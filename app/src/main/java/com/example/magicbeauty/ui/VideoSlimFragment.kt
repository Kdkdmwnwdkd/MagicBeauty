package com.example.magicbeauty.ui

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
import com.example.magicbeauty.databinding.FragmentVideoBinding
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class VideoSlimFragment : Fragment() {

    private var _binding: FragmentVideoBinding? = null
    private val binding get() = _binding!!

    private var videoUri: Uri? = null
    private var resultBytes: ByteArray? = null

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            videoUri = it
            binding.btnProcess.isEnabled = true
            binding.tvStatus.text = "已选择视频，点击开始处理"
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentVideoBinding.inflate(inflater, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPick.setOnClickListener { pickVideo.launch("video/*") }
        binding.btnProcess.setOnClickListener { process() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun process() {
        val uri = videoUri ?: return
        val strength = binding.etStrength.text.toString().toFloatOrNull()
        val extra = if (strength != null) mapOf("strength" to strength.toString()) else emptyMap()
        val filename = "input.mp4"

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.VISIBLE
                binding.btnProcess.isEnabled = false
                binding.tvStatus.text = "处理中（视频较大请耐心等待）…"
            }
            try {
                val bytes: InputStream? = requireContext().contentResolver.openInputStream(uri)
                val data = bytes?.readBytes() ?: byteArrayOf()
                bytes?.close()
                resultBytes = BeautyService.slimVideo(data, filename, extra)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "处理完成，可保存到相册"
                    binding.btnSave.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "失败：${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { binding.progress.visibility = View.GONE }
            }
        }
    }

    private fun save() {
        val data = resultBytes ?: return
        val ok = SaveUtils.saveVideo(requireContext(), data)
        Toast.makeText(requireContext(), if (ok) "已保存到相册/MagicBeauty" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
