package com.mux.video.vod.demo.upload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mux.video.vod.demo.upload.screen.UploadListScreen
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme

class UploadListActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MuxUploadSDKForAndroidTheme { UploadListScreen() } }
  }
}