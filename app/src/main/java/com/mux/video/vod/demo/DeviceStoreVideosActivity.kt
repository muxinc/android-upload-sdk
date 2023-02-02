package com.mux.video.vod.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding

class DeviceStoreVideosActivity : AppCompatActivity() {

  private lateinit var binding: ActivityVideoListBinding
  private val viewModel by viewModels<DeviceStoreVideosViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoListBinding.inflate(layoutInflater)
    setContentView(binding.root)

    viewModel.videoList.observe(this) { list ->
      list.forEach { item ->
        Log.v(javaClass.simpleName, "Video item $item")
      }
    }
    viewModel.refresh()

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
        .setAction("Action", null).show()
    }
  }
}