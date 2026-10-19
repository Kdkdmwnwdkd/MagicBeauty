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
import com.example.magicbeauty.engine.LocalHairEngine
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class HairFragment : Fragment() {

    private var _binding: FragmentHairBinding? = null
    private val binding get() = _binding!!

    private var imageUri: Uri? = null
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            sourceBitmap = loadBitmap(it)
            binding.btnGen.isEnabled = true
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? = try {
        val stream: InputStream? = requireContext().contentResolver.openInputStream(uri)
        val bmp = BitmapFactory.decodeStream(stream)
        stream?.close()
        bmp
    } catch (_: Exception) { null }

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

    private fun generate() {
        val uri = imageUri ?: return
        val useLocal = binding.rbLocal.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.VISIBLE
                binding.btnGen.isEnabled = false
            }
            try {
                if (useLocal) {
                    // 端侧托底：离线换发色，零成本
                    val src = sourceBitmap ?: throw Exception("未选择图片")
                    val out = LocalHairEngine.recolor(src)
                    resultBitmap = out
                    withContext(Dispatchers.Main) {
                        binding.preview.setImageBitmap(out)
                        binding.btnSave.isEnabled = true
                    }
                } else {
                    // 云端生成式：需要硅基流动 Key
                    if (ApiConfig.siliconFlowKey.isBlank()) {
                        throw Exception("未配置硅基流动 Key，请到「设置」页填写，或切换到「端侧染发」")
                    }
                    val prompt = binding.etPrompt.text.toString()
                        .ifBlank { "flowing long wavy hair with wispy strands, silky, anime style" }
                    val stream: InputStream? = requireContext().contentResolver.openInputStream(uri)
                    val data = stream?.readBytes() ?: byteArrayOf()
                    stream?.close()
                    val outBytes = BeautyService.generateHair(data, prompt)
                    val bmp = BitmapFactory.decodeByteArray(outBytes, 0, outBytes.size)
                    resultBitmap = bmp
                    withContext(Dispatchers.Main) {
                        binding.preview.setImageBitmap(bmp)
                        binding.btnSave.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    binding.btnGen.isEnabled = true
                }
            }
        }
    }

    private fun save() {
        val bmp = resultBitmap ?: return
        val ok = SaveUtils.saveBitmap(requireContext(), bmp, "magic_hair_${System.currentTimeMillis()}")
        Toast.makeText(requireContext(), if (ok) "已保存到相册/MagicBeauty" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
