package com.example.magicbeauty

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.magicbeauty.databinding.ActivityMainBinding
import com.example.magicbeauty.ui.HairFragment
import com.example.magicbeauty.ui.PhotoEditorFragment
import com.example.magicbeauty.ui.VideoSlimFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) switchTo(PhotoEditorFragment(), R.id.nav_photo)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_photo -> switchTo(PhotoEditorFragment(), R.id.nav_photo)
                R.id.nav_video -> switchTo(VideoSlimFragment(), R.id.nav_video)
                R.id.nav_hair -> switchTo(HairFragment(), R.id.nav_hair)
                else -> false
            }
        }
    }

    private fun switchTo(fragment: Fragment, id: Int): Boolean {
        val tag = fragment.javaClass.simpleName
        if (supportFragmentManager.findFragmentByTag(tag)?.isVisible == true) return true
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment, tag)
            .commit()
        binding.bottomNav.selectedItemId = id
        return true
    }
}
