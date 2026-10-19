package com.example.magicbeauty.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 端侧视频瘦身引擎（开源、纯本地、离线、零成本）：
 * 解码视频 -> 逐帧用 MediaPipe 提人体关键点 -> 用 LocalEditEngine 做瘦腰/拉腿 warp -> 重新编码。
 *
 * 仅「视频瘦身」功能使用，全程不上传、不调任何外部 API。
 * 说明：手机端逐帧处理较慢（建议用 10 秒内的短视频测试）；若想要秒级体验或更高画质，
 * 可在「设置」页填入云端终点，走 VideoSlimApi 的云端路径。
 */
class LocalVideoSlimEngine(private val context: Context) {

    fun process(
        input: ByteArray,
        waistSlim: Float,
        legStretch: Float,
        onProgress: (done: Int, total: Int) -> Unit,
        onLog: (String) -> Unit
    ): ByteArray {
        onLog("开始端侧视频瘦身（手机本地处理，不联网）…")
        val inFile = File(context.cacheDir, "mb_in_${System.currentTimeMillis()}.mp4")
        val outFile = File(context.cacheDir, "mb_out_${System.currentTimeMillis()}.mp4")
        inFile.writeBytes(input)
        if (outFile.exists()) outFile.delete()

        val extractor = MediaExtractor()
        extractor.setDataSource(inFile.absolutePath)
        var vIdx = -1
        lateinit var vFormat: MediaFormat
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                vIdx = i; vFormat = f; break
            }
        }
        require(vIdx >= 0) { "视频里没有找到视频轨道" }
        val mime = vFormat.getString(MediaFormat.KEY_MIME)!!
        val width = vFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = vFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = if (vFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
            vFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        extractor.selectTrack(vIdx)

        // 解码：输出到 ImageReader 的 Surface，逐帧拿 YUV_420_888 图像
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(vFormat, reader.surface, null, 0)
        decoder.start()

        // 编码：H.264，ByteBuffer(NV12) 输入
        val encColor = pickEncoderColor()
        val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceAtLeast(2_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, encColor)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        val totalFrames = estimateFrames(vFormat, frameRate)

        try {
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
                    decoder.releaseOutputBuffer(outIdx, true)
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val bmp = yuvImageToBitmap(image, width, height)
                            if (frameNo % 10 == 0) {
                                try { guide = mp.analyze(bmp) } catch (_: Exception) {}
                            }
                            val out = run { engine.setSource(bmp); engine.render(params, guide) }
                            val nv12 = bitmapToNV12(out)
                            feedEncoder(encoder, nv12, dInfo.presentationTimeUs, eInfo, muxer, state)
                            frameNo++
                            if (frameNo % 5 == 0) onProgress(frameNo.coerceAtMost(totalFrames), totalFrames)
                        } finally {
                            image.close()
                        }
                    }
                    if ((dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true
                }
            }

            encoder.signalEndOfInputStream()
            drainEncoder(encoder, eInfo, muxer, state)
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { mp.close() } catch (_: Exception) {}
            try { engine.release() } catch (_: Exception) {}
            if (state.started) try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { inFile.delete() } catch (_: Exception) {}
        }

        onLog("端侧处理完成，共处理 $frameNo 帧")
        if (!outFile.exists() || outFile.length() == 0L) {
            throw Exception("视频处理失败，建议换更短的视频，或到「设置」填写云端瘦身终点")
        }
        return outFile.readBytes()
    }

    private fun pickEncoderColor(): Int {
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = enc.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.release()
        for (c in caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return c
        }
        for (c in caps.colorFormats) {
            if (c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                c == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return c
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun feedEncoder(encoder: MediaCodec, nv12: ByteArray, pts: Long, info: MediaCodec.BufferInfo,
                            muxer: MediaMuxer, state: MuxerState) {
        val inIdx = encoder.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf = encoder.getInputBuffer(inIdx)!!
            buf.clear()
            buf.put(nv12)
            encoder.queueInputBuffer(inIdx, 0, nv12.size, pts, 0)
        }
        drainEncoder(encoder, info, muxer, state)
    }

    private fun drainEncoder(encoder: MediaCodec, info: MediaCodec.BufferInfo, muxer: MediaMuxer, state: MuxerState) {
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (state.started) return else continue
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    state.track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    state.started = true
                }
                outIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIdx)!!
                    if (state.started) muxer.writeSampleData(state.track, buf, info)
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private data class MuxerState(var started: Boolean = false, var track: Int = -1)

    private fun estimateFrames(format: MediaFormat, frameRate: Int): Int {
        val dur = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        return if (dur > 0) (dur / 1_000_000 * frameRate).toInt().coerceAtLeast(1) else 300
    }

    /** YUV_420_888(Image) -> NV21 -> Bitmap。 */
    private fun yuvImageToBitmap(image: Image, w: Int, h: Int): Bitmap {
        val planes = image.planes
        val y = planes[0].buffer; val u = planes[1].buffer; val v = planes[2].buffer
        val yStride = planes[0].rowStride
        val uStride = planes[1].rowStride; val vStride = planes[2].rowStride
        val uPix = planes[1].pixelStride.coerceAtLeast(1)
        val vPix = planes[2].pixelStride.coerceAtLeast(1)
        val nv21 = ByteArray(w * h + w * h / 2)
        var pos = 0
        val yRow = ByteArray(yStride)
        for (row in 0 until h) {
            y.position(row * yStride); y.get(yRow, 0, w)
            System.arraycopy(yRow, 0, nv21, pos, w); pos += w
        }
        val uRow = ByteArray(uStride); val vRow = ByteArray(vStride)
        for (row in 0 until h / 2) {
            u.position(row * uStride); u.get(uRow, 0, w / 2 * uPix)
            v.position(row * vStride); v.get(vRow, 0, w / 2 * vPix)
            for (col in 0 until w / 2) {
                nv21[pos++] = vRow[col * vPix]   // V 在前（NV21）
                nv21[pos++] = uRow[col * uPix]   // U 在后
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 95, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw Exception("帧解码失败")
    }

    /** Bitmap -> NV12（Y 平面 + UV 交错，U 在前），用于喂编码器。 */
    private fun bitmapToNV12(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
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
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yp] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val idx = w * h + (j / 2) * w + i
                    yuv[idx] = u.coerceIn(0, 255).toByte()     // U 在前
                    yuv[idx + 1] = v.coerceIn(0, 255).toByte() // V 在后
                }
                yp++
            }
        }
        return yuv
    }
}
