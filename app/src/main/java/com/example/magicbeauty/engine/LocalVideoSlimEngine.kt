package com.example.magicbeauty.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 端侧视频瘦身引擎（开源、纯本地、离线、零成本）：
 * 解码 -> 逐帧 MediaPipe 提关键点 -> OpenGL 形变 -> 重编码，音轨原样透传。
 *
 * 相比旧版修正了 5 个硬伤：
 *  1) ImageReader 是异步的，必须阻塞 acquire，不能 release 后立刻 acquireLatestImage（会一直拿不到帧）；
 *  2) ByteBuffer 输入的编码器不能用 signalEndOfInputStream()，必须自己 queue 带 EOS 标志的空 buffer；
 *  3) Flexible 格式要走 getInputImage() 按 stride 写平面，不能直接塞紧凑 NV12；
 *  4) 音频轨透传，输出视频不再没声音；
 *  5) 读取 KEY_ROTATION 并写入 muxer，竖拍视频不再方向错误。
 *
 * 性能提示：手机逐帧处理本身较慢，建议先用 10 秒内的短视频。
 */
class LocalVideoSlimEngine(private val context: Context) {

    fun process(
        inputFile: File,
        outputFile: File,
        waistSlim: Float,
        legStretch: Float,
        onProgress: (done: Int, total: Int) -> Unit,
        onLog: (String) -> Unit
    ): File {
        onLog("开始端侧视频瘦身（本地处理，不联网）…")
        if (outputFile.exists()) outputFile.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)

        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = f
                break
            }
        }
        require(videoTrackIndex >= 0 && videoFormat != null) { "视频里没有找到视频轨道" }

        val format = videoFormat!!
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        // 编码尺寸必须与解码帧一致，不能沿用 setSource 的 1280 默认缩放
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
            format.getInteger(MediaFormat.KEY_FRAME_RATE).coerceIn(1, 60) else 30
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION))
            format.getInteger(MediaFormat.KEY_ROTATION) else 0
        extractor.selectTrack(videoTrackIndex)

        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, reader.surface, null, 0)
        decoder.start()

        val encoderColor = pickEncoderColor()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) {
            try { muxer.setOrientationHint(rotation) } catch (_: Exception) {}
        }
        val state = MuxerState()

        val mp = MediaPipeHelper(context)
        val engine = LocalEditEngine()
        val params = EditParams(waistSlim = waistSlim, legStretch = legStretch)
        var guide = WarpGuide()

        val dInfo = MediaCodec.BufferInfo()
        val eInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decoderDone = false
        var frameNo = 0
        var lastPts = 0L
        val totalFrames = estimateFrames(format, frameRate)

        var audioSource: AudioSource? = null
        // 编码器按第一帧处理后的实际尺寸懒创建，保证与位图尺寸严格一致
        var encoder: MediaCodec? = null

        try {
            // 音频轨要在 muxer.start() 之前 addTrack，所以先准备好
            audioSource = prepareAudio(inputFile, muxer)

            while (!decoderDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(dInfo, 10_000)
                if (outIdx >= 0) {
                    val eos = (dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outIdx, true)

                    if (eos) {
                        decoderDone = true
                    } else {
                        // 阻塞等待这一帧真正到达（旧版在这里用 acquireLatestImage 直接拿到 null）
                        val image = reader.acquireNextImage()
                        if (image != null) {
                            try {
                                val bmp = yuvToBitmap(image)
                                if (frameNo % 10 == 0) {
                                    try { guide = mp.analyze(bmp) } catch (_: Exception) {}
                                }
                                // 限制处理分辨率：4K 逐帧位图会 OOM（手机端没必要按 4K 处理）
                                engine.setSource(bmp, PROCESS_MAX_DIM)
                                val out = engine.render(params, guide)
                                val enc = encoder ?: createEncoder(
                                    out.width, out.height, frameRate, encoderColor
                                ).also { encoder = it }
                                // PTS 兜底：个别容器时间戳缺失/重复，muxer 会拒绝非单调时间戳
                                var pts = dInfo.presentationTimeUs
                                if (pts <= lastPts) pts = lastPts + 33_333L
                                lastPts = pts
                                feedEncoder(enc, out, pts, eInfo, muxer, state, encoderColor)
                                frameNo++
                                if (frameNo % 5 == 0) {
                                    onProgress(frameNo.coerceAtMost(totalFrames), totalFrames)
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }
                }
            }

            // ByteBuffer 输入的编码器：必须自己塞 EOS。
            // 注意：dequeueInputBuffer 可能因输入队列满返回 -1，不能只试一次，
            // 否则 EOS 永远入不了队，后面的 drain 会一直等下去（卡死）。
            encoder?.let { enc ->
                var queued = false
                var tries = 0
                while (!queued && tries < 50) {
                    val eosIdx = enc.dequeueInputBuffer(10_000)
                    if (eosIdx >= 0) {
                        enc.queueInputBuffer(eosIdx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        queued = true
                    } else {
                        // 先排空输出，给输入队列腾位置，再重试
                        drainEncoder(enc, eInfo, muxer, state, untilEos = false)
                        tries++
                    }
                }
                drainEncoder(enc, eInfo, muxer, state, untilEos = true)
            }

            // 透传音频
            audioSource?.let { copyAudio(it, muxer, state) }

            onLog("端侧处理完成，共处理 $frameNo 帧")
            require(frameNo > 0) { "没有解出任何视频帧，可能是编码格式不受支持" }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { mp.close() } catch (_: Exception) {}
            try { engine.release() } catch (_: Exception) {}
            try { audioSource?.extractor?.release() } catch (_: Exception) {}
            if (state.started) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                    // stop 失败说明封装不完整，删掉半成品，避免用户拿到损坏视频
                    try { outputFile.delete() } catch (_: Exception) {}
                }
            }
            try { muxer.release() } catch (_: Exception) {}
        }

        require(outputFile.exists() && outputFile.length() > 0L) {
            "视频处理失败，建议换更短的视频，或在「设置」里填写云端瘦身终点"
        }
        return outputFile
    }

    // ---------- 编码器输入 ----------

    private fun feedEncoder(
        encoder: MediaCodec,
        bmp: Bitmap,
        pts: Long,
        info: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        state: MuxerState,
        color: Int
    ) {
        val inIdx = encoder.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            var size = bmp.width * bmp.height * 3 / 2
            if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                val img = encoder.getInputImage(inIdx)
                if (img != null) {
                    fillImage(img, bmp)
                } else {
                    val nv12 = bitmapToNV12(bmp)
                    encoder.getInputBuffer(inIdx)!!.apply { clear(); put(nv12) }
                    size = nv12.size
                }
            } else if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                val i420 = bitmapToI420(bmp)
                encoder.getInputBuffer(inIdx)!!.apply { clear(); put(i420) }
                size = i420.size
            } else {
                val nv12 = bitmapToNV12(bmp)
                encoder.getInputBuffer(inIdx)!!.apply { clear(); put(nv12) }
                size = nv12.size
            }
            encoder.queueInputBuffer(inIdx, 0, size, pts, 0)
        }
        drainEncoder(encoder, info, muxer, state, untilEos = false)
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        state: MuxerState,
        untilEos: Boolean
    ) {
        var idle = 0
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    // 等 EOS 时加个上限（约 2 秒），避免异常情况下无限轮询
                    idle++
                    if (idle > 200) return
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!state.started) {
                        state.videoTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        state.started = true
                    }
                }
                outIdx >= 0 -> {
                    idle = 0
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (buf != null && info.size > 0 && state.started) {
                        // 不要手动改 position/limit：MediaMuxer 会按 BufferInfo.offset 自己定位
                        muxer.writeSampleData(state.videoTrack, buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ---------- 音频透传 ----------

    private class AudioSource(val extractor: MediaExtractor, val track: Int, val buffer: ByteBuffer)

    private fun prepareAudio(file: File, muxer: MediaMuxer): AudioSource? {
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(file.absolutePath)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { idx = i; break }
            }
            if (idx < 0) { ex.release(); return null }
            ex.selectTrack(idx)
            val track = muxer.addTrack(ex.getTrackFormat(idx))
            AudioSource(ex, track, ByteBuffer.allocateDirect(1 shl 20))
        } catch (_: Exception) {
            null
        }
    }

    private fun copyAudio(a: AudioSource, muxer: MediaMuxer, state: MuxerState) {
        if (!state.started) return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = try { a.extractor.readSampleData(a.buffer, 0) } catch (_: Exception) { -1 }
            if (size < 0) break
            val flags = if ((a.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, a.extractor.sampleTime, flags)
            try { muxer.writeSampleData(a.track, a.buffer, info) } catch (_: Exception) { break }
            a.extractor.advance()
        }
    }

    // ---------- 格式转换 ----------

    private fun pickEncoderColor(): Int {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.release()
        val fmts = caps.colorFormats.toSet()
        return when {
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            fmts.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        }
    }

    /** 把 ARGB 写入编码器提供的 Image，自动遵守 rowStride / pixelStride。 */
    private fun fillImage(img: Image, bmp: Bitmap) {
        val w = img.width
        val h = img.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val ys = yP.rowStride; val yp = yP.pixelStride
        val us = uP.rowStride; val up = uP.pixelStride
        val vs = vP.rowStride; val vp = vP.pixelStride

        for (r in 0 until h) {
            for (c in 0 until w) {
                val p = argb[r * w + c]
                val rr = (p shr 16) and 0xff
                val gg = (p shr 8) and 0xff
                val bb = p and 0xff
                val y = (((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).coerceIn(0, 255)
                val yIdx = r * ys + c * yp
                // 注意用 limit() 而不是 capacity()：绝对位置写入超出 limit 会抛异常
                if (yIdx < yB.limit()) yB.put(yIdx, y.toByte())

                if ((r and 1) == 0 && (c and 1) == 0) {
                    val u = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(0, 255)
                    val uIdx = (r / 2) * us + (c / 2) * up
                    val vIdx = (r / 2) * vs + (c / 2) * vp
                    if (uIdx < uB.limit()) uB.put(uIdx, u.toByte())
                    if (vIdx < vB.limit()) vB.put(vIdx, v.toByte())
                }
            }
        }
    }

    /**
     * YUV_420_888(Image) -> Bitmap，直接算 ARGB，不再走 JPEG 中转（旧版每帧压 JPEG 极慢）。
     * 若源分辨率超过 [maxDim]，在转换时就按最近邻降采样，避免先分配一张 4K 大图。
     */
    private fun yuvToBitmap(image: Image, maxDim: Int = PROCESS_MAX_DIM): Bitmap {
        val sw = image.width
        val sh = image.height
        val longer = maxOf(sw, sh)
        val scale = if (longer > maxDim) maxDim.toFloat() / longer else 1f
        var ow = ((sw * scale).toInt().coerceAtLeast(2)) and 0x7FFE
        var oh = ((sh * scale).toInt().coerceAtLeast(2)) and 0x7FFE
        if (ow > sw) ow = sw
        if (oh > sh) oh = sh

        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val ys = yP.rowStride; val yp = yP.pixelStride
        val us = uP.rowStride; val up = uP.pixelStride
        val vs = vP.rowStride; val vp = vP.pixelStride

        val yArr = ByteArray(yP.buffer.remaining()).also { yP.buffer.rewind(); yP.buffer.get(it) }
        val uArr = ByteArray(uP.buffer.remaining()).also { uP.buffer.rewind(); uP.buffer.get(it) }
        val vArr = ByteArray(vP.buffer.remaining()).also { vP.buffer.rewind(); vP.buffer.get(it) }

        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = oy * sh / oh
            val yBase = sy * ys
            val uvRow = sy / 2
            val dstRow = oy * ow
            for (ox in 0 until ow) {
                val sx = ox * sw / ow
                val yi = yBase + sx * yp
                val y = if (yi < yArr.size) (yArr[yi].toInt() and 0xff) else 0
                val ui = uvRow * us + (sx / 2) * up
                val vi = uvRow * vs + (sx / 2) * vp
                val u = if (ui < uArr.size) (uArr[ui].toInt() and 0xff) else 128
                val v = if (vi < vArr.size) (vArr[vi].toInt() and 0xff) else 128

                val yf = (y - 16).coerceAtLeast(0)
                val uf = u - 128
                val vf = v - 128
                val rr = (1.164f * yf + 1.596f * vf).toInt().coerceIn(0, 255)
                val gg = (1.164f * yf - 0.392f * uf - 0.813f * vf).toInt().coerceIn(0, 255)
                val bb = (1.164f * yf + 2.017f * uf).toInt().coerceIn(0, 255)
                out[dstRow + ox] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    /** Bitmap -> NV12（Y 平面 + UV 交错，U 在前），用于 SemiPlanar。 */
    private fun bitmapToNV12(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h + w * h / 2)
        var yp = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = argb[yp]
                val r = (px shr 16) and 0xff
                val g = (px shr 8) and 0xff
                val b = px and 0xff
                yuv[yp] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val idx = w * h + (j / 2) * w + i
                    if (idx + 1 < yuv.size) {
                        yuv[idx] = u.toByte()
                        yuv[idx + 1] = v.toByte()
                    }
                }
                yp++
            }
        }
        return yuv
    }

    /** Bitmap -> I420（Y + U + V 三个平面），用于极少数只支持 Planar 的编码器。 */
    private fun bitmapToI420(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h + w * h / 2)
        for (j in 0 until h) for (i in 0 until w) {
            val px = argb[j * w + i]
            val r = (px shr 16) and 0xff
            val g = (px shr 8) and 0xff
            val b = px and 0xff
            out[j * w + i] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)).toByte()
        }
        var uPos = w * h
        var vPos = w * h + w * h / 4
        for (j in 0 until h step 2) for (i in 0 until w step 2) {
            var rs = 0; var gs = 0; var bs = 0
            for (dy in 0..1) for (dx in 0..1) {
                val px = argb[(j + dy) * w + (i + dx)]
                rs += (px shr 16) and 0xff
                gs += (px shr 8) and 0xff
                bs += px and 0xff
            }
            val r = rs / 4; val g = gs / 4; val b = bs / 4
            out[uPos++] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
            out[vPos++] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)).toByte()
        }
        return out
    }

    private data class MuxerState(var started: Boolean = false, var videoTrack: Int = -1)

    private fun createEncoder(w: Int, h: Int, frameRate: Int, color: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 4).coerceAtLeast(2_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        return enc
    }

    private companion object {
        /** 逐帧处理的分辨率上限，防止 4K 视频把内存打爆 */
        const val PROCESS_MAX_DIM = 1920
    }

    private fun estimateFrames(format: MediaFormat, frameRate: Int): Int {
        val dur = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        return if (dur > 0) ((dur / 1_000_000.0) * frameRate).toInt().coerceAtLeast(1) else 300
    }
}
