package com.example.magicbeauty.ui

import com.example.magicbeauty.MainActivity
import com.example.magicbeauty.R
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.magicbeauty.databinding.FragmentPhotoBinding
import com.example.magicbeauty.engine.EditParams
import com.example.magicbeauty.engine.CollageBuilder
import com.example.magicbeauty.engine.Filters
import com.example.magicbeauty.engine.LocalEditEngine
import com.example.magicbeauty.engine.MediaPipeHelper
import com.example.magicbeauty.engine.MosaicSpec
import com.example.magicbeauty.engine.OverlaySpec
import com.example.magicbeauty.engine.StickerItem
import com.example.magicbeauty.engine.WarpGuide
import com.example.magicbeauty.util.BitmapUtils
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.PI

class PhotoEditorFragment : Fragment() {

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding!!

    /**
     * OpenGL 的 EGL 上下文是**线程绑定**的：创建在哪个线程，就只能在哪个线程用。
     * 这里固定一个单线程队列，所有 GL 调用都 post 到它上面，既避免换线程导致黑屏，
     * 也避免连续拖滑块时多个渲染并发抢同一个上下文。
     */
    private val glExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "mb-photo-gl") }
    private val glDispatcher = glExecutor.asCoroutineDispatcher()

    private val engine = LocalEditEngine()
    private lateinit var mp: MediaPipeHelper
    private val params = EditParams()

    private var sourceBitmap: Bitmap? = null
    private var guide: WarpGuide = WarpGuide()
    private var lastResult: Bitmap? = null
    private var renderJob: Job? = null

    private var rendering = false
    private var pendingRender = false
    private var renderErrorShown = false

    /** 底部面板当前分类 / 是否展开滑块（-1 表示还没选过） */
    private var currentTab = -1
    private var expanded = true

    /** 当前滤镜分组名 */
    private var filterGroup = Filters.groups.first().name

    // ---------- 工具/AI：叠加内容与当前工具 ----------

    /** 马赛克笔涂抹（与源图同尺寸的 mask） */
    private val mosaicSpec = MosaicSpec()
    /** 贴纸/文字层（引擎最多取前 4 层） */
    private val stickerItems = ArrayList<StickerItem>()
    /** 当前打开的工具面板：null=都没开 */
    private var activeTool: String? = null
    /** 塑形手动定位（null=用自动检测值） */
    private var manualHemY: Float? = null
    private var manualAnkleY: Float? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        loadImage(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentPhotoBinding.inflate(inflater, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 左上角「≡」打开侧边抽屉（放在各自页面里最稳，不依赖 Activity 反射查 View）
        binding.btnMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        mp = MediaPipeHelper(requireContext())

        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSave.setOnClickListener { saveCurrent() }

        // 底部工具面板：常驻显示，点标签切换分类，再点同一个标签收起/展开滑块
        binding.tabLight.setOnClickListener { selectTab(0) }
        binding.tabTexture.setOnClickListener { selectTab(1) }
        binding.tabFace.setOnClickListener { selectTab(2) }
        binding.tabShape.setOnClickListener { selectTab(3) }
        binding.tabTool.setOnClickListener { selectTab(4) }
        binding.tabAi.setOnClickListener { selectTab(5) }
        binding.sheetHandle.setOnClickListener { setExpanded(!expanded) }
        selectTab(0)

        // 面板高度变化（收起/展开/切换分类）时重算图片可用区域
        binding.toolSheet.addOnLayoutChangeListener { _, _, top, _, bottom, _, oTop, _, oBottom ->
            if (bottom - top != oBottom - oTop) updatePreviewInsets()
        }
        updatePreviewInsets()

        // 长按看原图，松手回到效果图（对比修改前后）
        binding.preview.setOnLongClickListener {
            sourceBitmap?.let { binding.preview.setImageBitmap(it) }
            true
        }
        binding.preview.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                lastResult?.let { binding.preview.setImageBitmap(it) }
            }
            false
        }

        binding.sbBrightness.setOnSeekBarChangeListener(slider { params.brightness = it / 100f * 0.6f - 0.3f })
        // 对比度/饱和度的中性点要落在滑块中点(50)，否则用户拖到"中间"时画面会莫名变灰/变淡
        binding.sbContrast.setOnSeekBarChangeListener(slider { params.contrast = 0.65f + it / 100f * 0.7f })
        binding.sbSaturation.setOnSeekBarChangeListener(slider { params.saturation = 0.4f + it / 100f * 1.2f })
        binding.sbWarmth.setOnSeekBarChangeListener(slider { params.warmth = it / 100f * 2f - 1f })
        binding.sbBlur.setOnSeekBarChangeListener(slider { params.blur = it / 100f })
        binding.sbWhiten.setOnSeekBarChangeListener(slider { params.whiten = it / 100f })
        binding.sbVignette.setOnSeekBarChangeListener(slider { params.vignette = it / 100f })

        // 滤镜：分组（一级）+ 滤镜（二级），全部代码构建
        buildFilterGroups()

        binding.sbThinFace.setOnSeekBarChangeListener(slider { params.thinFace = it / 100f })
        binding.sbEye.setOnSeekBarChangeListener(slider { params.eyeEnlarge = it / 100f * 0.35f })
        // 面部重塑：瘦鼻/缩唇/瘦下巴（face mesh 478 点定位，只动一小块）
        binding.sbNose.setOnSeekBarChangeListener(slider { params.noseSlim = it / 100f })
        binding.sbMouth.setOnSeekBarChangeListener(slider { params.mouthSlim = it / 100f })
        binding.sbChin.setOnSeekBarChangeListener(slider { params.chinSlim = it / 100f })
        binding.sbWaist.setOnSeekBarChangeListener(slider { params.waistSlim = it / 100f })
        binding.sbLeg.setOnSeekBarChangeListener(slider { params.legStretch = it / 100f * 0.25f })
        // 塑形手动定位：拖动裙摆/脚踝两条线，兜底检测不准的情况
        binding.btnGuideLines.setOnClickListener {
            val show = binding.guideLines.visibility != View.VISIBLE
            binding.guideLines.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                sourceBitmap?.let {
                    binding.guideLines.setImageSize(it.width, it.height)
                    binding.guideLines.initFrom(
                        manualHemY ?: guide.legTopResolved,
                        manualAnkleY ?: guide.ankleY
                    )
                }
                binding.tvHint.text = "拖动黄线设定拉腿区间：上=裙摆线，下=脚踝线；再点一次按钮关闭"
                binding.tvHint.visibility = View.VISIBLE
            } else {
                binding.tvHint.visibility = View.GONE
                updateDetectHint()
            }
        }
        binding.guideLines.onLineChanged = { hem, ankle ->
            manualHemY = hem
            manualAnkleY = ankle
            if (sourceBitmap != null) renderPreview()
        }
        // 塑形四件套：瘦肩/瘦手臂/瘦臀/瘦腿（位移场水平收缩）
        binding.sbShoulder.setOnSeekBarChangeListener(slider { params.shoulderSlim = it / 100f })
        binding.sbArm.setOnSeekBarChangeListener(slider { params.armSlim = it / 100f })
        binding.sbHip.setOnSeekBarChangeListener(slider { params.hipSlim = it / 100f })
        binding.sbLegSlim.setOnSeekBarChangeListener(slider { params.legSlim = it / 100f })

        // 调色进阶：高光/阴影（中性点在中点）
        binding.sbHighlight.setOnSeekBarChangeListener(slider { params.highlight = it / 100f * 2f - 1f })
        binding.sbShadow.setOnSeekBarChangeListener(slider { params.shadow = it / 100f * 2f - 1f })
        binding.sbSharpen.setOnSeekBarChangeListener(slider { params.sharpen = it / 100f })
        binding.sbGrain.setOnSeekBarChangeListener(slider { params.grain = it / 100f })

        // HSL 六色分频：先建色带圆点，再绑三个滑块
        buildHslBands()
        binding.sbHslHue.setOnSeekBarChangeListener(slider {
            params.hslHue[hslBand] = it / 100f * 1f - 0.5f
        })
        binding.sbHslSat.setOnSeekBarChangeListener(slider {
            params.hslSat[hslBand] = it / 100f * 2f
        })
        binding.sbHslLum.setOnSeekBarChangeListener(slider {
            params.hslLum[hslBand] = it / 100f * 0.6f - 0.3f
        })
        binding.btnHslReset.setOnClickListener {
            params.hslHue[hslBand] = 0f
            params.hslSat[hslBand] = 1f
            params.hslLum[hslBand] = 0f
            syncHslSliders()
            if (sourceBitmap != null) renderPreview()
        }

        // 曲线：弹窗里拖点，实时出 LUT
        binding.btnCurve.setOnClickListener { showCurveDialog() }

        // ---------- 工具 Tab ----------
        buildToolEntries()
        buildCropRatios()
        buildBorderColors()
        buildStickerGallery()
        buildCollageTemplates()
        wireToolPanels()

        // ---------- AI Tab（云端生成） ----------
        binding.btnAiErase.setOnClickListener { startAiErase() }
        binding.btnAiRestore.setOnClickListener { startAiRestore() }
        binding.btnAiExpand.setOnClickListener { startAiExpand() }
    }

    // ---------- 工具 Tab：入口与面板 ----------

    /** 工具入口（一行小按钮）；点同一个 = 收起面板 */
    private fun buildToolEntries() {
        val row = binding.toolRow
        row.removeAllViews()
        val entries = listOf(
            "crop" to "裁剪",
            "transform" to "旋转翻转",
            "border" to "边框",
            "mosaic" to "马赛克",
            "sticker" to "贴纸文字",
            "collage" to "拼图"
        )
        entries.forEach { (id, label) ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.tab_text, null))
                setBackgroundResource(R.drawable.bg_tab)
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, 0, p, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (30 * resources.displayMetrics.density).toInt()
                ).apply { marginStart = (6 * resources.displayMetrics.density).toInt() }
                setOnClickListener { toggleTool(id) }
            }
            row.addView(chip)
        }
    }

    private fun toggleTool(id: String) {
        if (activeTool == id) {
            closeTool()
            return
        }
        if (sourceBitmap == null) {
            toast("先选一张照片")
            return
        }
        activeTool = id
        val panels = mapOf(
            "crop" to binding.panelCrop,
            "transform" to binding.panelTransform,
            "border" to binding.panelBorder,
            "mosaic" to binding.panelMosaic,
            "sticker" to binding.panelSticker,
            "collage" to binding.panelCollage
        )
        panels.forEach { (k, v) -> v.visibility = if (k == id) View.VISIBLE else View.GONE }
        // 浮层显隐
        binding.cropOverlay.visibility = if (id == "crop") View.VISIBLE else View.GONE
        binding.maskPaint.visibility = if (id == "mosaic") View.VISIBLE else View.GONE
        binding.stickerLayer.visibility =
            if (id == "sticker") View.VISIBLE else View.GONE
        when (id) {
            "crop" -> {
                sourceBitmap?.let {
                    binding.cropOverlay.setImageSize(it.width, it.height)
                    binding.cropOverlay.reset()
                }
            }
            "mosaic" -> {
                sourceBitmap?.let { binding.maskPaint.setImageSize(it.width, it.height) }
            }
            "sticker" -> binding.stickerLayer.stickers = stickerItems
        }
        refreshToolEntries()
    }

    private fun closeTool() {
        activeTool = null
        binding.panelCrop.visibility = View.GONE
        binding.panelTransform.visibility = View.GONE
        binding.panelBorder.visibility = View.GONE
        binding.panelMosaic.visibility = View.GONE
        binding.panelSticker.visibility = View.GONE
        binding.panelCollage.visibility = View.GONE
        binding.cropOverlay.visibility = View.GONE
        binding.maskPaint.visibility = View.GONE
        // 贴纸层保持可见（不退出贴纸模式时仍能看到选中框）
        if (stickerItems.isEmpty()) binding.stickerLayer.visibility = View.GONE
        refreshToolEntries()
    }

    private fun refreshToolEntries() {
        val row = binding.toolRow
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i) as? TextView ?: continue
            val selected = when (i) {
                0 -> activeTool == "crop"
                1 -> activeTool == "transform"
                2 -> activeTool == "border"
                3 -> activeTool == "mosaic"
                4 -> activeTool == "sticker"
                5 -> activeTool == "collage"
                else -> false
            }
            c.isSelected = selected
            c.setTextColor(
                if (selected) 0xFF0E0F18.toInt() else resources.getColor(R.color.tab_text, null)
            )
        }
    }

    private fun wireToolPanels() {
        // 裁剪
        binding.btnCropReset.setOnClickListener { binding.cropOverlay.reset() }
        binding.btnCropApply.setOnClickListener { applyCrop() }

        // 旋转/翻转（提交式：替换源图 + 重算引导）
        binding.btnRotateLeft.setOnClickListener { applyTransform("rotL") }
        binding.btnRotateRight.setOnClickListener { applyTransform("rotR") }
        binding.btnFlipH.setOnClickListener { applyTransform("flipH") }
        binding.btnFlipV.setOnClickListener { applyTransform("flipV") }

        // 边框
        binding.sbBorder.setOnSeekBarChangeListener(slider {
            params.borderWidth = it / 100f * 0.06f
        })

        // 马赛克
        binding.sbMosaicBrush.setOnSeekBarChangeListener(slider {
            binding.maskPaint.brush = 0.02f + it / 100f * 0.18f
        })
        binding.btnMosaicErase.setOnClickListener {
            binding.maskPaint.erasing = !binding.maskPaint.erasing
            binding.btnMosaicErase.isSelected = binding.maskPaint.erasing
            binding.btnMosaicErase.text = if (binding.maskPaint.erasing) "橡皮" else "涂抹"
        }
        binding.btnMosaicUndo.setOnClickListener { binding.maskPaint.undo() }
        binding.btnMosaicClear.setOnClickListener { binding.maskPaint.clearMask() }
        binding.btnMosaicFull.setOnClickListener {
            mosaicSpec.brushOnly = !mosaicSpec.brushOnly
            binding.btnMosaicFull.isSelected = !mosaicSpec.brushOnly
            if (sourceBitmap != null) renderPreview()
        }
        binding.btnMosaicDone.setOnClickListener { closeTool() }
        binding.maskPaint.onMaskChanged = {
            mosaicSpec.mask = binding.maskPaint.mask
            if (sourceBitmap != null) renderPreview()
        }

        // 贴纸
        binding.btnAddText.setOnClickListener { showTextDialog() }
        binding.btnStickerClear.setOnClickListener {
            stickerItems.forEach { if (it.owned) it.bitmap.recycle() }
            stickerItems.clear()
            binding.stickerLayer.selected = -1
            if (stickerItems.isEmpty()) binding.stickerLayer.visibility = View.GONE
            if (sourceBitmap != null) renderPreview()
        }
        // 拼图
        binding.btnCollagePick.setOnClickListener { launchCollagePicker() }
        binding.stickerLayer.stickers = stickerItems
        binding.stickerLayer.onStickerChanged = { if (sourceBitmap != null) renderPreview() }
        binding.stickerLayer.onBackgroundTap = { binding.stickerLayer.selected = -1 }
        binding.stickerLayer.onStickerTap = { _, x, y ->
            val b = binding.stickerLayer.deleteButtonBounds()
            if (b != null && kotlin.math.hypot(x - b[0], y - b[1]) <= b[2] * 1.3f) {
                val idx = binding.stickerLayer.selected
                if (idx in stickerItems.indices) {
                    val st = stickerItems.removeAt(idx)
                    if (st.owned) st.bitmap.recycle()
                    binding.stickerLayer.selected = -1
                    if (stickerItems.isEmpty()) binding.stickerLayer.visibility = View.GONE
                    if (sourceBitmap != null) renderPreview()
                }
            }
        }
    }

    // ---------- 拼图 ----------

    /** 当前拼图模板："auto" 或 "列x行"（如 2x2） */
    private var collageTemplate = "auto"

    /** 多选照片：用 StartActivityForResult + EXTRA_ALLOW_MULTIPLE（不依赖新版 activity 的 GetMultipleContent） */
    private val pickCollage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uris = ArrayList<Uri>()
        val data = res.data
        if (data != null) {
            data.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris.add(it) }
            } ?: data.data?.let { uris.add(it) }
        }
        if (uris.isNotEmpty()) buildCollage(uris)
    }

    private fun launchCollagePicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(android.content.Intent.EXTRA_LOCAL_ONLY, false)
        }
        pickCollage.launch(intent)
    }

    private fun buildCollageTemplates() {
        val row = binding.collageTemplateRow
        row.removeAllViews()
        val templates = listOf("自动", "1×2", "2×1", "2×2", "1×3", "3×1")
        templates.forEachIndexed { i, label ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.tab_text, null))
                setBackgroundResource(R.drawable.bg_tab)
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, 0, p, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * resources.displayMetrics.density).toInt()
                ).apply { marginStart = (6 * resources.displayMetrics.density).toInt() }
                setOnClickListener {
                    collageTemplate = if (i == 0) "auto" else label.replace("×", "x")
                    (0 until row.childCount).forEach { j ->
                        (row.getChildAt(j) as? TextView)?.isSelected = j == i
                    }
                }
            }
            row.addView(chip)
        }
        (row.getChildAt(0) as? TextView)?.isSelected = true
    }

    private fun buildCollage(uris: List<Uri>) {
        val ctx = context ?: return
        lifecycleScope.launch {
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.take(9).mapNotNull { BitmapUtils.decodeSampled(ctx, it, 1200) }
                }
                if (bitmaps.isEmpty()) {
                    toast("图片读取失败")
                    return@launch
                }
                val (cols, rows) = if (collageTemplate == "auto") {
                    CollageBuilder.autoTemplate(bitmaps.size)
                } else {
                    val parts = collageTemplate.split("x")
                    (parts.getOrNull(0)?.toIntOrNull() ?: 1) to (parts.getOrNull(1)?.toIntOrNull() ?: 1)
                }
                // 图片不够填满模板时按数量回退到自动模板
                val (fc, fr) = if (bitmaps.size < cols * rows) {
                    CollageBuilder.autoTemplate(bitmaps.size)
                } else cols to rows
                val out = withContext(Dispatchers.IO) {
                    CollageBuilder.build(bitmaps.take(fc * fr), fc, fr)
                }
                replaceSource(out)
                toast("拼图完成（${fc}×${fr}）")
            } catch (e: Exception) {
                toast("拼图失败：${e.message}")
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    // ---------- AI 三件套（云端 Qwen-Image-Edit） ----------

    private val aiApi = com.example.magicbeauty.api.SiliconFlowImageApi()

    /** AI 消除：涂抹要删的区域 → 涂灰 → 让模型按指令擦除并自然填充。 */
    private fun startAiErase() {
        if (sourceBitmap == null) {
            toast("先选一张照片")
            return
        }
        // 复用马赛克笔视图当“涂抹选区”
        binding.maskPaint.setImageSize(sourceBitmap!!.width, sourceBitmap!!.height)
        binding.maskPaint.visibility = View.VISIBLE
        binding.maskPaint.onMaskChanged = null // 涂抹阶段不实时渲染
        binding.tvHint.text = "涂抹要消除的内容，然后点「开始消除」"
        binding.tvHint.visibility = View.VISIBLE
        val btn = TextView(requireContext()).apply {
            text = "开始消除"
            setBackgroundResource(R.drawable.bg_tab)
            setTextColor(resources.getColor(R.color.tab_text, null))
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, 0, p, 0)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                binding.previewBox.removeView(this)
                runAiErase()
            }
        }
        binding.previewBox.addView(btn, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            (36 * resources.displayMetrics.density).toInt()
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = (56 * resources.displayMetrics.density).toInt()
        })
    }

    private fun runAiErase() {
        val src = sourceBitmap ?: return
        val mask = binding.maskPaint.mask
        if (mask == null || !binding.maskPaint.hasPaint()) {
            toast("先涂抹要消除的区域")
            return
        }
        binding.maskPaint.visibility = View.GONE
        binding.tvHint.visibility = View.GONE
        // 恢复马赛克笔的常规回调
        binding.maskPaint.onMaskChanged = {
            mosaicSpec.mask = binding.maskPaint.mask
            if (sourceBitmap != null) renderPreview()
        }
        lifecycleScope.launch {
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
                val png = withContext(Dispatchers.IO) {
                    AiTools.compositeForErase(src, mask)
                }
                val outBytes = aiApi.edit(
                    png,
                    "移除图中所有中性灰色（#9E9E9E）标记的区域，用周围环境自然填充，" +
                        "保持其余部分与原图完全一致，不要改变构图、色彩和清晰度"
                )
                val bmp = withContext(Dispatchers.IO) { BitmapUtils.fromBytes(outBytes) }
                if (bmp == null) {
                    toast("AI 返回的图片解析失败")
                    return@launch
                }
                replaceSource(bmp)
                // AI 输出的画面不可预测，手动定位线失效，清除
                manualHemY = null
                manualAnkleY = null
                toast("消除完成")
            } catch (e: Exception) {
                toast("AI 消除失败：${e.message}")
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    /** AI 修复：老照片增强（清晰度/划痕/上色）。 */
    private fun startAiRestore() {
        if (sourceBitmap == null) {
            toast("先选一张照片")
            return
        }
        lifecycleScope.launch {
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
                val src = sourceBitmap!!
                val png = withContext(Dispatchers.IO) { AiTools.toUploadPng(src) }
                val outBytes = aiApi.edit(
                    png,
                    "修复这张照片：提升清晰度与细节，去除噪点、划痕和折痕，" +
                        "如果是黑白照片则进行自然上色（肤色真实、服饰配色合理），" +
                        "保持人物五官和构图与原图一致，不要改变照片内容"
                )
                val bmp = withContext(Dispatchers.IO) { BitmapUtils.fromBytes(outBytes) }
                if (bmp == null) {
                    toast("AI 返回的图片解析失败")
                    return@launch
                }
                replaceSource(bmp)
                manualHemY = null
                manualAnkleY = null
                toast("修复完成")
            } catch (e: Exception) {
                toast("AI 修复失败：${e.message}")
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    /** AI 扩图：pad 画布 → 模型补内容 → 裁回原尺寸。 */
    private fun startAiExpand() {
        if (sourceBitmap == null) {
            toast("先选一张照片")
            return
        }
        lifecycleScope.launch {
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
                val src = sourceBitmap!!
                val (padded, inset) = AiTools.padForExpand(src, 0.25f)
                val png = withContext(Dispatchers.IO) { AiTools.toUploadPng(padded, 2048) }
                val outBytes = aiApi.edit(
                    png,
                    "把画面自然扩展到四周新增的中性灰色（#9E9E9E）区域：延续背景纹理、" +
                        "光线和构图逻辑补全内容，主体保持完整不变，不要改变原有画面"
                )
                val bmp = withContext(Dispatchers.IO) { BitmapUtils.fromBytes(outBytes) }
                if (bmp == null) {
                    toast("AI 返回的图片解析失败")
                    return@launch
                }
                // 裁回原图区域后再走一遍完整管线（模型输出尺寸可能与 pad 图略有出入）
                val cropped = AiTools.cropInset(bmp, inset)
                replaceSource(cropped)
                manualHemY = null
                manualAnkleY = null
                toast("扩图完成")
            } catch (e: Exception) {
                toast("AI 扩图失败：${e.message}")
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    // ---------- 工具：裁剪 ----------

    private fun buildCropRatios() {
        val row = binding.cropRatioRow
        row.removeAllViews()
        val ratios = listOf(
            "自由" to CropOverlayView.RATIO_FREE,
            "1:1" to CropOverlayView.RATIO_1_1,
            "4:3" to CropOverlayView.RATIO_4_3,
            "3:4" to CropOverlayView.RATIO_3_4,
            "16:9" to CropOverlayView.RATIO_16_9,
            "9:16" to CropOverlayView.RATIO_9_16
        )
        ratios.forEach { (label, r) ->
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 12.5f
                gravity = android.view.Gravity.CENTER
                setTextColor(resources.getColor(R.color.tab_text, null))
                setBackgroundResource(R.drawable.bg_tab)
                val p = (10 * resources.displayMetrics.density).toInt()
                setPadding(p, 0, p, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (28 * resources.displayMetrics.density).toInt()
                ).apply { marginStart = (6 * resources.displayMetrics.density).toInt() }
                setOnClickListener {
                    binding.cropOverlay.setRatio(r)
                    ratios.forEachIndexed { i, _ ->
                        (row.getChildAt(i) as? TextView)?.isSelected = i == row.indexOfChild(this)
                    }
                }
            }
            row.addView(chip)
        }
        (row.getChildAt(0) as? TextView)?.isSelected = true
    }

    /** 应用裁剪：替换源图 → 重算引导 → 重渲染。贴纸/mask 跟着一起裁。 */
    private fun applyCrop() {
        val src = sourceBitmap ?: return
        val r = binding.cropOverlay.cropRect()
        val x = (r.left * src.width).toInt().coerceIn(0, src.width - 2)
        val y = (r.top * src.height).toInt().coerceIn(0, src.height - 2)
        val w = (r.width() * src.width).toInt().coerceIn(1, src.width - x)
        val h = (r.height() * src.height).toInt().coerceIn(1, src.height - y)
        val cropped = Bitmap.createBitmap(src, x, y, w, h)
        // 贴纸位置换算到新图坐标
        val rw = r.width().coerceAtLeast(0.001f)
        val rh = r.height().coerceAtLeast(0.001f)
        stickerItems.forEach { st ->
            st.cx = ((st.cx - r.left) / rw).coerceIn(0f, 1f)
            st.cy = ((st.cy - r.top) / rh).coerceIn(0f, 1f)
        }
        // 手动定位线跟着裁剪区域换算（线是 GL y，先转 y 向下再换算）
        manualHemY = manualHemY?.let { g ->
            (1f - (1f - g - r.top) / rh).coerceIn(0.02f, 0.98f)
        }
        manualAnkleY = manualAnkleY?.let { g ->
            (1f - (1f - g - r.top) / rh).coerceIn(0.01f, 0.96f)
        }
        // mask 同步裁剪
        val oldMask = mosaicSpec.mask
        if (oldMask != null && oldMask.width == src.width && oldMask.height == src.height) {
            val mx = (r.left * oldMask.width).toInt().coerceIn(0, oldMask.width - 2)
            val my = (r.top * oldMask.height).toInt().coerceIn(0, oldMask.height - 2)
            val mw = (r.width() * oldMask.width).toInt().coerceIn(1, oldMask.width - mx)
            val mh = (r.height() * oldMask.height).toInt().coerceIn(1, oldMask.height - my)
            mosaicSpec.mask = Bitmap.createBitmap(oldMask, mx, my, mw, mh)
            mosaicSpec.maskVersion++
            oldMask.recycle()
            binding.maskPaint.replaceMask(mosaicSpec.mask!!)
        }
        replaceSource(cropped)
        binding.cropOverlay.reset()
    }

    // ---------- 工具：旋转/翻转 ----------

    /** op: rotL/rotR/flipH/flipV。源图、mask、贴纸一起变换，然后重算引导。 */
    private fun applyTransform(op: String) {
        val src = sourceBitmap ?: return
        val m = Matrix()
        when (op) {
            "rotL" -> m.postRotate(-90f)
            "rotR" -> m.postRotate(90f)
            "flipH" -> m.postScale(-1f, 1f)
            "flipV" -> m.postScale(1f, -1f)
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        // 贴纸：坐标随图变换，旋转角也叠加
        for (st in stickerItems) {
            when (op) {
                "rotL" -> {
                    val nx = st.cy; val ny = 1f - st.cx
                    st.cx = nx; st.cy = ny
                    st.rotation -= PI.toFloat() / 2f
                }
                "rotR" -> {
                    val nx = 1f - st.cy; val ny = st.cx
                    st.cx = nx; st.cy = ny
                    st.rotation += PI.toFloat() / 2f
                }
                "flipH" -> { st.cx = 1f - st.cx; st.rotation = -st.rotation }
                "flipV" -> { st.cy = 1f - st.cy; st.rotation = -st.rotation }
            }
        }
        // 手动定位线：90° 旋转后横线会变竖线，无法表达，直接清除让用户重拖；
        // 水平翻转不影响 y；垂直翻转镜像 y
        when (op) {
            "rotL", "rotR" -> {
                manualHemY = null
                manualAnkleY = null
            }
            "flipV" -> {
                manualHemY = manualHemY?.let { 1f - it }
                manualAnkleY = manualAnkleY?.let { 1f - it }
            }
        }
        // mask 同步变换（像素对齐，用同一个矩阵）
        val oldMask = mosaicSpec.mask
        if (oldMask != null) {
            val newMask = Bitmap.createBitmap(oldMask, 0, 0, oldMask.width, oldMask.height, m, true)
            mosaicSpec.mask = newMask
            mosaicSpec.maskVersion++
            oldMask.recycle()
            binding.maskPaint.replaceMask(newMask)
        }
        replaceSource(out)
    }

    /** 替换源图：重新上传 + 重跑人像分析（旋转/裁剪后人脸坐标全变了，必须重算）。 */
    private fun replaceSource(newSrc: Bitmap) {
        val ctx = context ?: return
        lifecycleScope.launch {
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
                val pair = try {
                    withContext(glDispatcher) {
                        val s = engine.setSource(newSrc, 1920)
                        val g = try { mp.analyze(s) } catch (_: Exception) { WarpGuide() }
                        s to g
                    }
                } catch (e: Exception) {
                    toast("处理失败：${e.message}")
                    return@launch
                }
                sourceBitmap = pair.first
                guide = pair.second
                if (_binding == null) return@launch
                updateDetectHint()
                renderPreview()
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    // ---------- 工具：边框 ----------

    private val borderColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF1A1A1A.toInt(), 0xFFC9A96A.toInt(),
        0xFFB04A4A.toInt(), 0xFF3E5C8A.toInt()
    )

    private fun buildBorderColors() {
        val row = binding.borderColorRow
        row.removeAllViews()
        val size = (24 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        borderColors.forEachIndexed { i, color ->
            val dot = View(requireContext()).apply {
                background = resources.getDrawable(R.drawable.bg_dot, null).mutate().apply {
                    setTint(color)
                }
                setOnClickListener {
                    params.borderColor[0] = ((color ushr 16) and 0xFF) / 255f
                    params.borderColor[1] = ((color ushr 8) and 0xFF) / 255f
                    params.borderColor[2] = (color and 0xFF) / 255f
                    refreshBorderDots(i)
                    if (sourceBitmap != null) renderPreview()
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = if (i == 0) 0 else margin
            })
        }
        refreshBorderDots(0)
    }

    private fun refreshBorderDots(sel: Int) {
        val row = binding.borderColorRow
        for (i in 0 until row.childCount) {
            val dot = row.getChildAt(i)
            dot.background = resources.getDrawable(
                if (i == sel) R.drawable.bg_dot_sel else R.drawable.bg_dot, null
            ).mutate().apply { setTint(borderColors[i]) }
        }
    }

    // ---------- 工具：贴纸/文字 ----------

    private fun buildStickerGallery() {
        val row = binding.stickerRow
        row.removeAllViews()
        val size = (52 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        Stickers.entries.forEach { entry ->
            val iv = android.widget.ImageView(requireContext()).apply {
                setImageBitmap(Stickers.bitmap(entry.name))
                setPadding(
                    (6 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt(),
                    (6 * resources.displayMetrics.density).toInt()
                )
                setBackgroundResource(R.drawable.bg_tab)
                setOnClickListener { addSticker(Stickers.bitmap(entry.name), owned = false) }
            }
            row.addView(iv, LinearLayout.LayoutParams(size, size).apply {
                marginStart = margin
            })
        }
    }

    private fun addSticker(bmp: Bitmap, owned: Boolean) {
        if (stickerItems.size >= 4) {
            toast("最多 4 层贴纸")
            return
        }
        stickerItems.add(
            StickerItem(bmp, cx = 0.5f, cy = 0.45f, scale = 0.35f).apply { this.owned = owned }
        )
        binding.stickerLayer.stickers = stickerItems
        binding.stickerLayer.selected = stickerItems.size - 1
        binding.stickerLayer.visibility = View.VISIBLE
        if (sourceBitmap != null) renderPreview()
    }

    private fun showTextDialog() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            setText("双击编辑")
            setSingleLine(false)
            maxLines = 3
            setHint("输入文字")
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("添加文字")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    addSticker(Stickers.textBitmap(text, 0xFFFFFFFF.toInt()), owned = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildFilterGroups() {
        val row = binding.filterGroupRow
        row.removeAllViews()
        Filters.groups.forEach { g ->
            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_filter_chip, row, false) as TextView
            chip.text = g.name
            chip.setOnClickListener {
                filterGroup = g.name
                buildFilterChips()
                refreshGroupChips()
            }
            row.addView(chip)
        }
        refreshGroupChips()
    }

    private fun refreshGroupChips() {
        val row = binding.filterGroupRow
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i) as TextView
            val selected = Filters.groups[i].name == filterGroup
            c.isSelected = selected
            c.setTextColor(
                if (selected) 0xFF0E0F18.toInt() else resources.getColor(R.color.tab_text, null)
            )
        }
    }

    private fun buildFilterChips() {
        val row = binding.filterRow
        row.removeAllViews()
        val group = Filters.groups.firstOrNull { it.name == filterGroup } ?: return
        group.indices.forEach { id ->
            val chip = LayoutInflater.from(requireContext())
                .inflate(R.layout.view_filter_chip, row, false) as TextView
            chip.text = Filters.get(id).name
            chip.setOnClickListener {
                params.filterId = id
                refreshFilterChips()
                if (sourceBitmap != null) renderPreview()
            }
            row.addView(chip)
        }
        refreshFilterChips()
    }

    private fun refreshFilterChips() {
        val row = binding.filterRow
        val group = Filters.groups.firstOrNull { it.name == filterGroup } ?: return
        group.indices.forEachIndexed { i, id ->
            val c = row.getChildAt(i) as? TextView ?: return@forEachIndexed
            val selected = id == params.filterId
            c.isSelected = selected
            c.setTextColor(
                if (selected) 0xFF0E0F18.toInt() else resources.getColor(R.color.tab_text, null)
            )
        }
    }

    // ---------- HSL 六色分频 ----------

    /** 六个色相带的中性色（只用于色带圆点展示） */
    private val hslBandColors = intArrayOf(
        0xFFFF5A5A.toInt(), // 红
        0xFFFFB84D.toInt(), // 橙/黄
        0xFF58D68D.toInt(), // 绿
        0xFF4DD8E0.toInt(), // 青
        0xFF5A8CFF.toInt(), // 蓝
        0xFFE05AC8.toInt()  // 品红
    )
    private var hslBand = 0

    private fun buildHslBands() {
        val row = binding.hslBandRow
        row.removeAllViews()
        val size = (22 * resources.displayMetrics.density).toInt()
        val margin = (5 * resources.displayMetrics.density).toInt()
        hslBandColors.forEachIndexed { i, color ->
            val dot = View(requireContext()).apply {
                background = resources.getDrawable(R.drawable.bg_dot, null).mutate().apply {
                    setTint(color)
                }
                setOnClickListener {
                    hslBand = i
                    refreshHslDots()
                    syncHslSliders()
                }
            }
            row.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                marginStart = if (i == 0) 0 else margin
            })
        }
        refreshHslDots()
        syncHslSliders()
    }

    private fun refreshHslDots() {
        val row = binding.hslBandRow
        for (i in 0 until row.childCount) {
            val dot = row.getChildAt(i)
            dot.background = resources.getDrawable(
                if (i == hslBand) R.drawable.bg_dot_sel else R.drawable.bg_dot, null
            ).mutate().apply { setTint(hslBandColors[i]) }
        }
    }

    private fun syncHslSliders() {
        if (_binding == null) return
        binding.sbHslHue.progress = ((params.hslHue[hslBand] + 0.5f) * 100).toInt().coerceIn(0, 100)
        binding.sbHslSat.progress = (params.hslSat[hslBand] * 50).toInt().coerceIn(0, 100)
        binding.sbHslLum.progress = ((params.hslLum[hslBand] + 0.3f) / 0.6f * 100).toInt().coerceIn(0, 100)
    }

    // ---------- 曲线 ----------

    private fun showCurveDialog() {
        val ctx = context ?: return
        val curve = CurveView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt()
            )
        }
        // 打开时恢复当前 LUT 对应的点位置（如果刚才编辑过）
        val versionAtOpen = params.curveVersion
        curve.onLutChanged = { lut ->
            params.curveLut = lut
            params.curveVersion++
            if (sourceBitmap != null) renderPreview()
        }
        // 用默认点初始化，若之前改过则保持默认进入（用户可重拖）
        curve.reset()
        updateCurveState()
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("RGB 曲线")
            .setView(curve)
            .setPositiveButton("完成") { d, _ -> d.dismiss() }
            .setNeutralButton("重置") { _, _ ->
                curve.reset()
                updateCurveState()
            }
            .setNegativeButton("取消") { _, _ ->
                // 回到打开时的状态：没改过就清掉，改过就重建一份默认 LUT
                if (versionAtOpen == 0) {
                    params.curveLut = null
                    params.curveVersion = 0
                } else {
                    params.curveLut = CurveView(ctx).run { reset(); buildLut() }
                    params.curveVersion++
                }
                updateCurveState()
                if (sourceBitmap != null) renderPreview()
            }
            .create()
        dialog.show()
    }

    private fun updateCurveState() {
        if (_binding == null) return
        binding.tvCurveState.text = if (params.curveLut == null) "默认" else "已调整"
    }

    private fun slider(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            onChange(p)
            if (sourceBitmap != null) renderPreview()
        }
        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    }

    /**
     * 切换底部工具分类。再点一次当前分类 = 收起/展开滑块，
     * 这样用户既能常驻看到工具，也能让图片占满更多空间。
     */
    private fun selectTab(index: Int) {
        val b = _binding ?: return
        // 点的是当前分类：切换收起/展开
        if (expanded && index == currentTab) {
            setExpanded(false)
            return
        }
        currentTab = index
        b.tabLight.isSelected = index == 0
        b.tabTexture.isSelected = index == 1
        b.tabFace.isSelected = index == 2
        b.tabShape.isSelected = index == 3
        b.tabTool.isSelected = index == 4
        b.tabAi.isSelected = index == 5
        applyGroupVisibility()
        if (!expanded) setExpanded(true) else updatePreviewInsets()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        applyGroupVisibility()
    }

    private fun applyGroupVisibility() {
        val b = _binding ?: return
        val groups = arrayOf(b.groupLight, b.groupTexture, b.groupFace, b.groupShape, b.groupTool, b.groupAi)
        groups.forEachIndexed { i, g ->
            val show = expanded && i == currentTab
            if (show) {
                if (g.visibility != View.VISIBLE) {
                    g.visibility = View.VISIBLE
                    g.alpha = 0f
                    g.animate().alpha(1f).setDuration(150).start()
                }
            } else {
                g.animate().cancel()
                g.visibility = View.GONE
            }
        }
        // 收起时把拖拽条转 180°，提示"可以往下拉"
        b.sheetHandle.rotation = if (expanded) 0f else 180f
        b.sheetHandle.post { updatePreviewInsets() }
    }

    /**
     * 图片始终居中显示在「顶部栏以下、工具面板以上」的可见区域。
     * 面板收起/展开时重新算一次，保证图片不会被挡住，也不会因为留白显得很小。
     */
    private fun updatePreviewInsets() {
        val b = _binding ?: return
        b.preview.post {
            val bb = _binding ?: return@post
            val top = bb.topBar.height
            val bottom = bb.toolSheet.height
            bb.preview.setPadding(0, top, 0, bottom)
            // 三个浮层与图片保持同一可见区域，否则会盖到顶栏/面板上
            bb.stickerLayer.setPadding(0, top, 0, bottom)
            bb.cropOverlay.setPadding(0, top, 0, bottom)
            bb.maskPaint.setPadding(0, top, 0, bottom)
            bb.guideLines.setPadding(0, top, 0, bottom)
        }
    }

    private fun loadImage(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch {
            // 人体解析模型 66MB，首次要解压+加载，可能几秒；不给提示会被当成卡死
            if (_binding != null) binding.progress.visibility = View.VISIBLE
            try {
            // 解码放到 IO 线程（旧版没有采样，大图会 OOM；也没有处理 EXIF 旋转）
            val bmp = withContext(Dispatchers.IO) { BitmapUtils.decodeSampled(ctx, uri, 2048) }
            if (bmp == null) {
                toast("图片读取失败")
                return@launch
            }
            val pair = try {
                withContext(glDispatcher) {
                    val src = engine.setSource(bmp, 1920)
                    val g = try { mp.analyze(src) } catch (_: Exception) { WarpGuide() }
                    src to g
                }
            } catch (e: Exception) {
                toast("图片处理失败：${e.message}")
                return@launch
            }
            sourceBitmap = pair.first
            guide = pair.second
            if (_binding == null) return@launch
            binding.btnSave.isEnabled = true
            // 换图：贴纸/马赛克全部重置（mask 与旧图同尺寸，不能带到新图上）
            stickerItems.forEach { if (it.owned) it.bitmap.recycle() }
            stickerItems.clear()
            binding.stickerLayer.selected = -1
            binding.stickerLayer.stickers = stickerItems
            binding.stickerLayer.visibility = View.GONE
            mosaicSpec.enabled = false
            mosaicSpec.mask = null
            binding.maskPaint.setImageSize(pair.first.width, pair.first.height)
            binding.cropOverlay.setImageSize(pair.first.width, pair.first.height)
            binding.stickerLayer.setImageSize(pair.first.width, pair.first.height)
            binding.guideLines.setImageSize(pair.first.width, pair.first.height)
            manualHemY = null
            manualAnkleY = null
            updateDetectHint()
            renderPreview()
            } finally {
                if (_binding != null) binding.progress.visibility = View.GONE
            }
        }
    }

    /** 没检测到人脸/人体时，明确告诉用户哪几个功能不会生效，而不是把效果套在画面中央。 */
    private fun updateDetectHint() {
        if (_binding == null) return
        val missing = ArrayList<String>(2)
        val off = ArrayList<String>(2)
        if (!guide.hasFace) {
            missing.add("人脸")
            off.add("瘦脸/大眼/瘦鼻/唇形/下巴")
        }
        if (!guide.hasPose) {
            missing.add("人体")
            off.add("瘦腰/拉腿/瘦肩/瘦臀/瘦腿")
        }
        if (missing.isEmpty()) {
            binding.tvHint.visibility = View.GONE
        } else {
            // 只缺人脸时，不要连带说“瘦腰/拉腿也失效”，之前那句提示会误导
            binding.tvHint.text = "未识别到${missing.joinToString("、")}：" +
                    "${off.joinToString("、")}暂不生效（其余功能正常，正脸/全身照效果最好）"
            binding.tvHint.visibility = View.VISIBLE
        }
    }

    /** 当前叠加内容（贴纸/文字 + 马赛克）。马赛克开着且没有 mask 也不是整图模式时不生效。 */
    private fun currentOverlays() = OverlaySpec(
        stickers = stickerItems.toList(),
        mosaic = mosaicSpec
    )

    private fun renderPreview() {
        if (sourceBitmap == null || _binding == null) return        // 渲染进行中就先记一个待办，避免拖滑块时堆积成百上千个任务
        if (rendering) {
            pendingRender = true
            return
        }
        rendering = true
        renderJob = lifecycleScope.launch {
            try {
                // 手动定位线（裙摆/脚踝）叠加到引导上，兜底检测不准
                val g = if (manualHemY != null || manualAnkleY != null) {
                    guide.copy(manualHemY = manualHemY, manualAnkleY = manualAnkleY)
                } else guide
                // 关键：没检测到就不硬套默认坐标，否则效果会落在画面中央而不是脸上
                val effective = params.copy(
                    thinFace = if (g.hasFace) params.thinFace else 0f,
                    eyeEnlarge = if (g.hasFace) params.eyeEnlarge else 0f,
                    waistSlim = if (g.waistOk) params.waistSlim else 0f,
                    legStretch = if (g.legOk) params.legStretch else 0f,
                    // 面部重塑与塑形四件套：引导不可用时清零（引擎里也有同样的门控）
                    noseSlim = if (g.faceFeatureOk) params.noseSlim else 0f,
                    mouthSlim = if (g.faceFeatureOk) params.mouthSlim else 0f,
                    chinSlim = if (g.faceFeatureOk) params.chinSlim else 0f,
                    shoulderSlim = if (g.shoulderOk) params.shoulderSlim else 0f,
                    armSlim = if (g.armOk) params.armSlim else 0f,
                    hipSlim = if (g.hipOk) params.hipSlim else 0f,
                    legSlim = if (g.legSlimOk) params.legSlim else 0f
                )
                val out = withContext(glDispatcher) {
                    engine.render(effective, g, currentOverlays())
                }
                if (_binding == null || !isAdded) return@launch
                // 不要 recycle 旧图：ImageView 可能还在主线程绘制它，
                // 在后台线程 recycle 会偶发 "trying to use a recycled bitmap" 崩溃。
                lastResult = out
                binding.preview.setImageBitmap(out)
                binding.emptyState.visibility = View.GONE
            } catch (e: Exception) {
                // 不要静默吞掉：渲染失败必须能看见，否则就是“选了图没反应”
                android.util.Log.e("MagicBeauty", "render failed", e)
                if (!renderErrorShown) {
                    renderErrorShown = true
                    toast("预览失败：${e.message}")
                }
            } finally {
                rendering = false
                if (pendingRender) {
                    pendingRender = false
                    renderPreview()
                }
            }
        }
    }

    private fun saveCurrent() {
        val bmp = lastResult ?: run {
            toast("还没有可保存的结果")
            return
        }
        val ctx = context ?: return
        // 写相册是 IO 操作，不要放主线程（大图会卡顿）
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { SaveUtils.saveBitmap(ctx, bmp) }
            toast(if (ok) "已保存到相册/MagicBeauty" else "保存失败（请检查存储权限）")
        }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        renderJob?.cancel()
        // GL 资源必须在它自己的线程上释放
        try {
            runBlocking { withContext(glDispatcher) { engine.release() } }
        } catch (_: Exception) {}
        try {
            if (::mp.isInitialized) mp.close()
        } catch (_: Exception) {}
        try { glDispatcher.close() } catch (_: Exception) {}
    }
}
