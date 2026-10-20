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

        // 面部重塑：瘦鼻/缩唇/瘦下巴（中心与半宽由 face mesh 478 点推导）
        uniform vec2  uNose;
        uniform float uNoseHalfW;
        uniform float uNoseSlim;
        uniform vec2  uMouth;
        uniform float uMouthHalfW;
        uniform float uMouthSlim;
        uniform vec2  uChin;
        uniform float uChinHalfW;
        uniform float uChinSlim;

        // 拉腿 / 瘦腰：位移场（CPU 侧按人体网格算出，见 WarpField）
        uniform sampler2D uDisp;
        uniform float uDispScale;    // 解码比例（位移 = (纹素 - 0.5) * uDispScale）
        uniform float uHasDisp;

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

            // ---------- ⑤ 面部重塑：瘦鼻 / 缩唇 / 瘦下巴 ----------
            // 局部横向收缩：中心附近、半径内把 x 向中心收。权重 t^2 在边界处为 0，
            // 不会出现圆形接缝；只动一小块，五官其他部分不受影响。
            if (uNoseSlim > 0.001) {
                vec2 d = vec2((uv.x - uNose.x) * uAspect, uv.y - uNose.y);
                float dist = length(d);
                if (dist < uNoseHalfW) {
                    float t = 1.0 - dist / uNoseHalfW;
                    uv.x = uNose.x + (uv.x - uNose.x) * (1.0 - uNoseSlim * 0.22 * t * t);
                }
            }
            if (uMouthSlim > 0.001) {
                vec2 d = vec2((uv.x - uMouth.x) * uAspect, uv.y - uMouth.y);
                float dist = length(d);
                if (dist < uMouthHalfW) {
                    float t = 1.0 - dist / uMouthHalfW;
                    uv.x = uMouth.x + (uv.x - uMouth.x) * (1.0 - uMouthSlim * 0.20 * t * t);
                }
            }
            if (uChinSlim > 0.001) {
                // 下巴中心从颏部 152 略上移（覆盖下颌斜坡），半径取下巴半宽
                vec2 cc = vec2(uChin.x, uChin.y + uChinHalfW * 0.35);
                vec2 d = vec2((uv.x - cc.x) * uAspect, uv.y - cc.y);
                float dist = length(d);
                if (dist < uChinHalfW) {
                    float t = 1.0 - dist / uChinHalfW;
                    uv.x = cc.x + (uv.x - cc.x) * (1.0 - uChinSlim * 0.18 * t * t);
                }
            }

            // 只在人体范围内生效：背景像素直接沿用原始坐标，一点不动
            // （半条腿露出来、被裙摆遮住一部分的情况，也会按实际像素分别处理）
            uv = clamp(uv, 0.0, 1.0);
            // 两个位置都要在人体内才给满权重：
            // 只看输出位置的话，收缩/拉伸后的采样点可能已经落到背景上（比如白墙），
            // 会把它抓进人体边缘，表现就是“腰侧一圈发亮、颜色发虚”。
            float pw = min(personWeight(vUV), personWeight(uv));
            uv = mix(vUV, uv, pw);

            // ---------- ① 拉腿 / 瘦腰：位移场 ----------
            // 位移由 CPU 侧的人体网格算出（WarpField），这里只做一次取样 + 双线性插值。
            // 放在最后，是因为位移场里的量已经乘过人体权重；若和上面的 personWeight
            // 再乘一次，边缘会被二次衰减（之前“瘦腰不明显”的一个隐藏原因）。
            // 好处：
            //   · 背景格点位移恒为 0 → 背景完全不动，不靠权重硬压；
            //   · 网格插值天然平滑，不会出现台阶、折痕、光晕；
            //   · 裙摆/脚踝的约束写在顶点上，长裙短裙都能处理。
            if (uHasDisp > 0.5) {
                vec2 d = texture2D(uDisp, vUV).rg - 0.5;
                uv += d * uDispScale;
            }

            gl_FragColor = texture2D(uTex, clamp(uv, 0.0, 1.0));
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
        uniform float uWhiten;       // 0..1 美白强度（只提亮肤色区域）

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

            // ---------- 美白：只提亮肤色区域 ----------
            // 复用磨皮同款肤色判定（偏暖、不太暗、不过曝），所以背景/衣服/头发不动。
            // 提亮 + 轻微降饱和：只提亮会惨白，降一点饱和更接近"瓷白"的观感。
            if (uWhiten > 0.001) {
                float m = skinMask(c0);
                float w = uWhiten * m;
                col += w * 0.18;
                col = mix(col, vec3(luma(col)), w * 0.10);
            }

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
