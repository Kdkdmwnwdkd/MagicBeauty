package com.example.magicbeauty.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.opengl.EGL14
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 本地图片编辑引擎（开源、纯端侧，不调用任何 API）。
 * 流程：Bitmap -> 纹理 -> [Warp 形变段] -> [Adjust 调色/磨皮段] -> FBO -> 读出 Bitmap。
 * 使用离屏 EGL Pbuffer 上下文，可在任意线程对静态图做确定性渲染。
 */
class LocalEditEngine {

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var initialized = false

    private var warpProg = 0
    private var adjustProg = 0
    private var overlayProg = 0

    private var texSrc = 0
    private var texMask = 0
    private var maskRef: ByteArray? = null
    private var maskN = 0
    private var maskOk = false
    private var texDisp = 0
    private var dispKey = ""
    private var texCurve = 0
    private var curveRef: IntArray? = null
    private var curveVer = -1
    private var texA = 0   // warp 输出
    private var texB = 0   // adjust 输出
    private var texC = 0   // overlay 输出
    private var fboA = 0
    private var fboB = 0
    private var fboC = 0

    // 贴纸纹理缓存：Bitmap 引用 -> 纹理名（拖滑块时避免每帧重传）。
    // accessOrder=true：get/put 都算访问，淘汰时先丢最久未用的那张。
    private val stickerTex = java.util.LinkedHashMap<Bitmap, Int>(16, 0.75f, true)
    // 马赛克 mask 纹理缓存（mask 是同一实例反复重画，按版本号判断重传）
    private var texMosaic = 0
    private var mosaicRef: Bitmap? = null
    private var mosaicVer = -1

    private var width = 0
    private var height = 0
    private var targetsW = 0
    private var targetsH = 0

    // 复用缓冲：视频逐帧处理时避免每帧分配几十 MB（否则 GC 抖动会明显拖慢速度）
    private var flipBitmap: Bitmap? = null
    private var flipCanvas: Canvas? = null
    private var uploadBuf: ByteBuffer? = null
    private var readBuf: ByteBuffer? = null
    private var readBytes: ByteArray? = null
    private var readPixels: IntArray? = null

    private val quadPos = floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f,
        -1f, 1f, 1f, -1f, 1f, 1f
    )
    private val quadUV = floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f,
        0f, 1f, 1f, 0f, 1f, 1f
    )

    // 关键：glVertexAttribPointer 只接受 native order 的 direct Buffer。
    // 用 FloatBuffer.wrap() 会抛 IllegalArgumentException("Must use a native order direct Buffer")，
    // 之前正是这个异常被静默吞掉，导致预览一直空白。
    private val quadPosBuf: FloatBuffer = ByteBuffer.allocateDirect(quadPos.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadPos); position(0) }
    private val quadUVBuf: FloatBuffer = ByteBuffer.allocateDirect(quadUV.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadUV); position(0) }

    private fun initGL() {
        if (initialized) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "无法获取 EGL Display" }
        // 不要传 null：部分 ROM 的实现会在这里 NPE，传真实数组最稳
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL 初始化失败" }

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        check(num[0] > 0 && configs[0] != null) { "找不到可用的 EGL 配置" }
        val cfg = configs[0]!!
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, pbAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        warpProg = buildProgram(Shaders.VERTEX, Shaders.WARP_FRAG)
        adjustProg = buildProgram(Shaders.VERTEX, Shaders.ADJUST_FRAG)
        overlayProg = buildProgram(Shaders.VERTEX, Shaders.OVERLAY_FRAG)
        initialized = true
    }

    /** 设置源图。btmp 会被等比缩放到 maxDim 以内。 */
    fun setSource(bitmap: Bitmap, maxDim: Int = 1280): Bitmap {
        initGL()
        // EGL 上下文是线程绑定的：每次调用都重新 makeCurrent，
        // 这样即使调用方换了线程（协程 IO 池）也能保证 GL 有效。
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        var b = bitmap
        val longer = maxOf(b.width, b.height)
        if (longer > maxDim) {
            val s = maxDim.toFloat() / longer
            b = Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
        }
        width = b.width
        height = b.height
        // GL 纹理原点在左下，上传前先垂直翻转，保证预览/输出方向正确
        // （复用同一张翻转位图；源图不透明且变换覆盖整幅，不必先清空）
        val flipped: Bitmap = if (flipBitmap?.width == b.width && flipBitmap?.height == b.height) {
            flipBitmap!!
        } else {
            try { flipBitmap?.recycle() } catch (_: Exception) {}
            Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
                flipBitmap = it
                flipCanvas = Canvas(it)
            }
        }
        val canvas = flipCanvas ?: Canvas(flipped).also { flipCanvas = it }
        val m = Matrix().apply { postScale(1f, -1f, b.width / 2f, b.height / 2f) }
        canvas.drawBitmap(b, m, null)
        texSrc = uploadTexture(flipped, texSrc)
        ensureTargets(width, height)
        // 换图 = 旧马赛克 mask 全部作废（尺寸/内容都不再匹配），必须清缓存状态，
        // 否则用户给新图涂马赛克时会看到上一张图的涂抹痕迹。
        if (texMosaic != 0) GLES20.glDeleteTextures(1, intArrayOf(texMosaic), 0)
        texMosaic = 0
        mosaicRef = null
        mosaicVer = -1
        // 位移场缓存同时失效：引导坐标随新图重新检测，dispKey 换了自然重建，
        // 但人体蒙版引用也要重置，逼 render 重新上传（见 render 段①）
        maskRef = null; maskN = 0; maskOk = false
        dispKey = ""
        return b
    }

    /** 用当前参数渲染一帧，返回处理后的 Bitmap（已翻转回正常方向）。 */
    fun render(
        params: EditParams,
        guide: WarpGuide = WarpGuide(),
        overlays: OverlaySpec = OverlaySpec()
    ): Bitmap {
        initGL()
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // 统一在这里做“没检测到就不变形”的兜底，视频/照片两条链路都不会再把效果套到画面中央。
        // 拉腿起点、腿段是否可用、瘦腰是否可用，都在 WarpGuide 里按穿着算好（见 legTopResolved）。
        val eff = params.copy(
            thinFace = if (guide.hasFace) params.thinFace else 0f,
            eyeEnlarge = if (guide.hasFace)
                (params.eyeEnlarge * guide.eyeGain).coerceIn(0f, 0.40f) else 0f,
            waistSlim = if (guide.waistOk) params.waistSlim else 0f,
            legStretch = if (guide.legOk) params.legStretch else 0f,
            // 面部重塑：没检出脸就不做（默认坐标硬套会把鼻子收歪）
            noseSlim = if (guide.faceFeatureOk) params.noseSlim else 0f,
            mouthSlim = if (guide.faceFeatureOk) params.mouthSlim else 0f,
            chinSlim = if (guide.faceFeatureOk) params.chinSlim else 0f,
            // 塑形四件套：各自的引导不可用就不做
            shoulderSlim = if (guide.shoulderOk) params.shoulderSlim else 0f,
            armSlim = if (guide.armOk) params.armSlim else 0f,
            hipSlim = if (guide.hipOk) params.hipSlim else 0f,
            legSlim = if (guide.legSlimOk) params.legSlim else 0f
        )

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)

        // —— 段①：Warp ——
        GLES20.glUseProgram(warpProg)
        bindQuad(warpProg)
        bindTex(warpProg, "uTex", texSrc, 0)

        // 人体蒙版：只有解析成功时才有，没有就退回“全图作用”（旧行为）
        val pm = guide.personMask
        val hasMask = pm != null && guide.maskSize > 0
        if (hasMask && maskRef !== pm) {
            texMask = uploadMask(pm!!, guide.maskSize, texMask)
            // 上传失败（返回 0，蒙版几乎全空）时不锁定引用，下次渲染还会重试
            maskRef = if (texMask != 0) pm else null
            maskN = guide.maskSize
            maskOk = texMask != 0
            bindTex(warpProg, "uTex", texSrc, 0)   // 恢复单元 0
        }
        f(warpProg, "uHasMask", if (hasMask && maskOk) 1f else 0f)
        val mtex = if (maskN > 0) 1.6f / maskN else 1.6f / 473f
        f(warpProg, "uMaskTexel", mtex, mtex)
        bindTex(warpProg, "uMask", if (hasMask && maskOk) texMask else texSrc, 1)
        val fc = guide.faceCenter; val el = guide.eyeL; val er = guide.eyeR
        f(warpProg, "uAspect", aspect)
        f(warpProg, "uFaceCenter", fc.x, fc.y)
        f(warpProg, "uFaceHalfW", guide.faceHalfW)
        f(warpProg, "uFaceHalfH", guide.faceHalfH)
        f(warpProg, "uThinFace", eff.thinFace)
        f(warpProg, "uEyeL", el.x, el.y)
        f(warpProg, "uEyeR", er.x, er.y)
        f(warpProg, "uEyeRadius", guide.eyeRadius)
        f(warpProg, "uEyeEnlarge", eff.eyeEnlarge)
        // 面部重塑（瘦鼻/缩唇/瘦下巴）
        f(warpProg, "uNose", guide.nose.x, guide.nose.y)
        f(warpProg, "uNoseHalfW", guide.noseHalfW)
        f(warpProg, "uNoseSlim", eff.noseSlim)
        f(warpProg, "uMouth", guide.mouth.x, guide.mouth.y)
        f(warpProg, "uMouthHalfW", guide.mouthHalfW)
        f(warpProg, "uMouthSlim", eff.mouthSlim)
        f(warpProg, "uChin", guide.chin.x, guide.chin.y)
        f(warpProg, "uChinHalfW", guide.chinHalfW)
        f(warpProg, "uChinSlim", eff.chinSlim)
        // 拉腿 / 瘦腰 / 瘦肩 / 瘦手臂 / 瘦臀 / 瘦腿：上传位移场纹理
        val dispOn = (eff.legStretch > 0.001f && guide.legOk) ||
                (eff.waistSlim > 0.001f && guide.waistOk) ||
                (eff.shoulderSlim > 0.001f && guide.shoulderOk) ||
                (eff.armSlim > 0.001f && guide.armOk) ||
                (eff.hipSlim > 0.001f && guide.hipOk) ||
                (eff.legSlim > 0.001f && guide.legSlimOk)
        if (dispOn) {
            // 缓存键必须覆盖 WarpField.build 读到的每一个引导字段，
            // 漏一个就会在手动拖定位线/重新检测时沿用旧位移场（形变对不上位置）
            val key = "${eff.legStretch}|${eff.waistSlim}|${eff.shoulderSlim}|${eff.armSlim}|" +
                    "${eff.hipSlim}|${eff.legSlim}|${guide.legTopResolved}|" +
                    "${guide.ankleY}|${guide.manualAnkleY ?: -1f}|${guide.manualHemY ?: -1f}|" +
                    "${guide.waistY}|${guide.bodyHalfW}|${guide.bodyCenter.x}|" +
                    "${guide.bodyTopY}|${guide.bodyBottomY}|" +
                    "${guide.shoulderY}|${guide.shoulderHalfW}|${guide.armTopY}|${guide.armBotY}|" +
                    "${guide.armHalfW}|${guide.armCxL}|${guide.armCxR}|${guide.hipY}|" +
                    "${guide.hipHalfW}|${guide.legHalfW}|" +
                    "${guide.clothesHalfW}|${guide.clothesEdgeLift}|${guide.maskSize}|" +
                    "${System.identityHashCode(guide.personMask)}"
            if (key != dispKey) {
                val field = WarpField.build(guide, eff, aspect)
                texDisp = uploadDisp(field, texDisp)
                dispKey = key
            }
            f(warpProg, "uHasDisp", 1f)
            f(warpProg, "uDispScale", WarpField.ENCODE_SCALE)
            bindTex(warpProg, "uDisp", texDisp, 2)
            bindTex(warpProg, "uTex", texSrc, 0)   // 恢复单元 0
        } else {
            f(warpProg, "uHasDisp", 0f)
            dispKey = ""
        }
        drawQuad()

        // —— 段②：Adjust ——
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(adjustProg)
        bindQuad(adjustProg)
        bindTex(adjustProg, "uTex", texA, 0)
        f(adjustProg, "uTexel", 1f / width, 1f / height)
        f(adjustProg, "uBrightness", params.brightness)
        f(adjustProg, "uContrast", params.contrast)
        f(adjustProg, "uSaturation", params.saturation)
        f(adjustProg, "uWarmth", params.warmth)
        f(adjustProg, "uBlur", params.blur)
        f(adjustProg, "uVignette", params.vignette)
        f(adjustProg, "uWhiten", params.whiten)
        // 调色进阶：锐化/颗粒/高光/阴影
        f(adjustProg, "uSharpen", params.sharpen)
        f(adjustProg, "uGrain", params.grain)
        f(adjustProg, "uHighlight", params.highlight)
        f(adjustProg, "uShadow", params.shadow)
        // HSL 六色分频（x=色相 y=饱和 z=明度）
        vec3(adjustProg, "uHslR", floatArrayOf(params.hslHue[0], params.hslSat[0], params.hslLum[0]))
        vec3(adjustProg, "uHslO", floatArrayOf(params.hslHue[1], params.hslSat[1], params.hslLum[1]))
        vec3(adjustProg, "uHslG", floatArrayOf(params.hslHue[2], params.hslSat[2], params.hslLum[2]))
        vec3(adjustProg, "uHslC", floatArrayOf(params.hslHue[3], params.hslSat[3], params.hslLum[3]))
        vec3(adjustProg, "uHslB", floatArrayOf(params.hslHue[4], params.hslSat[4], params.hslLum[4]))
        vec3(adjustProg, "uHslM", floatArrayOf(params.hslHue[5], params.hslSat[5], params.hslLum[5]))
        // 曲线 LUT（内容没变就复用纹理）
        val lut = params.curveLut
        if (lut != null && lut.size >= 256 && params.curveVersion != curveVer) {
            texCurve = uploadCurve(lut, texCurve)
            curveRef = lut
            curveVer = params.curveVersion
        }
        f(adjustProg, "uHasCurve", if (lut != null && lut.size >= 256 && texCurve != 0) 1f else 0f)
        bindTex(adjustProg, "uCurve", if (texCurve != 0) texCurve else texA, 3)
        val preset = Filters.get(params.filterId)
        mat3(adjustProg, "uFilterMat", preset.m)
        vec3(adjustProg, "uFilterOff", preset.off)
        drawQuad()

        // —— 段③：Overlay（贴纸/文字层 + 马赛克笔 + 边框）——
        // 没有叠加内容就跳过，Adjust 的输出就是最终结果
        if (!overlays.active && params.borderWidth <= 0f) {
            return readFramebuffer(fboB)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboC)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(overlayProg)
        bindQuad(overlayProg)
        bindTex(overlayProg, "uBase", texB, 0)
        f(overlayProg, "uAspectB", aspect)

        // 边框
        f(overlayProg, "uBorderW", params.borderWidth.coerceIn(0f, 0.08f))
        vec3(overlayProg, "uBorderCol", params.borderColor)

        // 马赛克
        val mos = overlays.mosaic
        f(overlayProg, "uMosaicOn", if (mos.enabled) 1f else 0f)
        f(overlayProg, "uMosaicBlock", mos.block.coerceIn(0.008f, 0.2f))
        f(overlayProg, "uMosaicFull", if (mos.enabled && !mos.brushOnly) 1f else 0f)
        val mask = if (mos.enabled && mos.brushOnly) mos.mask else null
        if (mask != null && (mask !== mosaicRef || mos.maskVersion != mosaicVer)) {
            texMosaic = uploadMaskFlipped(mask, texMosaic)
            mosaicRef = mask
            mosaicVer = mos.maskVersion
        }
        bindTex(overlayProg, "uMosaicMask", if (texMosaic != 0) texMosaic else texB, 1)

        // 贴纸层（最多 4 层）。上传走纹理单元 7，避免覆盖单元 0 上的 uBase
        GLES20.glActiveTexture(GLES20.GL_TEXTURE7)
        val stickers = overlays.stickers.take(4)
        f(overlayProg, "uLayerCount", stickers.size.toFloat())
        stickers.forEachIndexed { i, st ->
            var tex = stickerTex[st.bitmap]
            if (tex == null) {
                // 缓存上限 16 张，淘汰最久未用的（accessOrder 的 LinkedHashMap）
                if (stickerTex.size >= 16) {
                    val oldest = stickerTex.keys.firstOrNull()
                    if (oldest != null) {
                        stickerTex.remove(oldest)?.let { t ->
                            GLES20.glDeleteTextures(1, intArrayOf(t), 0)
                        }
                    }
                }
                // OVERLAY_FRAG 的贴纸采样期望 v=1 对应位图顶行（与 uBase 同向），
                // 而 uploadTexture 不翻转、纹理 v=0 落在位图首行——必须先垂直翻转再上传，
                // 否则贴纸整体上下颠倒（与 uBase 的翻转规则保持一致）
                val flipped = Bitmap.createBitmap(st.bitmap.width, st.bitmap.height, Bitmap.Config.ARGB_8888)
                val cv = Canvas(flipped)
                cv.drawBitmap(st.bitmap, Matrix().apply { postScale(1f, -1f, st.bitmap.width / 2f, st.bitmap.height / 2f) }, null)
                tex = uploadTexture(flipped, 0)
                flipped.recycle()   // 纹理已在 GPU 侧，临时位图立即释放
                stickerTex[st.bitmap] = tex
            }
            bindTex(overlayProg, "uLayers[$i]", tex, 2 + i)
            f4(overlayProg, "uLayerT[$i]", st.cx, st.cy, st.scale, st.rotation)
        }
        drawQuad()

        return readFramebuffer(fboC)
    }

    fun release() {
        if (!initialized) return
        if (texSrc != 0) GLES20.glDeleteTextures(1, intArrayOf(texSrc), 0)
        if (texMask != 0) GLES20.glDeleteTextures(1, intArrayOf(texMask), 0)
        if (texDisp != 0) GLES20.glDeleteTextures(1, intArrayOf(texDisp), 0)
        if (texCurve != 0) GLES20.glDeleteTextures(1, intArrayOf(texCurve), 0)
        if (texA != 0) GLES20.glDeleteTextures(1, intArrayOf(texA), 0)
        if (texB != 0) GLES20.glDeleteTextures(1, intArrayOf(texB), 0)
        if (texC != 0) GLES20.glDeleteTextures(1, intArrayOf(texC), 0)
        if (texMosaic != 0) GLES20.glDeleteTextures(1, intArrayOf(texMosaic), 0)
        stickerTex.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        stickerTex.clear()
        if (warpProg != 0) GLES20.glDeleteProgram(warpProg)
        if (adjustProg != 0) GLES20.glDeleteProgram(adjustProg)
        if (overlayProg != 0) GLES20.glDeleteProgram(overlayProg)
        if (fboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        if (fboC != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboC), 0)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglTerminate(eglDisplay)
        initialized = false
        texSrc = 0; texA = 0; texB = 0; texC = 0; texMask = 0; texDisp = 0; texCurve = 0; texMosaic = 0
        warpProg = 0; adjustProg = 0; overlayProg = 0
        maskRef = null; maskN = 0; maskOk = false; dispKey = ""
        curveRef = null; curveVer = -1
        mosaicRef = null; mosaicVer = -1
        fboA = 0; fboB = 0; fboC = 0
        targetsW = 0; targetsH = 0
        try { flipBitmap?.recycle() } catch (_: Exception) {}
        flipBitmap = null; flipCanvas = null
        uploadBuf = null; readBuf = null; readBytes = null; readPixels = null
    }

    // ---------- GL 辅助 ----------

    private fun ensureTargets(w: Int, h: Int) {
        // 尺寸没变就复用，避免视频逐帧重建全屏纹理（原来每帧都重建，极慢）
        if (targetsW == w && targetsH == h && texA != 0 && texB != 0 && fboA != 0 && fboB != 0) return
        if (texA != 0) GLES20.glDeleteTextures(1, intArrayOf(texA), 0)
        if (texB != 0) GLES20.glDeleteTextures(1, intArrayOf(texB), 0)
        if (texC != 0) GLES20.glDeleteTextures(1, intArrayOf(texC), 0)
        if (fboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        if (fboC != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboC), 0)
        val a = createTarget(w, h); texA = a.first; fboA = a.second
        val b = createTarget(w, h); texB = b.first; fboB = b.second
        val c = createTarget(w, h); texC = c.first; fboC = c.second
        targetsW = w; targetsH = h
    }

    /** 创建“纹理 + 绑定它的 FBO”，返回 (纹理名, FBO名)。 */
    private fun createTarget(w: Int, h: Int): Pair<Int, Int> {
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1); GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return tex[0] to fbo[0]
    }

    private fun uploadTexture(bmp: Bitmap, reuse: Int): Int {
        val tex = IntArray(1)
        if (reuse == 0) GLES20.glGenTextures(1, tex, 0) else tex[0] = reuse
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // Android 的 ARGB int 在内存里是小端 B,G,R,A；直接按 GL_RGBA 上传会红蓝互换。
        // 这里先交换 R/B，上传后颜色才正确（红色不会再变蓝）。
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and 0xFF00FF00.toInt()) or
                    ((p and 0x00FF0000) shr 16) or
                    ((p and 0x000000FF) shl 16)
        }
        val need = pixels.size * 4
        val buf = uploadBuf?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { uploadBuf = it }
        buf.clear()
        buf.asIntBuffer().put(pixels)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bmp.width, bmp.height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    /** 上传人体蒙版（单通道）。Bitmap 首行是顶部，GL 纹理 v=0 在底部，所以要按行翻转。 */
    private fun uploadMask(data: ByteArray, n: Int, reuse: Int): Int {
        // 蒙版几乎全空时直接不用它（否则权重全是 0，所有形变都会失效）
        var nonZero = 0
        for (v in data) if (v.toInt() != 0) nonZero++
        if (nonZero * 200 < n * n) return 0

        val tex = IntArray(1)
        if (reuse == 0) GLES20.glGenTextures(1, tex, 0) else tex[0] = reuse
        // 关键：必须切到纹理单元 1 再绑定。否则会把单元 0 上绑着的主图替换掉，
        // uTex 采到的是蒙版本身 → 整张图变黑（之前那一版的黑屏就是这个原因）。
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        // 用 RGBA 而不是 LUMINANCE：部分机型对非 2 次幂的 LUMINANCE 纹理采样会返回 0，
        // 表现同样是“拉滑块完全没反应”。
        val buf = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until n) {
            val srcRow = (n - 1 - y) * n
            for (x in 0 until n) {
                val v = data[srcRow + x]
                buf.put(v); buf.put(v); buf.put(v); buf.put(255.toByte())
            }
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n, n, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    /**
     * 上传位移场（RGBA，R=du、G=dv，按 ENCODE_SCALE 编码）。
     * 用 RGBA 而非 LA/LUMINANCE：部分机型对非 2 次幂的亮度纹理采样会返回 0。
     */
    private fun uploadDisp(data: FloatArray, reuse: Int): Int {
        val w = WarpField.W
        val h = WarpField.H
        val tex = IntArray(1)
        if (reuse == 0) GLES20.glGenTextures(1, tex, 0) else tex[0] = reuse
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])

        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        var i = 0
        val inv = 1f / WarpField.ENCODE_SCALE
        for (y in 0 until h) {
            for (x in 0 until w) {
                val du = data[i++]
                val dv = data[i++]
                buf.put(((du * inv + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                buf.put(((dv * inv + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                buf.put(0.toByte())
                buf.put(255.toByte())
            }
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return tex[0]
    }

    /** 上传曲线 LUT（256x1 RGB；每项 0xRRGGBB）。 */
    private fun uploadCurve(lut: IntArray, reuse: Int): Int {
        val tex = IntArray(1)
        if (reuse == 0) GLES20.glGenTextures(1, tex, 0) else tex[0] = reuse
        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val buf = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 256) {
            val p = lut[i]
            buf.put((p shr 16 and 0xff).toByte())
            buf.put((p shr 8 and 0xff).toByte())
            buf.put((p and 0xff).toByte())
            buf.put(255.toByte())
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        // 曲线采样必须关闭线性插值：LUT 是分段函数，插值会把折角磨圆
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return tex[0]
    }

    private fun readFramebuffer(fbo: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        val need = width * height * 4
        val buf = readBuf?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { readBuf = it }
        buf.clear()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        // 注意：allocateDirect 的 Buffer 没有可访问的 backing array，
        // 调 buf.array() 会抛 UnsupportedOperationException（这正是之前必崩的原因）。
        // 正确做法是先把数据读进普通 ByteArray。
        val bytes = readBytes?.takeIf { it.size >= need } ?: ByteArray(need).also { readBytes = it }
        buf.position(0)
        buf.get(bytes, 0, need)

        val pxCount = width * height
        val pixels = readPixels?.takeIf { it.size >= pxCount } ?: IntArray(pxCount).also { readPixels = it }
        for (y in 0 until height) {
            val srcRow = (height - 1 - y) * width * 4
            val dstRow = y * width
            for (x in 0 until width) {
                val i = srcRow + x * 4
                val r = bytes[i].toInt() and 0xff
                val g = bytes[i + 1].toInt() and 0xff
                val b = bytes[i + 2].toInt() and 0xff
                val a = bytes[i + 3].toInt() and 0xff
                pixels[dstRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        // createBitmap 会复制像素，因此 pixels 可以安全复用
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link 失败" }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader 编译失败: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun bindQuad(prog: Int) {
        val pLoc = GLES20.glGetAttribLocation(prog, "aPos")
        val uvLoc = GLES20.glGetAttribLocation(prog, "aUV")
        quadPosBuf.position(0)
        GLES20.glEnableVertexAttribArray(pLoc)
        GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 0, quadPosBuf)
        quadUVBuf.position(0)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, quadUVBuf)
    }

    private fun drawQuad() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    private fun bindTex(prog: Int, name: String, tex: Int, unit: Int) {
        val loc = GLES20.glGetUniformLocation(prog, name)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(loc, unit)
    }

    private fun f(prog: Int, name: String, v: Float) {
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, name), v)
    }

    private fun f(prog: Int, name: String, x: Float, y: Float) {
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, name), x, y)
    }

    private fun f4(prog: Int, name: String, x: Float, y: Float, z: Float, w: Float) {
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, name), x, y, z, w)
    }

    /**
     * 上传马赛克涂抹 mask（Bitmap，白色=涂抹区）。
     * 位图是 y 向下的，纹理 v=0 在底部，按行翻转；mask 值取 R 通道。
     */
    private fun uploadMaskFlipped(bmp: Bitmap, reuse: Int): Int {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val tex = IntArray(1)
        if (reuse == 0) GLES20.glGenTextures(1, tex, 0) else tex[0] = reuse
        // 绑到单元 1：bindTex(uMosaicMask, 1) 随后会重新绑一次，互不影响
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until h) {
            val srcRow = (h - 1 - y) * w
            for (x in 0 until w) {
                val v = (px[srcRow + x] shr 16) and 0xff   // 白色=255
                buf.put(v.toByte()); buf.put(v.toByte()); buf.put(v.toByte()); buf.put(255.toByte())
            }
        }
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return tex[0]
    }

    /** 3x3 色彩矩阵。我们的矩阵都是对称阵，行/列主序一致，直接用 transpose=false。 */
    private fun mat3(prog: Int, name: String, m: FloatArray) {
        GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(prog, name), 1, false, m, 0)
    }

    private fun vec3(prog: Int, name: String, v: FloatArray) {
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(prog, name), 1, v, 0)
    }
}
