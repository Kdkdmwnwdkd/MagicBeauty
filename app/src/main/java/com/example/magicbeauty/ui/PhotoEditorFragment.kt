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
import com.example.magicbeauty.databinding.FragmentPhotoBinding
import com.example.magicbeauty.engine.EditParams
import com.example.magicbeauty.engine.LocalEditEngine
import com.example.magicbeauty.engine.MediaPipeHelper
import com.example.magicbeauty.engine.WarpGuide
import com.example.magicbeauty.util.SaveUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

class PhotoEditorFragment : Fragment() {

    private var _binding: FragmentPhotoBinding? = null
    private val binding get() = _binding!!

    private val engine = LocalEditEngine()
    private lateinit var mp: MediaPipeHelper
    private val params = EditParams()

    private var sourceBitmap: Bitmap? = null
    private var guide: WarpGuide = WarpGuide()
    private var lastResult: Bitmap? = null
    private var renderJob: Job? = null

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

        // 所有滑块：progress 0..100 -> 参数
        binding.sbBrightness.setOnSeekBarChangeListener(slider { params.brightness = it / 100f * 0.6f - 0.3f })
        binding.sbContrast.setOnSeekBarChangeListener(slider { params.contrast = 0.7f + it / 100f * 0.7f })
        binding.sbSaturation.setOnSeekBarChangeListener(slider { params.saturation = it / 100f * 1.6f })
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

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val stream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            val bmp = BitmapFactory.decodeStream(stream)
            stream?.close()
            if (bmp == null) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), "图片读取失败", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            sourceBitmap = engine.setSource(bmp)
            guide = mp.analyze(sourceBitmap!!)
            withContext(Dispatchers.Main) {
                binding.btnSave.isEnabled = true
                renderPreview()
            }
        }
    }

    private fun renderPreview() {
        val src = sourceBitmap ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch(Dispatchers.IO) {
            val out = engine.render(params, guide)
            lastResult?.recycle()
            lastResult = out
            withContext(Dispatchers.Main) {
                binding.preview.setImageBitmap(out)
            }
        }
    }

    private fun saveCurrent() {
        val bmp = lastResult ?: return
        val ok = SaveUtils.saveBitmap(requireContext(), bmp)
        Toast.makeText(requireContext(), if (ok) "已保存到相册/MagicBeauty" else "保存失败", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
        mp.close()
    }
}
