package com.mux.video.vod.demo.upload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mux.video.vod.demo.upload.screen.CreateUploadScreen
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme

class CreateUploadActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MuxUploadSDKForAndroidTheme { CreateUploadScreen() } }
  }
}
