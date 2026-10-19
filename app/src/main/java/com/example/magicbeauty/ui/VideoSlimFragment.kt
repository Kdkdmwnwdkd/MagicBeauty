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
import com.example.magicbeauty.api.ApiConfig
import com.example.magicbeauty.api.BeautyService
import com.example.magicbeauty.databinding.FragmentVideoBinding
import com.example.magicbeauty.engine.LocalVideoSlimEngine
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val strength = binding.etStrength.text.toString().toFloatOrNull() ?: 0.5f
        val waist = strength
        val leg = strength * 0.6f

        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.VISIBLE
                binding.progress.isIndeterminate = true
                binding.btnProcess.isEnabled = false
                binding.tvStatus.text = "处理中（端侧逐帧较慢，请耐心等待）…"
            }
            try {
                val bytes: InputStream? = requireContext().contentResolver.openInputStream(uri)
                val data = bytes?.readBytes() ?: byteArrayOf()
                bytes?.close()

                resultBytes = if (ApiConfig.videoSlimEndpoint.isBlank()) {
                    // 端侧本地处理：纯手机计算，离线免费
                    val engine = LocalVideoSlimEngine(requireContext())
                    engine.process(
                        data, waist, leg,
                        onProgress = { done, total ->
                            runBlocking(Dispatchers.Main) {
                                binding.progress.isIndeterminate = false
                                binding.progress.max = total
                                binding.progress.progress = done
                                binding.tvStatus.text = "端侧处理中 $done/$total"
                            }
                        },
                        onLog = { msg ->
                            runBlocking(Dispatchers.Main) { binding.tvStatus.text = msg }
                        }
                    )
                } else {
                    // 云端处理：走你配置的瘦身终点
                    BeautyService.slimVideo(
                        data, "input.mp4",
                        mapOf("strength" to strength.toString())
                    )
                }

                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "处理完成，可保存到相册"
                    binding.btnSave.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "失败：${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.progress.visibility = View.GONE
                    binding.btnProcess.isEnabled = true
                }
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
