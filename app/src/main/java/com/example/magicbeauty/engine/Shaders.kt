package com.example.magicbeauty.engine

// 所有 GLSL 着色器（GL ES 2.0 / WebGL1 语法）集中在此。
// 设计：两段式管线
//   ① Warp 段：根据人脸/人体关键点做 UV 形变（瘦脸、大眼、瘦腰、拉腿）
//   ② Adjust 段：调色（亮度/对比/饱和/冷暖）+ 磨皮 + 暗角
//
// 形变算法说明（参考商业美颜 SDK 的通用做法）：
//   · 所有形变都是「逆映射」：对输出像素求它在原图中的采样点，天然不留空洞。
//   · 权重必须连续衰减到 0（用 smoothstep / exp），否则会出现硬边和“液化感”。
//   · 瘦脸只在「下颌环带」内收，五官中心不动；拉腿采用分段映射，
//     脚踝以下原样平移，不参与拉伸 —— 这样鞋和脚不会被拉长。

object Shaders {

    // 顶点：全屏四边形
    const val VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aUV;
        varying vec2 vUV;
        void main() {
            vUV = aUV;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """

    // ---- ① 形变段 ----
    const val WARP_FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;

        // uv 是按宽、高各自归一化的，直接算距离会被画面比例拉扁；
        // 统一乘上宽高比，换算成「以图片高度为单位」的等比坐标再算权重。
        uniform float uAspect;

        uniform vec2  uFaceCenter;   // 脸中心（GL 坐标，y 向上）
        uniform float uFaceHalfW;    // 半脸宽（按图片宽度归一化）
        uniform float uFaceHalfH;    // 半脸高（按图片高度归一化）
        uniform float uThinFace;     // 0..1 瘦脸强度

        uniform vec2  uEyeL;
        uniform vec2  uEyeR;
        uniform float uEyeRadius;    // 以图片高度为单位
        uniform float uEyeEnlarge;   // 0..0.35

        uniform vec2  uBodyCenter;   // 身体中线（腰部位置）
        uniform float uBodyHalfW;    // 半腰宽（按图片宽度归一化）
        uniform float uWaistY;       // 腰线
        uniform float uWaistSlim;    // 0..1 瘦腰
        uniform float uHipY;         // 胯（腿段上界）
        uniform float uAnkleY;       // 脚踝（腿段下界）
        uniform float uLegStretch;   // 0..0.18 拉腿

        // 径向放大：权重 t^2 在边界处恰好为 0，所以不会出现圆形接缝
        vec2 magnify(vec2 uv, vec2 c, float r, float k, float aspect) {
            vec2 d = vec2((uv.x - c.x) * aspect, uv.y - c.y);
            float dist = length(d);
            if (dist < r && r > 0.0001) {
                float t = 1.0 - dist / r;
                d *= (1.0 - k * t * t);
                uv = vec2(c.x + d.x / aspect, c.y + d.y);
            }
            return uv;
        }

        void main() {
            vec2 uv = vUV;

            // ---------- ① 拉腿：分段映射 ----------
            // 脚踝以下（脚、鞋）保持原样，只把「胯 → 脚踝」这一段按比例拉长，
            // 拉长的部分由胯以上整体上移吸收。所以不会有“鞋不动、脚被拉长”。
            if (uLegStretch > 0.001) {
                float L = max(uHipY - uAnkleY, 0.02);
                float delta = min(uLegStretch * L, 0.08);   // 上移量最多占画面高 8%，避免裁掉头顶
                float s = 1.0 + delta / L;
                float hipOut = uHipY + delta;
                float y = uv.y;
                if (y >= hipOut) {
                    y -= delta;
                } else if (y > uAnkleY) {
                    y = uAnkleY + (y - uAnkleY) / s;
                }
                uv.y = y;
            }

            // ---------- ② 瘦腰：平滑权重，无硬边 ----------
            if (uWaistSlim > 0.001) {
                float hw = max(uBodyHalfW * uAspect, 0.02);
                float dx = (uv.x - uBodyCenter.x) * uAspect / hw;
                float dy = (uv.y - uWaistY) / (hw * 0.85);
                float w = exp(-dx * dx * 0.65) * exp(-dy * dy);
                uv.x = uBodyCenter.x + (uv.x - uBodyCenter.x) * (1.0 + uWaistSlim * 0.18 * w);
            }

            // ---------- ③ 瘦脸：只收下颌轮廓，五官不动 ----------
            if (uThinFace > 0.001) {
                float hw = max(uFaceHalfW * uAspect, 0.02);
                float hh = max(uFaceHalfH, 0.02);
                vec2 q = vec2((uv.x - uFaceCenter.x) * uAspect / hw,
                              (uv.y - uFaceCenter.y) / hh);
                float r = length(q);
                // 环带：脸中心（眼鼻口，r<0.45）权重为 0；轮廓 r≈1 处最强；r>1.5 完全不受影响
                float ring = (1.0 - smoothstep(0.85, 1.5, r)) * smoothstep(0.45, 0.9, r);
                // 只作用于下半脸（下颌线），额头太阳穴保持原样
                float lower = 1.0 - smoothstep(-0.1, 0.55, q.y);
                float w = ring * lower;
                uv.x = uFaceCenter.x + (uv.x - uFaceCenter.x) * (1.0 + uThinFace * 0.20 * w);
            }

            // ---------- ④ 大眼 ----------
            if (uEyeEnlarge > 0.001) {
                uv = magnify(uv, uEyeL, uEyeRadius, uEyeEnlarge, uAspect);
                uv = magnify(uv, uEyeR, uEyeRadius, uEyeEnlarge, uAspect);
            }

            uv = clamp(uv, 0.0, 1.0);
            gl_FragColor = texture2D(uTex, uv);
        }
    """

    // ---- ② 调色 + 磨皮段 ----
    const val ADJUST_FRAG = """
        precision mediump float;
        varying vec2 vUV;
        uniform sampler2D uTex;
        uniform vec2  uTexel;        // 1.0/分辨率
        uniform float uBrightness;   // -0.3..0.3
        uniform float uContrast;     // 0.7..1.4
        uniform float uSaturation;   // 0..1.6
        uniform float uWarmth;       // -1..1
        uniform float uBlur;         // 0..1 磨皮强度
        uniform float uVignette;     // 0..1 暗角

        float luma(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }

        void main() {
            vec2 uv = vUV;
            vec3 col = texture2D(uTex, uv).rgb;

            // 5 点模糊近似磨皮
            vec3 b = texture2D(uTex, uv + vec2(uTexel.x, 0.0)).rgb
                   + texture2D(uTex, uv - vec2(uTexel.x, 0.0)).rgb
                   + texture2D(uTex, uv + vec2(0.0, uTexel.y)).rgb
                   + texture2D(uTex, uv - vec2(0.0, uTexel.y)).rgb
                   + col;
            b /= 5.0;
            float mask = clamp(1.0 - abs(luma(col) - 0.6) * 1.6, 0.0, 1.0); // 偏中调(肤色)
            col = mix(col, b, mask * uBlur);

            // 亮度 / 对比度
            col += uBrightness;
            col = (col - 0.5) * uContrast + 0.5;

            // 饱和度
            float l = luma(col);
            col = mix(vec3(l), col, uSaturation);

            // 冷暖
            col.r += uWarmth * 0.05;
            col.b -= uWarmth * 0.05;

            // 暗角（smoothstep 必须 edge0 < edge1）
            if (uVignette > 0.001) {
                vec2 d = uv - 0.5;
                float v = 1.0 - smoothstep(0.2, 0.8, dot(d, d) * 2.0);
                col *= mix(1.0, v, uVignette);
            }

            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
