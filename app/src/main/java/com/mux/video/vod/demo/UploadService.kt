package com.mux.video.vod.demo

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import java.io.File

class UploadService : Service() {

  companion object {
    const val ACTION_START_UPLOAD = "start upload"
  }

  // todo - should we notify for every single one? I say aggregate them (n videos uploading)

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action == ACTION_START_UPLOAD) {
    } else {
      throw RuntimeException("Unknown action")
    }

    // todo - find upload by file from intent
    // todo - start if necessary?
    // todo - listen for updates
    // todo - foreground service
    // todo - stop listening on destroy
    return super.onStartCommand(intent, flags, startId)
  }

  override fun onBind(intent: Intent?): IBinder? {
    return MyBinder()
  }

  private fun observeUpload(upload: MuxUpload) {

  }

  inner class MyBinder : Binder() {
    fun getService(): UploadService = this@UploadService
  }
}