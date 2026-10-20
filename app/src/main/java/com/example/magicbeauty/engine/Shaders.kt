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

        // 人体蒙版：非背景=1，背景=0。所有形变最后按它混合，背景保持完全不动
        uniform sampler2D uMask;
        uniform float uHasMask;
        uniform vec2  uMaskTexel;   // 羽化用的偏移

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
        uniform float uHipY;         // 腿段上界（裙子=裙摆下沿 / 长裤=胯 / 兜底=膝盖）
        uniform float uLegHalfW;     // 拉腿的横向作用半径（裙子用裙摆半宽）
        uniform float uHemLift;      // 裙摆两侧比中间高多少（大摆裙用）
        uniform float uAnkleY;       // 脚踝（腿段下界）
        uniform float uLegStretch;   // 0..0.18 拉腿

        // 人体权重（5 点平均做羽化，避免蒙版边缘出现硬切）
        float personWeight(vec2 uv) {
            if (uHasMask < 0.5) return 1.0;
            // 蒙版在 CPU 侧已经做过膨胀+模糊，这里只做一次轻微平滑，保证边缘平滑过渡
            float s = texture2D(uMask, uv).r;
            s += texture2D(uMask, uv + vec2(uMaskTexel.x, 0.0)).r;
            s += texture2D(uMask, uv - vec2(uMaskTexel.x, 0.0)).r;
            s += texture2D(uMask, uv + vec2(0.0, uMaskTexel.y)).r;
            s += texture2D(uMask, uv - vec2(0.0, uMaskTexel.y)).r;
            return clamp(s * 0.2, 0.0, 1.0);
        }

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
            // 横向再做一层「只在人体附近生效」的权重，否则门框、墙角这类竖线会被一起拉弯。
            if (uLegStretch > 0.001) {
                // 该列的腿段上界：中间用裙摆中线，靠两侧时按 uHemLift 抬高，
                // 这样 A 字裙/大摆裙两侧更高也不会被当成“腿”多拉一截。
                float dxn = abs(uv.x - uBodyCenter.x) / max(uLegHalfW, 0.02);
                float top = uHipY + uHemLift * smoothstep(0.25, 1.10, dxn);
                float L = max(top - uAnkleY, 0.02);

                // 关键：按「目标增高量」算，不是按腿段长度的百分比。
                // 旧写法 delta = uLegStretch * L，腿段越短位移越小 —— 洛丽塔长裙只露
                // 一截小腿时，位移不到 1% 画面高，看起来就是“拉到 100 也没反应”。
                // 现在先定目标增高（最多 11% 画面高），再换算成这一段的拉伸倍率，
                // 腿段短就多拉一点、腿段长就少拉一点，视觉增高量一致。
                float want  = uLegStretch * 0.11;
                float s     = clamp(1.0 + want / L, 1.0, 1.42);   // 单段最多拉长 42%
                float delta = (s - 1.0) * L;

                float hipOut = top + delta;
                float y = uv.y;
                if (y >= hipOut) {
                    y -= delta;
                } else if (y > uAnkleY) {
                    // 脚踝上方留一段过渡带：倍率从 1 平滑过渡到 s，
                    // 否则脚踝那一行导数突变，会看到一道折痕。
                    float soft = max(L * 0.14, 0.012);
                    float d = y - uAnkleY;
                    float k = clamp(d / soft, 0.0, 1.0);
                    k = k * k * (3.0 - 2.0 * k);
                    y = uAnkleY + d * mix(1.0, 1.0 / s, k);
                }
                // 作用范围交给人体蒙版统一控制（最后一步 mix），这里不再叠加横向权重，
                // 否则“蒙版 + 横向权重”双重衰减会让腿部边缘出现台阶、幅度也被削掉。
                uv.y = y;
            }

            // ---------- ② 瘦腰：平滑权重，无硬边 ----------
            if (uWaistSlim > 0.001) {
                float hw = max(uBodyHalfW * uAspect, 0.02);
                float dx = (uv.x - uBodyCenter.x) / max(uBodyHalfW, 0.02);
                // 垂直方向按画面高度给固定带宽（±7%），不要跟着腰宽缩：
                // 腰宽窄的时候带宽会一起变窄，收腰只作用在一条细缝上，看着像没效果。
                float dy = (uv.y - uWaistY) / 0.07;
                // 权重形状：腰内（|dx|<0.75）满强度，到 1.55 倍半腰宽处归零。
                // 之前用 exp(-dx*dx*k)，最大值在中心、轮廓处只剩 13%，等于“腰中间不动、
                // 边上才动”，既不明显，又因为作用到轮廓外把墙/门框卷进来 → 放光变色。
                float ax = abs(dx);
                float w = (1.0 - smoothstep(0.75, 1.55, ax)) * exp(-dy * dy * 1.2);
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

            // 只在人体范围内生效：背景像素直接沿用原始坐标，一点不动
            // （半条腿露出来、被裙摆遮住一部分的情况，也会按实际像素分别处理）
            uv = clamp(uv, 0.0, 1.0);
            // 两个位置都要在人体内才给满权重：
            // 只看输出位置的话，收缩/拉伸后的采样点可能已经落到背景上（比如白墙），
            // 会把它抓进人体边缘，表现就是“腰侧一圈发亮、颜色发虚”。
            float pw = min(personWeight(vUV), personWeight(uv));
            uv = mix(vUV, uv, pw);

            gl_FragColor = texture2D(uTex, uv);
        }
    """

    // ---- ② 调色 + 磨皮 + 滤镜 ----
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

        uniform mat3  uFilterMat;    // 滤镜色彩矩阵（行主序传入）
        uniform vec3  uFilterOff;    // 滤镜偏移

        float luma(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }

        // 肤色权重：偏暖、不太暗、不过曝。用来避免把背景/头发一起糊掉
        float skinMask(vec3 c) {
            float y = luma(c);
            float chroma = c.r - c.b;
            float m1 = smoothstep(0.004, 0.055, chroma);
            float m2 = 1.0 - smoothstep(0.55, 0.86, y);
            float m3 = smoothstep(0.08, 0.20, y);
            return clamp(m1 * m2 * m3, 0.0, 1.0);
        }

        void main() {
            vec2 uv = vUV;
            vec3 c0 = texture2D(uTex, uv).rgb;
            vec3 col = c0;

            // ---------- 磨皮：边缘保持的双边滤波（8 邻域） ----------
            // 旧版是 5 点均值模糊，会把眼睛、发丝一起糊掉，看起来“假滑”。
            // 这里每个采样点按“与中心的颜色差”加权：颜色差越大权重越小，
            // 所以皮肤被磨平，五官/发丝边缘保留。
            if (uBlur > 0.001) {
                float r = 1.0 + uBlur * 2.2;
                float sigma = 90.0;
                vec3 acc = c0;
                float ws = 1.0;
                vec3 s;
                float w;

                s = texture2D(uTex, uv + vec2( r, 0.0) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2(-r, 0.0) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2(0.0,  r) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2(0.0, -r) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;

                float d = r * 0.7071;
                s = texture2D(uTex, uv + vec2( d,  d) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2(-d,  d) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2( d, -d) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;
                s = texture2D(uTex, uv + vec2(-d, -d) * uTexel).rgb;
                w = exp(-dot(s - c0, s - c0) * sigma); acc += s * w; ws += w;

                vec3 blur = acc / ws;
                // 皮肤区域全强度；非皮肤只给 25%，避免背景糊成一片
                float m = mix(0.25, 1.0, skinMask(c0));
                col = mix(c0, blur, uBlur * m);
            }

            // 亮度 / 对比度
            col += uBrightness;
            col = (col - 0.5) * uContrast + 0.5;

            // 饱和度
            float l = luma(col);
            col = mix(vec3(l), col, uSaturation);

            // 冷暖
            col.r += uWarmth * 0.05;
            col.b -= uWarmth * 0.05;

            // 滤镜（色彩矩阵 + 偏移）
            col = clamp(uFilterMat * col + uFilterOff, 0.0, 1.0);

            // 暗角（smoothstep 必须 edge0 < edge1）
            if (uVignette > 0.001) {
                vec2 d2 = uv - 0.5;
                float v = 1.0 - smoothstep(0.2, 0.8, dot(d2, d2) * 2.0);
                col *= mix(1.0, v, uVignette);
            }

            gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """
}
