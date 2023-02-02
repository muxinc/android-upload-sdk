package com.mux.video.vod.demo

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding

class DeviceStoreVideosActivity : AppCompatActivity() {

  private lateinit var binding: ActivityVideoListBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityVideoListBinding.inflate(layoutInflater)
    setContentView(binding.root)

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
        .setAction("Action", null).show()
    }
  }
}