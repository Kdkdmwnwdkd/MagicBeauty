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

    private var texSrc = 0
    private var texA = 0   // warp 输出
    private var texB = 0   // adjust 输出
    private var fboA = 0
    private var fboB = 0

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
        return b
    }

    /** 用当前参数渲染一帧，返回处理后的 Bitmap（已翻转回正常方向）。 */
    fun render(params: EditParams, guide: WarpGuide = WarpGuide()): Bitmap {
        initGL()
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // —— 段①：Warp ——
        GLES20.glUseProgram(warpProg)
        bindQuad(warpProg)
        bindTex(warpProg, "uTex", texSrc, 0)
        val fc = guide.faceCenter; val el = guide.eyeL; val er = guide.eyeR; val bc = guide.bodyCenter
        f(warpProg, "uFaceCenter", fc.x, fc.y)
        f(warpProg, "uFaceHalfH", guide.faceHalfH)
        f(warpProg, "uThinFace", params.thinFace)
        f(warpProg, "uEyeL", el.x, el.y)
        f(warpProg, "uEyeR", er.x, er.y)
        f(warpProg, "uEyeRadius", guide.eyeRadius)
        f(warpProg, "uEyeEnlarge", params.eyeEnlarge)
        f(warpProg, "uBodyCenter", bc.x, bc.y)
        f(warpProg, "uBodyHalfW", guide.bodyHalfW)
        f(warpProg, "uWaistY", guide.waistY)
        f(warpProg, "uWaistSlim", params.waistSlim)
        f(warpProg, "uHipY", guide.hipY)
        f(warpProg, "uAnkleY", guide.ankleY)
        f(warpProg, "uLegStretch", params.legStretch)
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
        drawQuad()

        return readFramebuffer(fboB)
    }

    fun release() {
        if (!initialized) return
        if (texSrc != 0) GLES20.glDeleteTextures(1, intArrayOf(texSrc), 0)
        if (texA != 0) GLES20.glDeleteTextures(1, intArrayOf(texA), 0)
        if (texB != 0) GLES20.glDeleteTextures(1, intArrayOf(texB), 0)
        if (fboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglTerminate(eglDisplay)
        initialized = false
        texSrc = 0; texA = 0; texB = 0
        fboA = 0; fboB = 0
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
        if (fboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboA), 0)
        if (fboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fboB), 0)
        val a = createTarget(w, h); texA = a.first; fboA = a.second
        val b = createTarget(w, h); texB = b.first; fboB = b.second
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
        val pb = FloatBuffer.wrap(quadPos)
        GLES20.glEnableVertexAttribArray(pLoc)
        GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 0, pb)
        val ub = FloatBuffer.wrap(quadUV)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, ub)
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
}
