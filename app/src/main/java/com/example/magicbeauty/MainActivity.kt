package com.example.magicbeauty

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.example.magicbeauty.databinding.ActivityMainBinding
import com.example.magicbeauty.ui.HairFragment
import com.example.magicbeauty.ui.PhotoEditorFragment
import com.example.magicbeauty.ui.SettingsFragment
import com.example.magicbeauty.ui.VideoSlimFragment
import com.google.android.material.navigation.NavigationView

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

        // Android 15 起强制全屏（edge-to-edge）：不加这层，标题会顶进状态栏、底部内容会被手势条切掉
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        // 侧边抽屉：取代原来占一整条的底部导航，把高度还给图片和工具
        binding.navView.setNavigationItemSelectedListener { item ->
            if (!suppressNav) {
                when (item.itemId) {
                    R.id.nav_photo -> show(PhotoEditorFragment())
                    R.id.nav_video -> show(VideoSlimFragment())
                    R.id.nav_hair -> show(HairFragment())
                    R.id.nav_settings -> show(SettingsFragment())
                }
                suppressNav = true
                item.isChecked = true
                suppressNav = false
            }
            binding.drawer.closeDrawers()
            true
        }

        if (savedInstanceState == null) {
            show(PhotoEditorFragment())
            selectNavSilently(R.id.nav_photo)
        } else {
            current = supportFragmentManager.fragments.firstOrNull { !it.isHidden }
            if (current == null) {
                // 极端情况（进程被回收后恢复不到 Fragment）：兜底回照片页
                show(PhotoEditorFragment())
                selectNavSilently(R.id.nav_photo)
            } else {
                val id = when (current) {
                    is VideoSlimFragment -> R.id.nav_video
                    is HairFragment -> R.id.nav_hair
                    is SettingsFragment -> R.id.nav_settings
                    else -> R.id.nav_photo
                }
                selectNavSilently(id)
            }
        }

        requestLegacyStorageIfNeeded()
    }

    /** 供各页面左上角的「≡」按钮调用 */
    fun openDrawer() {
        binding.drawer.openDrawer(GravityCompat.START)
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
        // 每个页面左上角都有一个 btnMenu，统一在这里挂上开抽屉的动作
        target.view?.findViewById<View>(R.id.btnMenu)?.setOnClickListener { openDrawer() }
    }

    private fun selectNavSilently(id: Int) {
        suppressNav = true
        binding.navView.setCheckedItem(id)
        suppressNav = false
    }

    override fun onBackPressed() {
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawers()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
