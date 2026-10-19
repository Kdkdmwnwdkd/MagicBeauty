package com.example.magicbeauty.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 端侧「染发/换发色」引擎（开源、纯本地、零成本、离线可用）。
 *
 * 说明：这不是「生成式」——它不会凭空造出飘逸发丝（那必须云端大模型）。
 * 它做的是对整图做色彩变换，模拟换发色/增亮发丝，作为「画发丝」功能在无网、
 * 无 Key、或想快速预览时的托底方案。若想要真正生成新发丝，请切到「云端生成」模式。
 *
 * @param hueShift  色相旋转角度（度）。例如 60=偏黄棕，120=偏绿，300=偏紫红。
 * @param satBoost  饱和度增益（0~1）。
 * @param lightBoost 亮度增益（0~0.3）。
 */
object LocalHairEngine {

    fun recolor(
        src: Bitmap,
        hueShift: Float = 50f,
        satBoost: Float = 0.35f,
        lightBoost: Float = 0.08f
    ): Bitmap {
        val config = src.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(src.width, src.height, config)

        // 色相旋转：分别构造绕 R/G/B 轴旋转矩阵并叠加
        val rm = ColorMatrix().apply { setRotate(0, hueShift) }
        val gm = ColorMatrix().apply { setRotate(1, hueShift) }
        val bm = ColorMatrix().apply { setRotate(2, hueShift) }
        val hue = ColorMatrix().apply { set(rm); postConcat(gm); postConcat(bm) }

        // 饱和度
        val sat = ColorMatrix().apply { setSaturation(1f + satBoost) }

        // 亮度
        val light = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, lightBoost,
            0f, 1f, 0f, 0f, lightBoost,
            0f, 0f, 1f, 0f, lightBoost,
            0f, 0f, 0f, 1f, 0f
        ))

        val cm = ColorMatrix()
        cm.set(hue)
        cm.postConcat(sat)
        cm.postConcat(light)

        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }
}
