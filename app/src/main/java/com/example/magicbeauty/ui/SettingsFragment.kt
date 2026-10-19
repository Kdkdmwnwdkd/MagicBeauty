package com.example.magicbeauty.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.magicbeauty.Prefs
import com.example.magicbeauty.databinding.FragmentSettingsBinding

/**
 * 设置页：在手机上直接填写硅基流动 Key（画发丝用）与可选视频瘦身云端终点。
 * 无需电脑、无需改代码、无需重新编译。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.etSfKey.setText(Prefs.siliconFlowKey)
        binding.etVsEndpoint.setText(Prefs.videoSlimEndpoint)
        binding.btnSave.setOnClickListener {
            Prefs.siliconFlowKey = binding.etSfKey.text.toString()
            Prefs.videoSlimEndpoint = binding.etVsEndpoint.text.toString()
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
