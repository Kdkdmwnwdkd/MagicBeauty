package com.example.magicbeauty.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min

/**
 * 3D LUT（色彩查找表）体系。
 *
 * ## 为什么需要
 * 早期的滤镜只是「饱和度 + 通道增益 + 提黑」拼出来的 3x3 色彩矩阵，表达能力上限很低：
 * 做不出分离色调（阴影偏蓝、高光偏橙）、做不出交叉通道变换、也做不出胶片曲线。
 * 消费级修图 App 的「风格」几乎都是 **3D LUT + 强度混合** 实现的。
 *
 * ## 为什么用 2D strip 而不是 3D 纹理
 * OpenGL ES 2.0 **没有** `GL_TEXTURE_3D`（`OES_texture_3D` 是可选扩展，移动端覆盖率低）。
 * 所以主流做法是把 N 个 B 切片横向排成一张 2D 纹理（N*N 宽 x N 高），
 * B 轴插值靠「两次采样 + mix」手工完成，R/G 轴由硬件双线性完成 —— 即三线性插值。
 *
 * 参考：GPUImage 的 `GPUImageLookupFilter`（8x8 tile 排布）、
 *      NVIDIA GPU Gems 2 Chapter 24（3D LUT 采样修正公式）。
 *
 * ## 采样公式的精度要点
 * 硬件纹理采样默认从数据集的一端采到另一端，会在边缘引入非线性误差；
 * GPU Gems 指出对小尺寸 LUT（32x32x32）这个误差**视觉上显著**。
 * 因此必须用 `(N-1)/N * coord + 0.5/N` 做修正（见 [sample] 里的公式）。
 *
 * ## 色彩空间
 * LUT 在 **sRGB 域**应用（与消费级 App 一致，Core Image / Lightroom 各自的处理更复杂）。
 */
object Lut {

    /** LUT 边长（立方体分辨率）。33 是 .cube 格式的常用尺寸。 */
    const val SIZE = 33

    /** LUT 强度默认 0.85 —— Lightroom 等专业工作流建议 60%~85%，全强度会压掉中间调对比度。 */
    const val DEFAULT_STRENGTH = 0.85f

    /**
     * 生成一个 3D LUT 的 2D strip 布局。
     *
     * 布局（与 shader 里 `lutLookup` 的采样公式严格对应）：
     * - 纹理尺寸：宽 = SIZE*SIZE，高 = SIZE
     * - 纹理行 = G（行号 y 就是 G 的采样序号，y=0 在**底部**，与 GL 的 v 轴一致，上传时不翻转）
     * - 纹理列 = B 切片 * SIZE + R（第 t 个 B 切片占 x 区间 [t*SIZE, (t+1)*SIZE)，
     *   切片内 x 方向是 R 递增）
     * - 内存顺序：先 G 行，再 B 切片，再 R 列
     *   => 线性下标 = (g * SIZE + b) * SIZE + r
     *
     * 注意 R 的采样范围是 [0, SIZE-1]（SIZE 个采样点），
     * 硬件双线性在 R 方向的插值权重由 `r_float = c.r*(SIZE-1)` 给出，
     * 恰好落在切片内部，不会跨切片渗色（与 GPUImage 的 8x8 tile 做法同理）。
     *
     * @param fn 输入 (r,g,b) 各 0..1，返回变换后的 (r,g,b)，各 0..1
     * @return 长度为 SIZE*SIZE*SIZE*4 的 RGBA 字节
     */
    fun buildStrip(fn: (Float, Float, Float) -> FloatArray): ByteArray {
        val n = SIZE
        val out = ByteArray(n * n * n * 4)
        var p = 0
        // 内存顺序 = 纹理行优先：先 G 行，再 B 切片，再 R 列
        for (g in 0 until n) {
            val gv = g.toFloat() / (n - 1)
            for (b in 0 until n) {
                val bv = b.toFloat() / (n - 1)
                for (r in 0 until n) {
                    val rv = r.toFloat() / (n - 1)
                    val c = fn(rv, gv, bv)
                    out[p++] = to8(c[0])
                    out[p++] = to8(c[1])
                    out[p++] = to8(c[2])
                    out[p++] = 255.toByte()
                }
            }
        }
        return out
    }

    private fun to8(v: Float): Byte {
        val x = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return x.toByte()
    }
}

/**
 * 滤镜风格定义。
 *
 * 每一项描述一次**颜色变换**（在 sRGB 域做），可以表达纯矩阵做不到的效果：
 * - [splitShadow] / [splitHighlight]：分离色调（阴影与高光染不同颜色）——胶片感的关键
 * - [curve]：胶片 S 曲线（提暗部、压高光、留中间调）
 * - [satBoost]：以 luma 为中心的饱和度调整（保住亮度关系）
 * - [channelMix]：交叉通道变换（3x3 矩阵的对称阵假设在这里被打破）
 * - [tintShadow] / [tintHighlight]：整体色调偏移
 */
data class LutStyle(
    val name: String,
    val group: String,
    /** 饱和度倍率 */
    val satBoost: Float = 1f,
    /** 分离色调：阴影染色 (r,g,b) 增量，-1..1 */
    val splitShadow: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** 分离色调：高光染色 (r,g,b) 增量，-1..1 */
    val splitHighlight: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** 分离色调的过渡位置（0=只有阴影染色，1=只有高光染色），0.5 = 中间过渡 */
    val splitBalance: Float = 0.5f,
    /** 分离色调强度（整体缩放） */
    val splitAmount: Float = 1f,
    /** 胶片 S 曲线强度（0=不改变，1=全量） */
    val curve: Float = 0f,
    /** 交叉通道混合：out.r = r*mix[0] + g*mix[1] + b*mix[2]，共 3x3 */
    val channelMix: FloatArray? = null,
    /** 整体色调偏移 (r,g,b) */
    val tint: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** 抬黑（褪色感）：0=不抬，1=抬到 0.12 */
    val fade: Float = 0f,
    /** 整体亮度缩放 */
    val exposure: Float = 1f,
    /** 对比度（绕 0.5） */
    val contrast: Float = 1f
) {
    /** 把这个风格烘焙成 3D LUT 的 strip 数据 */
    fun bake(): ByteArray = Lut.buildStrip { r, g, b -> apply(r, g, b) }

    private fun apply(r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0
        var g = g0
        var b = b0

        // 1) 曝光（乘性，最先做，因为后面的曲线/染色都作用在它之上）
        if (exposure != 1f) {
            r *= exposure; g *= exposure; b *= exposure
        }

        // 2) 交叉通道变换（矩阵不做对称假设）
        val mix = channelMix
        if (mix != null) {
            val nr = r * mix[0] + g * mix[1] + b * mix[2]
            val ng = r * mix[3] + g * mix[4] + b * mix[5]
            val nb = r * mix[6] + g * mix[7] + b * mix[8]
            r = nr; g = ng; b = nb
        }

        // 3) 饱和度：以 Rec.601 luma 为中心缩放，保住亮度关系
        if (satBoost != 1f) {
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            r = y + (r - y) * satBoost
            g = y + (g - y) * satBoost
            b = y + (b - y) * satBoost
        }

        // 4) 分离色调：按亮度在阴影/高光两色之间插值
        if (splitAmount > 0.001f &&
            (splitShadow.any { it != 0f } || splitHighlight.any { it != 0f })) {
            val y = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
            // 用 smoothstep 让过渡更自然
            val t = smoothstep(0f, 1f, (y - splitBalance) / 0.5f + 0.5f)
            r += ((1f - t) * splitShadow[0] + t * splitHighlight[0]) * splitAmount
            g += ((1f - t) * splitShadow[1] + t * splitHighlight[1]) * splitAmount
            b += ((1f - t) * splitShadow[2] + t * splitHighlight[2]) * splitAmount
        }

        // 5) 胶片 S 曲线：暗部压下去、高光压下来、中间调抬起
        if (curve > 0.001f) {
            r = r + (filmCurve(r) - r) * curve
            g = g + (filmCurve(g) - g) * curve
            b = b + (filmCurve(b) - b) * curve
        }

        // 6) 对比度（绕 0.5）
        if (contrast != 1f) {
            r = (r - 0.5f) * contrast + 0.5f
            g = (g - 0.5f) * contrast + 0.5f
            b = (b - 0.5f) * contrast + 0.5f
        }

        // 7) 抬黑（褪色）
        if (fade > 0.001f) {
            r += (0.12f - r) * fade
            g += (0.12f - g) * fade
            b += (0.12f - b) * fade
        }

        // 8) 整体色调偏移
        r += tint[0]; g += tint[1]; b += tint[2]

        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 经典胶片 S 曲线：x^2.2 提中间调 + 高光滚降 */
    private fun filmCurve(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        val gamma = v.toDouble().pow(0.85).toFloat()   // 提亮中间调
        val rolloff = 1f - (1f - v) * (1f - v) * 0.25f        // 高光轻微滚降
        return (gamma * 0.85f + rolloff * 0.15f).coerceIn(0f, 1f)
    }
}
