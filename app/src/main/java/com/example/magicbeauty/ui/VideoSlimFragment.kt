package com.example.magicbeauty.ui

import com.example.magicbeauty.MainActivity
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.magicbeauty.api.ApiConfig
import com.example.magicbeauty.api.BeautyService
import com.example.magicbeauty.databinding.FragmentVideoBinding
import com.example.magicbeauty.engine.LocalVideoSlimEngine
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoSlimFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private var videoUri: Uri? = null

    // 本地处理：结果保留为文件，保存时流式写入相册（不把整段视频读进内存）
    private var resultFile: File? = null
    // 云端处理：接口直接返回字节，只能放内存
    private var resultBytes: ByteArray? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            videoUri = it
            if (_binding != null) {
                binding.btnProcess.isEnabled = true
                binding.tvStatus.text = "已选择视频，点击开始处理"
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentVideoBinding.inflate(inflater, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 左上角「≡」打开侧边抽屉（放在各自页面里最稳，不依赖 Activity 反射查 View）
        binding.btnMenu.setOnClickListener { (activity as? MainActivity)?.openDrawer() }
        binding.btnPick.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnProcess.setOnClickListener { process() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun process() {
        val uri = videoUri ?: return
        val strength = binding.etStrength.text.toString().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        val waist = strength
        val leg = strength * 0.5f
        val ctx = context ?: return

        // 清掉上一次的结果，避免 cache 里堆文件
        try { resultFile?.delete() } catch (_: Exception) {}
        resultFile = null
        resultBytes = null
        if (_binding != null) binding.btnSave.isEnabled = false

        lifecycleScope.launch {
            setBusy(true)
            var inFile: File? = null
            var outFile: File? = null
            var keepOut = false
            try {
                // 先把视频落到本地文件，避免整包读进内存（旧版 readBytes 大视频必 OOM）
                val src = File(ctx.cacheDir, "mb_in_${System.currentTimeMillis()}.mp4")
                inFile = src
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri) ?: throw Exception("无法读取视频")
                    input.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
                }

                if (ApiConfig.videoSlimEndpoint.isBlank()) {
                    val target = File(ctx.cacheDir, "mb_out_${System.currentTimeMillis()}.mp4")
                    outFile = target
                    val engine = LocalVideoSlimEngine(ctx)
                    val done = withContext(Dispatchers.IO) {
                        engine.process(
                            src, target, waist, leg,
                            onProgress = { d, total -> ui { updateProgress(d, total) } },
                            onLog = { msg -> ui { setStatus(msg) } }
                        )
                    }
                    resultFile = done
                    keepOut = true
                    resultBytes = null
                } else {
                    // 云端：直接流式上传文件，避免 readBytes 把长视频读爆内存
                    resultBytes = BeautyService.slimVideo(
                        src, "input.mp4",
                        mapOf("strength" to strength.toString())
                    )
                }

                ui { setStatus("处理完成，可保存到相册") }
                if (_binding != null) binding.btnSave.isEnabled = true
            } catch (e: Exception) {
                ui { setStatus("失败：${e.message}") }
            } finally {
                try { inFile?.delete() } catch (_: Exception) {}
                if (!keepOut) {
                    try { outFile?.delete() } catch (_: Exception) {}
                }
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        ui {
            if (_binding == null) return@ui
            binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            if (busy) {
                binding.progress.isIndeterminate = true
                binding.tvStatus.text = "处理中（端侧逐帧较慢，请耐心等待）…"
            }
            binding.btnProcess.isEnabled = !busy
        }
    }

    private fun updateProgress(done: Int, total: Int) {
        if (_binding == null) return
        binding.progress.isIndeterminate = false
        binding.progress.max = total.coerceAtLeast(1)
        binding.progress.progress = done
        binding.tvStatus.text = "端侧处理中 $done/$total"
    }

    private fun setStatus(msg: String) {
        if (_binding != null) binding.tvStatus.text = msg
    }

    private fun ui(block: () -> Unit) {
        val act = activity ?: return
        act.runOnUiThread { if (isAdded) block() }
    }

    private fun save() {
        val ctx = context ?: return
        val file = resultFile
        val data = resultBytes
        if (file == null && data == null) return

        // 视频几十 MB，写相册必须放到 IO 线程，否则很容易 ANR
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                when {
                    file != null && file.exists() -> SaveUtils.saveVideoFile(ctx, file)
                    data != null -> SaveUtils.saveVideo(ctx, data)
                    else -> false
                }
            }
            Toast.makeText(
                ctx,
                if (ok) "已保存到相册/MagicBeauty" else "保存失败（请检查存储权限）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
