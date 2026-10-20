package com.example.magicbeauty.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.example.magicbeauty.databinding.FragmentHairBinding
import com.example.magicbeauty.engine.HairSegmenter
import com.example.magicbeauty.engine.LocalHairEngine
import com.example.magicbeauty.util.BitmapUtils
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class HairFragment : Fragment() {

    private var _binding: FragmentHairBinding? = null
    private val binding get() = _binding!!

    private var imageUri: Uri? = null
    private var resultBitmap: Bitmap? = null
    private var segmenter: HairSegmenter? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            if (_binding != null) binding.btnGen.isEnabled = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentHairBinding.inflate(inflater, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPick.setOnClickListener { pickImage.launch("image/*") }
        binding.btnGen.setOnClickListener { generate() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun ensureSegmenter(): HairSegmenter =
        segmenter ?: HairSegmenter(requireContext().applicationContext).also { segmenter = it }

    private fun generate() {
        val uri = imageUri ?: return
        val useLocal = binding.rbLocal.isChecked
        val ctx = context ?: return

        lifecycleScope.launch {
            showBusy(true)
            try {
                if (useLocal) {
                    // 端侧托底：离线换发色，只作用于头发区域
                    val src = withContext(Dispatchers.IO) {
                        BitmapUtils.decodeSampled(ctx, uri, 1600)
                    } ?: throw Exception("图片读取失败")

                    var usedMask = true
                    val out = withContext(Dispatchers.IO) {
                        val mask = try { ensureSegmenter().hairMask(src) } catch (_: Throwable) { null }
                        usedMask = mask != null
                        LocalHairEngine.recolor(src, mask)
                    }

                    resultBitmap = out
                    if (_binding != null) {
                        binding.preview.setImageBitmap(out)
                        binding.btnSave.isEnabled = true
                    }
                    if (!usedMask) toast("未识别到头发，已退回整图调色")
                } else {
                    // 云端生成式：需要硅基流动 Key
                    if (ApiConfig.siliconFlowKey.isBlank()) {
                        throw Exception("未配置硅基流动 Key，请到「设置」页填写，或切换到「端侧染发」")
                    }
                    val prompt = binding.etPrompt.text.toString()
                        .ifBlank { "flowing long wavy hair with wispy strands, silky, anime style" }

                    val bmp = withContext(Dispatchers.IO) {
                        BitmapUtils.decodeSampled(ctx, uri, 1536)
                    } ?: throw Exception("图片读取失败")

                    val pngBytes = withContext(Dispatchers.IO) {
                        ByteArrayOutputStream().use { bos ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            bos.toByteArray()
                        }
                    }

                    val outBytes = BeautyService.generateHair(pngBytes, prompt)
                    val outBmp = BitmapFactory.decodeByteArray(outBytes, 0, outBytes.size)
                        ?: throw Exception("返回的图片无法解析")

                    resultBitmap = outBmp
                    if (_binding != null) {
                        binding.preview.setImageBitmap(outBmp)
                        binding.btnSave.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                toast("失败：${e.message}")
            } finally {
                showBusy(false)
            }
        }
    }

    private fun showBusy(busy: Boolean) {
        if (_binding == null) return
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnGen.isEnabled = !busy
    }

    private fun save() {
        val bmp = resultBitmap ?: return
        val ctx = context ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                SaveUtils.saveBitmap(ctx, bmp, "magic_hair_${System.currentTimeMillis()}")
            }
            toast(if (ok) "已保存到相册/MagicBeauty" else "保存失败（请检查存储权限）")
        }
    }

    private fun toast(msg: String) {
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { segmenter?.close() } catch (_: Exception) {}
        segmenter = null
    }
}
