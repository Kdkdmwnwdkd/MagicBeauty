package com.example.magicbeauty.engine

import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * 端侧「染发/换发色」引擎（开源、纯本地、零成本、离线可用）。
 *
 * 说明：这不是「生成式」——它不会凭空造出飘逸发丝（那必须云端大模型）。
 * 它做的是：在**头发掩码**范围内做色相/饱和度/明度变换，用来快速预览发色。
 *
 * 掩码来自 [HairSegmenter]（只覆盖头发，不会把皮肤、衣服、背景一起染色）。
 * 若分割失败（mask=null），会退回整图调色，保证功能不至于完全不可用。
 *
 * @param hueShift  色相旋转角度（度）。例如 50=偏棕黄，120=偏绿，300=偏紫红。
 * @param satBoost  饱和度增益（0~1，0.35 = +35%）。
 * @param lightBoost 明度增益（0~1，0.08 = 提亮 8%）。
 */
object LocalHairEngine {

    fun recolor(
        src: Bitmap,
        mask: ByteArray?,
        hueShift: Float = 50f,
        satBoost: Float = 0.35f,
        lightBoost: Float = 0.08f
    ): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 掩码做 3x3 羽化，避免头发边缘出现硬锯齿
        val alpha = feather(mask, w, h)

        // 标准色相旋转矩阵（绕 RGB 灰轴旋转）
        val rad = Math.toRadians(hueShift.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val m0 = 0.213f + c * 0.787f - s * 0.213f
        val m1 = 0.715f - c * 0.715f - s * 0.715f
        val m2 = 0.072f - c * 0.072f + s * 0.928f
        val m3 = 0.213f - c * 0.213f + s * 0.143f
        val m4 = 0.715f + c * 0.285f + s * 0.140f
        val m5 = 0.072f - c * 0.072f - s * 0.283f
        val m6 = 0.213f - c * 0.213f - s * 0.787f
        val m7 = 0.715f - c * 0.715f + s * 0.715f
        val m8 = 0.072f + c * 0.928f + s * 0.072f

        val sat = 1f + satBoost
        val light = 1f + lightBoost

        val out = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xff
            val or0 = ((p ushr 16) and 0xff) / 255f
            val og0 = ((p ushr 8) and 0xff) / 255f
            val ob0 = (p and 0xff) / 255f

            // 色相旋转
            var r = (m0 * or0 + m1 * og0 + m2 * ob0).coerceIn(0f, 1f)
            var g = (m3 * or0 + m4 * og0 + m5 * ob0).coerceIn(0f, 1f)
            var b = (m6 * or0 + m7 * og0 + m8 * ob0).coerceIn(0f, 1f)

            // 饱和度
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            r = (gray + (r - gray) * sat).coerceIn(0f, 1f)
            g = (gray + (g - gray) * sat).coerceIn(0f, 1f)
            b = (gray + (b - gray) * sat).coerceIn(0f, 1f)

            // 明度（乘法，单位是比例而不是 0-255 平移量）
            r = (r * light).coerceIn(0f, 1f)
            g = (g * light).coerceIn(0f, 1f)
            b = (b * light).coerceIn(0f, 1f)

            // 按掩码混合：mask=0 保持原图
            val m = if (alpha == null) 1f else alpha[i]
            val fr = or0 + (r - or0) * m
            val fg = og0 + (g - og0) * m
            val fb = ob0 + (b - ob0) * m

            out[i] = (a shl 24) or
                    ((fr * 255f).toInt().coerceIn(0, 255) shl 16) or
                    ((fg * 255f).toInt().coerceIn(0, 255) shl 8) or
                    ((fb * 255f).toInt().coerceIn(0, 255))
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 3x3 均值羽化，返回 0~1 的权重；mask 为空时返回 null（表示整图）。 */
    private fun feather(mask: ByteArray?, w: Int, h: Int): FloatArray? {
        if (mask == null || mask.size != w * h) return null
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var n = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        if (mask[yy * w + xx].toInt() != 0) sum++
                        n++
                    }
                }
                out[y * w + x] = sum.toFloat() / n
            }
        }
        return out
    }
}
