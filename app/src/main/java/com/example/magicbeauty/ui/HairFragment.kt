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
import com.example.magicbeauty.api.BeautyService
import com.example.magicbeauty.databinding.FragmentHairBinding
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class HairFragment : Fragment() {

    private var _binding: FragmentHairBinding? = null
    private val binding get() = _binding!!

    private var imageUri: Uri? = null
    private var resultBitmap: Bitmap? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            imageUri = it
            binding.btnGen.isEnabled = true
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

    private fun generate() {
        val uri = imageUri ?: return
        val prompt = binding.etPrompt.text.toString().ifBlank { "flowing long wavy hair with wispy strands" }
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.VISIBLE
                binding.btnGen.isEnabled = false
            }
            try {
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "生成失败：${e.message}", Toast.LENGTH_LONG).show()
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
