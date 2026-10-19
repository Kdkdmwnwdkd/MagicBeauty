package com.example.magicbeauty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.magicbeauty.databinding.ActivityMainBinding
import com.example.magicbeauty.ui.HairFragment
import com.example.magicbeauty.ui.PhotoEditorFragment
import com.example.magicbeauty.ui.SettingsFragment
import com.example.magicbeauty.ui.VideoSlimFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var current: Fragment? = null
    private var suppressNav = false

    private val legacyStoragePerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (suppressNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_photo -> { show(PhotoEditorFragment()); true }
                R.id.nav_video -> { show(VideoSlimFragment()); true }
                R.id.nav_hair -> { show(HairFragment()); true }
                R.id.nav_settings -> { show(SettingsFragment()); true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            show(PhotoEditorFragment())
            selectTabSilently(R.id.nav_photo)
        } else {
            current = supportFragmentManager.fragments.firstOrNull { !it.isHidden }
            val id = when (current) {
                is VideoSlimFragment -> R.id.nav_video
                is HairFragment -> R.id.nav_hair
                is SettingsFragment -> R.id.nav_settings
                else -> R.id.nav_photo
            }
            selectTabSilently(id)
        }

        requestLegacyStorageIfNeeded()
    }

    /**
     * 用 add/hide/show 而不是 replace：replace 会把旧 Fragment 销毁，
     * 切回来就是全新的空白页（选好的图、调好的参数全丢）。
     */
    private fun show(fragment: Fragment) {
        val fm = supportFragmentManager
        val tag = fragment.javaClass.simpleName
        val existing = fm.findFragmentByTag(tag)
        val target = existing ?: fragment
        if (current === target) return
        fm.beginTransaction().apply {
            current?.let { if (it !== target) hide(it) }
            if (existing == null) add(R.id.container, target, tag) else show(target)
        }.commitNow()
        current = target
    }

    private fun selectTabSilently(id: Int) {
        suppressNav = true
        binding.bottomNav.selectedItemId = id
        suppressNav = false
    }

    /** Android 9 及以下保存到相册需要 WRITE_EXTERNAL_STORAGE 的运行时授权。 */
    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) legacyStoragePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
