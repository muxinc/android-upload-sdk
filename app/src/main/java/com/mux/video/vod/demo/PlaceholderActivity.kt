package com.mux.video.vod.demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.mux.video.upload.api.MuxUploadManager

class PlaceholderActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_placeholder)
    MuxUploadManager.uploadsInProgress
  }
}