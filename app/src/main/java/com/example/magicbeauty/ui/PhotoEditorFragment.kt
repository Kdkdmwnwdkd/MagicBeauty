package com.example.magicbeauty.ui

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
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
        mp = MediaPipeHelper(requireContext())

        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }
        binding.btnSave.setOnClickListener { saveCurrent() }

        // 底部工具抽屉：收起时只露标签，点标签展开对应滑块
        binding.tabLight.setOnClickListener { selectTab(0) }
        binding.tabTexture.setOnClickListener { selectTab(1) }
        binding.tabShape.setOnClickListener { selectTab(2) }
        selectTab(0, expand = false)

        try {
            BottomSheetBehavior.from(binding.toolSheet).addBottomSheetCallback(
                object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        updatePreviewInsets()
                    }
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {}
                }
            )
        } catch (_: Exception) {}
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
        binding.sbThinFace.setOnSeekBarChangeListener(slider { params.thinFace = it / 100f })
        binding.sbEye.setOnSeekBarChangeListener(slider { params.eyeEnlarge = it / 100f * 0.5f })
        binding.sbWaist.setOnSeekBarChangeListener(slider { params.waistSlim = it / 100f })
        binding.sbLeg.setOnSeekBarChangeListener(slider { params.legStretch = it / 100f * 0.3f })
    }

    private fun slider(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            onChange(p)
            if (sourceBitmap != null) renderPreview()
        }
        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
    }

    /** 切换底部工具分类；点标签时把抽屉展开 */
    private fun selectTab(index: Int, expand: Boolean = true) {
        val b = _binding ?: return
        b.groupLight.visibility = if (index == 0) View.VISIBLE else View.GONE
        b.groupTexture.visibility = if (index == 1) View.VISIBLE else View.GONE
        b.groupShape.visibility = if (index == 2) View.VISIBLE else View.GONE
        b.tabLight.isSelected = index == 0
        b.tabTexture.isSelected = index == 1
        b.tabShape.isSelected = index == 2
        if (expand) {
            try {
                BottomSheetBehavior.from(b.toolSheet).state = BottomSheetBehavior.STATE_EXPANDED
            } catch (_: Exception) {}
        }
        updatePreviewInsets()
    }

    /**
     * 图片始终居中显示在「顶部栏以下、工具抽屉以上」的可见区域。
     * 抽屉收起/展开时重新算一次，保证图片不会被挡住，也不会因为留白显得很小。
     */
    private fun updatePreviewInsets() {
        val b = _binding ?: return
        b.preview.post {
            val bb = _binding ?: return@post
            // 用 y（含 translationY），抽屉拖动时 top 不一定会变
            val sheetTop = bb.toolSheet.y.toInt()
            if (sheetTop <= 0) return@post
            val top = bb.topBar.height
            val bottom = (bb.previewBox.height - sheetTop).coerceAtLeast(0)
            bb.preview.setPadding(0, top, 0, bottom)
        }
    }

    private fun loadImage(uri: Uri) {
        val ctx = context ?: return
        lifecycleScope.launch {
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
        }
    }

    /** 没检测到人脸/人体时，明确告诉用户哪几个功能不会生效，而不是把效果套在画面中央。 */
    private fun updateDetectHint() {
        if (_binding == null) return
        val missing = ArrayList<String>(2)
        if (!guide.hasFace) missing.add("人脸")
        if (!guide.hasPose) missing.add("人体")
        if (missing.isEmpty()) {
            binding.tvHint.visibility = View.GONE
        } else {
            binding.tvHint.text = "未识别到${missing.joinToString("、")}：" +
                    "对应的瘦脸/大眼/瘦腰/拉腿不会生效（正脸、全身照效果最好）"
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
