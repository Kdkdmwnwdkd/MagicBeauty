package com.example.magicbeauty.engine

/**
 * 滤镜预设：用「饱和度 + 通道增益 + 提黑」组合出来的 3x3 色彩矩阵 + 偏移。
 * 相比一堆 if-else 分支，矩阵能让 GPU 一次算完，视频逐帧也不掉速。
 *
 * 矩阵都是对称阵，所以行主序 / 列主序写出来一样（GLES2 只接受 transpose=false）。
 */
object Filters {

    data class Preset(val name: String, val m: FloatArray, val off: FloatArray)

    private const val LR = 0.299f
    private const val LG = 0.587f
    private const val LB = 0.114f

    /**
     * @param sat     饱和度（1 = 不变）
     * @param gainR/G/B 通道增益（做冷暖）
     * @param lift    整体提亮（抬黑位，做胶片/复古感）
     * @param contrast 对比度微调（1 = 不变）
     */
    private fun preset(
        name: String,
        sat: Float,
        gainR: Float = 1f,
        gainG: Float = 1f,
        gainB: Float = 1f,
        lift: Float = 0f,
        contrast: Float = 1f
    ): Preset {
        // 饱和度矩阵：(1-s)*外积(luma) + s*I，再乘通道增益、乘对比度
        val a = 1f - sat
        val r0 = (a * LR + sat) * gainR * contrast
        val r1 = (a * LG) * gainR * contrast
        val r2 = (a * LB) * gainR * contrast
        val g0 = (a * LR) * gainG * contrast
        val g1 = (a * LG + sat) * gainG * contrast
        val g2 = (a * LB) * gainG * contrast
        val b0 = (a * LR) * gainB * contrast
        val b1 = (a * LG) * gainB * contrast
        val b2 = (a * LB + sat) * gainB * contrast
        // 行主序 -> 列主序。GLES2 的 glUniformMatrix3fv 只接受 transpose=false，
        // 也就是必须自己按列排好；直接传行主序会让矩阵被“转置”，
        // 黑白滤镜会变成绿色（三行相同 → 转置后每列恒定）。
        val m = floatArrayOf(
            r0, g0, b0,
            r1, g1, b1,
            r2, g2, b2
        )
        // 对比度带来的 0.5 偏移要补回来，再叠加提亮
        val k = (1f - contrast) * 0.5f
        val off = floatArrayOf(k + lift, k + lift, k + lift)
        return Preset(name, m, off)
    }

    /** 顺序要和 layout 里滤镜标签的顺序一致 */
    val presets: List<Preset> = listOf(
        preset("原图", 1.00f),
        preset("清透", 1.06f, 1.02f, 1.03f, 1.07f, 0.012f, 1.04f),
        preset("暖阳", 1.08f, 1.09f, 1.02f, 0.92f, 0.006f, 1.02f),
        preset("冷调", 0.96f, 0.95f, 1.00f, 1.10f, 0.004f, 1.03f),
        preset("复古", 0.80f, 1.06f, 1.00f, 0.90f, 0.045f, 0.96f),
        preset("黑白", 0.00f, 1.02f, 1.02f, 1.02f, 0.010f, 1.05f),
        preset("胶片", 0.90f, 1.03f, 1.02f, 0.97f, 0.030f, 0.97f)
    )

    fun get(id: Int): Preset = presets.getOrElse(id) { presets[0] }
}
