package com.example.magicbeauty.engine

/**
 * 滤镜预设。
 *
 * ## 设计演进
 * 早期版本每个滤镜是「饱和度 + 通道增益 + 提黑」拼出来的 3x3 色彩矩阵，
 * 表达能力上限很低 —— 做不出分离色调（阴影偏蓝、高光偏橙）、
 * 做不出交叉通道变换、做不出胶片曲线，所以用户反馈"滤镜不好看"。
 *
 * 现在改为 **[LutStyle]（3D LUT 风格）** 体系：
 * 每个预设描述一次颜色变换，烘焙成 33x33x33 的 3D LUT，
 * 在 GPU 上用 2D strip 采样（GLES2 没有 3D 纹理）。
 *
 * 详见 [Lut] 与 [LutStyle]。
 */
object Filters {

    data class Preset(
        val name: String,
        val style: LutStyle,
        val group: String
    )

    /** 组名 -> 组内 preset 的全局索引（顺序即 UI 顺序） */
    data class Group(val name: String, val indices: List<Int>)

    /** 顺序即 filterId；UI 按 groups 分组展示 */
    val presets: List<Preset> = listOf(
        // —— 清透 ——
        Preset("原图", LutStyle("原图", "清透"), "清透"),
        Preset("清透", LutStyle(
            "清透", "清透",
            satBoost = 1.08f, exposure = 1.03f, curve = 0.15f,
            splitShadow = floatArrayOf(0.0f, 0.0f, 0.03f),
            splitHighlight = floatArrayOf(0.02f, 0.01f, 0.0f)
        ), "清透"),
        Preset("净透", LutStyle(
            "净透", "清透",
            satBoost = 1.14f, exposure = 1.06f, curve = 0.10f,
            splitShadow = floatArrayOf(-0.01f, 0.0f, 0.04f),
            splitHighlight = floatArrayOf(0.02f, 0.01f, -0.01f),
            fade = 0.06f
        ), "清透"),
        Preset("晨雾", LutStyle(
            "晨雾", "清透",
            satBoost = 0.90f, exposure = 1.05f, curve = 0.20f,
            splitShadow = floatArrayOf(0.02f, 0.01f, 0.03f),
            splitHighlight = floatArrayOf(0.04f, 0.03f, 0.0f),
            fade = 0.14f, contrast = 0.94f
        ), "清透"),
        Preset("空气感", LutStyle(
            "空气感", "清透",
            satBoost = 0.96f, exposure = 1.07f, curve = 0.18f,
            splitShadow = floatArrayOf(0.0f, 0.01f, 0.04f),
            splitHighlight = floatArrayOf(0.03f, 0.02f, 0.0f),
            fade = 0.10f
        ), "清透"),

        // —— 暖阳 ——
        Preset("暖阳", LutStyle(
            "暖阳", "暖阳",
            satBoost = 1.06f, exposure = 1.04f, curve = 0.18f,
            splitShadow = floatArrayOf(0.04f, 0.01f, -0.03f),
            splitHighlight = floatArrayOf(0.05f, 0.02f, -0.04f),
            contrast = 1.03f
        ), "暖阳"),
        Preset("日落", LutStyle(
            "日落", "暖阳",
            satBoost = 1.14f, exposure = 1.05f, curve = 0.22f,
            splitShadow = floatArrayOf(0.05f, 0.0f, -0.04f),
            splitHighlight = floatArrayOf(0.09f, 0.03f, -0.07f),
            contrast = 1.05f
        ), "暖阳"),
        Preset("焦糖", LutStyle(
            "焦糖", "暖阳",
            satBoost = 1.08f, exposure = 1.03f, curve = 0.30f,
            splitShadow = floatArrayOf(0.05f, 0.01f, -0.04f),
            splitHighlight = floatArrayOf(0.07f, 0.03f, -0.05f),
            fade = 0.08f, contrast = 1.02f
        ), "暖阳"),
        Preset("秋日", LutStyle(
            "秋日", "暖阳",
            satBoost = 1.02f, exposure = 1.02f, curve = 0.24f,
            splitShadow = floatArrayOf(0.03f, 0.0f, -0.02f),
            splitHighlight = floatArrayOf(0.06f, 0.02f, -0.05f),
            fade = 0.10f
        ), "暖阳"),

        // —— 冷调 ——
        Preset("冷调", LutStyle(
            "冷调", "冷调",
            satBoost = 0.98f, exposure = 1.02f, curve = 0.20f,
            splitShadow = floatArrayOf(-0.03f, 0.0f, 0.05f),
            splitHighlight = floatArrayOf(-0.01f, 0.0f, 0.04f),
            contrast = 1.04f
        ), "冷调"),
        Preset("青柠", LutStyle(
            "青柠", "冷调",
            satBoost = 1.04f, exposure = 1.03f, curve = 0.16f,
            splitShadow = floatArrayOf(-0.02f, 0.02f, 0.03f),
            splitHighlight = floatArrayOf(0.0f, 0.02f, 0.02f)
        ), "冷调"),
        Preset("海盐", LutStyle(
            "海盐", "冷调",
            satBoost = 0.90f, exposure = 1.06f, curve = 0.15f,
            splitShadow = floatArrayOf(-0.04f, 0.0f, 0.06f),
            splitHighlight = floatArrayOf(-0.01f, 0.0f, 0.05f),
            fade = 0.12f
        ), "冷调"),
        Preset("极地", LutStyle(
            "极地", "冷调",
            satBoost = 0.86f, exposure = 1.08f, curve = 0.24f,
            splitShadow = floatArrayOf(-0.05f, -0.01f, 0.08f),
            splitHighlight = floatArrayOf(-0.02f, 0.0f, 0.06f),
            fade = 0.08f, contrast = 1.06f
        ), "冷调"),

        // —— 复古 ——
        Preset("复古", LutStyle(
            "复古", "复古",
            satBoost = 0.78f, exposure = 1.0f, curve = 0.26f,
            splitShadow = floatArrayOf(0.03f, 0.02f, -0.01f),
            splitHighlight = floatArrayOf(0.06f, 0.03f, -0.03f),
            fade = 0.16f, contrast = 0.96f
        ), "复古"),
        Preset("港风", LutStyle(
            "港风", "复古",
            satBoost = 0.88f, exposure = 1.0f, curve = 0.30f,
            splitShadow = floatArrayOf(0.02f, 0.01f, 0.02f),
            splitHighlight = floatArrayOf(0.05f, 0.01f, -0.04f),
            fade = 0.12f, contrast = 1.05f
        ), "复古"),
        Preset("昭和", LutStyle(
            "昭和", "复古",
            satBoost = 0.82f, exposure = 0.99f, curve = 0.22f,
            splitShadow = floatArrayOf(0.04f, 0.03f, 0.0f),
            splitHighlight = floatArrayOf(0.07f, 0.04f, -0.02f),
            fade = 0.20f, contrast = 0.97f
        ), "复古"),
        Preset("旧报", LutStyle(
            "旧报", "复古",
            satBoost = 0.68f, exposure = 1.0f, curve = 0.18f,
            splitShadow = floatArrayOf(0.05f, 0.04f, 0.02f),
            splitHighlight = floatArrayOf(0.08f, 0.06f, 0.03f),
            fade = 0.24f, contrast = 1.02f
        ), "复古"),

        // —— 黑白 ——
        Preset("黑白", LutStyle(
            "黑白", "黑白",
            satBoost = 0f, exposure = 1.02f, curve = 0.30f, contrast = 1.06f
        ), "黑白"),
        Preset("银盐", LutStyle(
            "银盐", "黑白",
            satBoost = 0f, exposure = 1.04f, curve = 0.36f,
            fade = 0.06f, contrast = 1.10f
        ), "黑白"),
        Preset("反差", LutStyle(
            "反差", "黑白",
            satBoost = 0f, exposure = 1.0f, curve = 0.45f, contrast = 1.24f
        ), "黑白"),
        Preset("黑白复古", LutStyle(
            "黑白复古", "黑白",
            satBoost = 0f, exposure = 1.02f, curve = 0.28f,
            splitShadow = floatArrayOf(0.04f, 0.03f, 0.0f),
            splitHighlight = floatArrayOf(0.07f, 0.05f, 0.0f),
            fade = 0.14f, contrast = 1.08f
        ), "黑白"),

        // —— 胶片 ——
        Preset("胶片", LutStyle(
            "胶片", "胶片",
            satBoost = 0.90f, exposure = 1.02f, curve = 0.34f,
            splitShadow = floatArrayOf(0.02f, 0.01f, 0.03f),
            splitHighlight = floatArrayOf(0.05f, 0.02f, -0.02f),
            fade = 0.10f
        ), "胶片"),
        Preset("柯达", LutStyle(
            "柯达", "胶片",
            satBoost = 1.04f, exposure = 1.03f, curve = 0.32f,
            splitShadow = floatArrayOf(0.03f, 0.01f, -0.02f),
            splitHighlight = floatArrayOf(0.07f, 0.03f, -0.05f),
            fade = 0.08f
        ), "胶片"),
        Preset("拍立得", LutStyle(
            "拍立得", "胶片",
            satBoost = 0.94f, exposure = 1.05f, curve = 0.28f,
            splitShadow = floatArrayOf(0.02f, 0.02f, 0.04f),
            splitHighlight = floatArrayOf(0.05f, 0.04f, 0.0f),
            fade = 0.18f, contrast = 0.95f
        ), "胶片"),
        Preset("烟灰", LutStyle(
            "烟灰", "胶片",
            satBoost = 0.80f, exposure = 1.0f, curve = 0.26f,
            splitShadow = floatArrayOf(0.0f, 0.0f, 0.02f),
            splitHighlight = floatArrayOf(0.02f, 0.01f, 0.0f),
            fade = 0.22f
        ), "胶片"),

        // —— 电影（分离色调的典型代表）——
        Preset("青橙", LutStyle(
            "青橙", "电影",
            satBoost = 1.10f, exposure = 1.02f, curve = 0.32f,
            splitShadow = floatArrayOf(-0.04f, 0.0f, 0.09f),
            splitHighlight = floatArrayOf(0.08f, 0.03f, -0.05f),
            splitBalance = 0.5f, splitAmount = 1.2f
        ), "电影"),
        Preset("冷暖对比", LutStyle(
            "冷暖对比", "电影",
            satBoost = 1.06f, exposure = 1.0f, curve = 0.30f,
            splitShadow = floatArrayOf(-0.03f, 0.0f, 0.07f),
            splitHighlight = floatArrayOf(0.07f, 0.02f, -0.04f),
            splitAmount = 1.1f
        ), "电影"),
        Preset("赛博", LutStyle(
            "赛博", "电影",
            satBoost = 1.18f, exposure = 0.98f, curve = 0.34f,
            splitShadow = floatArrayOf(-0.05f, -0.01f, 0.10f),
            splitHighlight = floatArrayOf(0.10f, 0.0f, -0.06f),
            splitAmount = 1.3f, contrast = 1.06f
        ), "电影"),
        Preset("暗夜", LutStyle(
            "暗夜", "电影",
            satBoost = 0.94f, exposure = 0.92f, curve = 0.28f,
            splitShadow = floatArrayOf(-0.04f, -0.01f, 0.08f),
            splitHighlight = floatArrayOf(0.03f, 0.02f, 0.01f),
            fade = 0.10f, contrast = 1.08f
        ), "电影")
    )

    /** 组 -> 全局索引列表（顺序即 UI 顺序） */
    val groups: List<Group> = presets
        .withIndex()
        .groupBy { it.value.group }
        .map { (name, items) -> Group(name, items.map { it.index }) }

    fun get(id: Int): Preset = presets.getOrElse(id) { presets[0] }

    /** 全局索引 -> 所在组名 */
    fun groupOf(id: Int): String = get(id).group

    /**
     * LUT 缓存：每个滤镜的 3D LUT 只烘焙一次（33^3 = 35937 个采样点，
     * 每个点要做几十次浮点运算，不缓存的话每次切滤镜都要卡一下）。
     */
    private val lutCache = HashMap<Int, ByteArray>()

    /** 取（并缓存）某个滤镜的 3D LUT strip 数据 */
    fun lutFor(id: Int): ByteArray = lutCache.getOrPut(id) {
        get(id).style.bake()
    }
}
