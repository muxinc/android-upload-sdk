package com.mux.video.vod.demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.mux.video.vod_ingest.api.MuxVodUpload
import com.mux.video.vod_ingest.api.MuxVodUploadManager

class PlaceholderActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_placeholder)
    MuxVodUploadManager.uploadsInProgress
  }
}