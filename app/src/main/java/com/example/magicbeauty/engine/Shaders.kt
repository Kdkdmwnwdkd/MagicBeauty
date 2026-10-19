package com.example.magicbeauty.engine

// 所有 GLSL 着色器（GL ES 2.0 / WebGL1 语法）集中在此。
// 设计：两段式管线
//   ① Warp 段：根据人脸/人体关键点做 UV 形变（瘦脸、大眼、瘦腰、拉腿/调身高）
//   ② Adjust 段：调色（亮度/对比度/饱和/冷暖）+ 磨皮 + 滤镜预设

object Shaders {

    // 顶点：全屏三角形/四边形
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

        uniform vec2  uFaceCenter;   // 眉心-下巴中点 (GL坐标, y向上)
        uniform float uFaceHalfH;    // 半脸高
        uniform float uThinFace;     // 0..1 瘦脸强度

        uniform vec2  uEyeL;         // 左眼中心
        uniform vec2  uEyeR;         // 右眼中心
        uniform float uEyeRadius;    // 眼睛作用半径
        uniform float uEyeEnlarge;   // 0..0.5 大眼强度

        uniform vec2  uBodyCenter;   // 身体中线 x, 腰部 y
        uniform float uBodyHalfW;    // 半身宽
        uniform float uWaistY;       // 腰线 y
        uniform float uWaistSlim;    // 0..1 瘦腰
        uniform float uHipY;         // 胯部 y (腿区上界)
        uniform float uAnkleY;       // 脚踝 y (腿区下界)
        uniform float uLegStretch;   // 0..0.3 拉腿/调身高

        vec2 magnify(vec2 uv, vec2 c, float r, float k) {
            vec2 d = uv - c;
            float dist = length(d);
            if (dist < r && r > 0.0001) {
                float t = dist / r;
                float f = (1.0 - k) + k * t * t; // 越靠中心越放大
                uv = c + d * f;
            }
            return uv;
        }

        void main() {
            vec2 uv = vUV;

            // —— 身体：瘦腰 ——
            if (uWaistSlim > 0.001) {
                float dyb = uv.y - uWaistY;
                float tb = dyb / 0.14;
                float band = exp(-tb * tb);
                float inside = step(abs(uv.x - uBodyCenter.x), uBodyHalfW);
                float dir = sign(uv.x - uBodyCenter.x);
                // 逆向采样：向“外侧”取样，使腰线向内收（此前写成 -= 会越调越宽）
                uv.x += dir * uWaistSlim * band * inside * 0.10;
            }

            // —— 身体：拉腿 / 调身高（把腿区采样点整体上移，腿显长）——
            if (uLegStretch > 0.001) {
                if (uv.y < uHipY && uv.y > uAnkleY) {
                    float span = max(uHipY - uAnkleY, 0.001);
                    float f = (uHipY - uv.y) / span;        // 0(胯)..1(踝)
                    uv.y = uv.y + uLegStretch * f * (uHipY - uAnkleY);
                }
            }

            // —— 人脸：瘦脸 ——
            if (uThinFace > 0.001) {
                float dy = uv.y - uFaceCenter.y;
                float ty = dy / (uFaceHalfH * 1.15);
                float band = exp(-ty * ty);
                float dir = sign(uv.x - uFaceCenter.x);
                float near = clamp(1.0 - abs(uv.x - uFaceCenter.x) * 2.2, 0.0, 1.0);
                // 同上：向外侧取样 => 脸颊向内收 => 显瘦（此前 -= 会显胖）
                uv.x += dir * uThinFace * band * near * 0.11;
            }

            // —— 人脸：大眼 ——
            if (uEyeEnlarge > 0.001) {
                uv = magnify(uv, uEyeL, uEyeRadius, uEyeEnlarge);
                uv = magnify(uv, uEyeR, uEyeRadius, uEyeEnlarge);
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

            // 暗角
            if (uVignette > 0.001) {
                vec2 d = uv - 0.5;
                float v = smoothstep(0.8, 0.2, dot(d, d) * 2.0);
                col *= mix(1.0, v, uVignette);
            }

            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
