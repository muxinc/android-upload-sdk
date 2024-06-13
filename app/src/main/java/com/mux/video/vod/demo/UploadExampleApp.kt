package com.mux.video.vod.demo

import android.app.Application
import com.mux.video.upload.MuxUploadSdk

class UploadExampleApp : Application() {
  override fun onCreate() {
    super.onCreate()
    MuxUploadSdk.initialize(this)
  }
}
