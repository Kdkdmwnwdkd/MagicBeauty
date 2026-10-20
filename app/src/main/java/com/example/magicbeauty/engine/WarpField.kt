package com.example.magicbeauty.engine

import kotlin.math.abs
import kotlin.math.exp

/**
 * 位移场（Displacement Field）—— 拉腿 / 瘦腰的统一实现。
 *
 * 商用美颜 SDK 的塑形不是“逐像素套公式”，而是：
 *   1. 在人体上建一层网格；
 *   2. 移动网格顶点（骨骼驱动）；
 *   3. 把顶点位移插值成一张位移贴图；
 *   4. 渲染时按贴图偏移 UV。
 *
 * 这样做的好处（也是我们之前一直踩坑的地方）：
 *   · 位移由网格顶点决定，双线性插值天然平滑，不会出现台阶和硬边；
 *   · 背景格点位移恒为 0 → 背景真的一点不动，不需要把画面拆成前后两层；
 *   · “裙摆固定、脚踝固定、头不受影响”这类约束直接写在顶点上，不受公式限制；
 *   · 腿段很短（长裙只露小腿）时也能给出和腿段长时一致的增高量。
 *
 * 位移以归一化 UV 为单位（1.0 = 整幅画面宽/高），编码进 RGBA 纹理时
 * 先除以 [ENCODE_SCALE] 再加 0.5，8bit 精度下误差约 0.001（约 1 像素/1000）。
 */
object WarpField {

    /** 网格分辨率。96×192 在 1080p 上约每格 11×6 像素，足够表现腿部的大范围位移 */
    const val W = 96
    const val H = 192

    /** 位移编码范围：实际位移不会超过 ±0.25，用它把 8bit 精度放大 4 倍 */
    const val ENCODE_SCALE = 0.25f

    /**
     * 生成位移场。返回 W*H*2 的数组：[du0, dv0, du1, dv1, ...]
     * 顺序为逐行（v 从 0 到 1，即从画面底部到顶部）。
     */
    fun build(guide: WarpGuide, params: EditParams, aspect: Float): FloatArray {
        val out = FloatArray(W * H * 2)

        val legOn = params.legStretch > 0.001f && guide.legOk
        val waistOn = params.waistSlim > 0.001f && guide.waistOk
        // 塑形四件套：瘦肩/瘦手臂/瘦臀/瘦腿
        val shoulderOn = params.shoulderSlim > 0.001f && guide.shoulderOk
        val armOn = params.armSlim > 0.001f && guide.armOk
        val hipOn = params.hipSlim > 0.001f && guide.hipOk
        val legSlimOn = params.legSlim > 0.001f && guide.legSlimOk
        if (!legOn && !waistOn && !shoulderOn && !armOn && !hipOn && !legSlimOn) return out

        // ---- 拉腿参数 ----
        val legTop = guide.legTopResolved
        val ankle = guide.ankleY
        val hemHalf = (guide.clothesHalfW.takeIf { it > 0.05f } ?: guide.bodyHalfW)
            .coerceAtLeast(0.05f)
        // 目标增高量（画面高比例），逐点再换算成该列的拉伸倍率：
        // 腿段短就多拉、长就少拉，视觉增高量一致。封顶 1.42。
        val want = params.legStretch * 0.11f

        // ---- 瘦腰参数 ----
        val cx = guide.bodyCenter.x
        val waistY = guide.waistY
        val halfW = guide.bodyHalfW.coerceAtLeast(0.04f)

        val mask = guide.personMask
        val mn = guide.maskSize
        val useMask = mask != null && mn > 0

        // 没有蒙版时（分割不可用），用横向窗口把作用范围收在身体附近，
        // 否则整幅画面都会跟着动 —— 这正是之前“背景跟着动”的原因之一。
        // 窗口不再外扩 1.5 倍（会把身后的墙圈进来），收到 0.9 倍；
        // 并且加上垂直窗口：只在人体框 [bodyBottomY, bodyTopY] 内有权重，
        // 头顶上方、脚底下方的背景位移恒 0。
        val lateralHalf = (guide.clothesHalfW.takeIf { it > 0.05f } ?: halfW) * 0.9f
        val vTop = guide.bodyTopY
        val vBot = guide.bodyBottomY
        val hasVWin = vTop > vBot + 0.05f

        var idx = 0
        for (j in 0 until H) {
            val v = (j + 0.5f) / H
            for (i in 0 until W) {
                val u = (i + 0.5f) / W

                // 人体权重：有蒙版按蒙版，没有就用横向+垂直双窗口
                var pw = if (useMask) {
                    val mx = (u * (mn - 1)).toInt().coerceIn(0, mn - 1)
                    val my = ((1f - v) * (mn - 1)).toInt().coerceIn(0, mn - 1)
                    (mask!![my * mn + mx].toInt() and 0xff) / 255f
                } else {
                    val dxn = abs(u - cx) / lateralHalf.coerceAtLeast(0.05f)
                    val wx = if (dxn >= 2.2f) 0f else exp(-Math.pow(dxn.toDouble(), 4.0)).toFloat()
                    if (hasVWin && wx > 0f) {
                        // 垂直方向：人体框外（头顶上方/脚底下方）快速归零，
                        // 那两片区域是纯背景，位移必须是 0。
                        val above = (v - vTop) / 0.04f
                        val below = (vBot - v) / 0.04f
                        val vy = when {
                            above > 0f -> exp(-(above * above * 3.0).toDouble()).toFloat()
                            below > 0f -> exp(-(below * below * 3.0).toDouble()).toFloat()
                            else -> 1f
                        }
                        wx * vy
                    } else wx
                }

                var du = 0f
                var dv = 0f

                if (pw > 0.002f) {
                    if (legOn) {
                        // A 字裙/大摆裙两侧裙摆更高：逐列抬高腿段上界，
                        // 否则两侧那一条会被当成“腿”多拉一截。
                        val dxn = abs(u - cx) / hemHalf
                        val top = legTop + guide.clothesEdgeLift * smoothstep(0.25f, 1.10f, dxn)
                        val L = (top - ankle).coerceAtLeast(0.02f)
                        val s = (1f + want / L).coerceIn(1f, 1.42f)
                        val delta = (s - 1f) * L
                        val hipOut = top + delta
                        val soft = (L * 0.14f).coerceAtLeast(0.012f)

                        // 逆映射：输出像素 v 去原图哪里采样，位移 = 采样点 - 原位。
                        dv = when {
                            v >= hipOut -> -delta
                            v > ankle -> {
                                val d = v - ankle
                                val k = smoothstep(0f, soft, d)
                                d * (1f / s - 1f) * k
                            }
                            else -> 0f
                        }
                    }
                    if (waistOn) {
                        val ax = abs(u - cx) / halfW
                        // 腰内满强度、到 1.55 倍半腰宽处归零：只收腰，不把墙卷进来
                        val wx = 1f - smoothstep(0.75f, 1.55f, ax)
                        // 垂直带收窄：原来 ±0.05 且衰减慢（dy=1 处还剩 30%），
                        // 竖构图下 ±8% 画面高都在收，胸到胯全在动，
                        // 用户看到的就是"瘦的位置不是腰"。改 ±0.028 快速归零。
                        val dy = (v - waistY) / 0.028f
                        val wy = exp(-(dy * dy * 2.6f).toDouble()).toFloat()
                        du = (u - cx) * params.waistSlim * 0.18f * wx * wy
                    }

                    // —— 瘦肩：肩线横向收缩。垂直带 ±3%（肩线有厚度，比瘦腰略宽），
                    //    横向到 1.5 倍半肩宽归零，只收肩不连云袖/墙。
                    if (shoulderOn) {
                        val dy = (v - guide.shoulderY) / 0.030f
                        val wy = exp(-(dy * dy * 2.6f).toDouble()).toFloat()
                        val ax = abs(u - cx) / guide.shoulderHalfW
                        val wx = 1f - smoothstep(0.85f, 1.50f, ax)
                        du += (u - cx) * params.shoulderSlim * 0.10f * wx * wy
                    }

                    // —— 瘦手臂：左右各一条竖直带（肩→腕），各自向自己的中心收。
                    //    两个中心可能很近（手臂贴身），此时两条带自然叠加，
                    //    效果就是连手臂带侧腰一起收，符合"瘦手臂"的直觉。
                    if (armOn) {
                        val armBands = floatArrayOf(guide.armCxL, guide.armCxR)
                        for (bi in 0 until 2) {
                            val acx = armBands[bi]
                            val ax = abs(u - acx) / guide.armHalfW
                            if (ax < 1.7f) {
                                val wx = 1f - smoothstep(0.70f, 1.70f, ax)
                                val above = (v - guide.armTopY) / 0.030f
                                val below = (guide.armBotY - v) / 0.030f
                                val wy = when {
                                    above > 0f -> exp(-(above * above * 3.0).toDouble()).toFloat()
                                    below > 0f -> exp(-(below * below * 3.0).toDouble()).toFloat()
                                    else -> 1f
                                }
                                du += (u - acx) * params.armSlim * 0.12f * wx * wy
                            }
                        }
                    }

                    // —— 瘦臀：胯线横向收缩，带 ±3.2%（胯有厚度）——
                    if (hipOn) {
                        val dy = (v - guide.hipY) / 0.032f
                        val wy = exp(-(dy * dy * 2.6f).toDouble()).toFloat()
                        val ax = abs(u - cx) / guide.hipHalfW
                        val wx = 1f - smoothstep(0.80f, 1.55f, ax)
                        du += (u - cx) * params.hipSlim * 0.12f * wx * wy
                    }

                    // —— 瘦腿：腿段（legTop→踝）整体横向收缩，靠胯强、靠踝弱
                    //    （越往下腿越细，收缩感递减，不会把脚踝收成锥子）。
                    if (legSlimOn) {
                        val top = legTop
                        if (v < top && v > ankle) {
                            val ax = abs(u - cx) / guide.legHalfW
                            val wx = 1f - smoothstep(0.85f, 1.60f, ax)
                            val t = ((top - v) / (top - ankle).coerceAtLeast(0.02f)).coerceIn(0f, 1f)
                            val wy = 0.45f + 0.55f * t
                            du += (u - cx) * params.legSlim * 0.10f * wx * wy
                        }
                    }
                }

                out[idx++] = du * pw
                out[idx++] = dv * pw
            }
        }
        return out
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
