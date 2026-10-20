package com.example.magicbeauty.ui

import com.example.magicbeauty.MainActivity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.magicbeauty.databinding.FragmentPhotoBinding
import com.example.magicbeauty.engine.EditParams
import com.example.magicbeauty.engine.LocalEditEngine
import com.example.magicbeauty.engine.MediaPipeHelper
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
        binding.tabShape.setOnClickListener { selectTab(2) }
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
        binding.sbVignette.setOnSeekBarChangeListener(slider { params.vignette = it / 100f })

        // 滤镜：一排标签，点哪个套哪个（矩阵在 GPU 一次算完）
        val chips = (0 until binding.filterRow.childCount).map { binding.filterRow.getChildAt(it) }
        chips.forEachIndexed { i, v ->
            v.isSelected = i == params.filterId
            v.setOnClickListener {
                params.filterId = i
                chips.forEachIndexed { j, c -> c.isSelected = j == i }
                if (sourceBitmap != null) renderPreview()
            }
        }
        binding.sbThinFace.setOnSeekBarChangeListener(slider { params.thinFace = it / 100f })
        binding.sbEye.setOnSeekBarChangeListener(slider { params.eyeEnlarge = it / 100f * 0.35f })
        binding.sbWaist.setOnSeekBarChangeListener(slider { params.waistSlim = it / 100f })
        binding.sbLeg.setOnSeekBarChangeListener(slider { params.legStretch = it / 100f * 0.25f })
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
        b.tabShape.isSelected = index == 2
        applyGroupVisibility()
        if (!expanded) setExpanded(true) else updatePreviewInsets()
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        applyGroupVisibility()
    }

    private fun applyGroupVisibility() {
        val b = _binding ?: return
        val groups = arrayOf(b.groupLight, b.groupTexture, b.groupShape)
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
            off.add("瘦脸/大眼")
        }
        if (!guide.hasPose) {
            missing.add("人体")
            off.add("瘦腰/拉腿")
        }
        if (missing.isEmpty()) {
            binding.tvHint.visibility = View.GONE
        } else {
            // 只缺人脸时，不要连带说“瘦腰/拉腿也失效”，之前那句提示会误导
            binding.tvHint.text = "未识别到${missing.joinToString("、")}：" +
                    "${off.joinToString("、")}不会生效（正脸、全身照效果最好）"
            binding.tvHint.visibility = View.VISIBLE
        }
    }

    private fun renderPreview() {
        if (sourceBitmap == null || _binding == null) return
        // 渲染进行中就先记一个待办，避免拖滑块时堆积成百上千个任务
        if (rendering) {
            pendingRender = true
            return
        }
        rendering = true
        renderJob = lifecycleScope.launch {
            try {
                val g = guide
                // 关键：没检测到就不硬套默认坐标，否则效果会落在画面中央而不是脸上
                val effective = params.copy(
                    thinFace = if (g.hasFace) params.thinFace else 0f,
                    eyeEnlarge = if (g.hasFace) params.eyeEnlarge else 0f,
                    waistSlim = if (g.hasPose) params.waistSlim else 0f,
                    legStretch = if (g.hasPose) params.legStretch else 0f
                )
                val out = withContext(glDispatcher) { engine.render(effective, g) }
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
