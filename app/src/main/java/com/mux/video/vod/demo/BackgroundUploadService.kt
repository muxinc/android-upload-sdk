package com.mux.video.vod.demo

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.UploadEventListener
import com.mux.video.upload.api.UploadStatus

class BackgroundUploadService : Service() {

  companion object {
    const val ACTION_START = "start"
  }

  private var uploads = listOf<MuxUpload>()
  private val statusListenersByFile = mutableMapOf<String, UploadListListener>()

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action == ACTION_START) {
    } else {
      throw RuntimeException("Unknown action")
    }
    // todo - listen for updates
    // todo - foreground service
    // todo - stop listening on destroy
    return super.onStartCommand(intent, flags, startId)
  }

  override fun onBind(intent: Intent?): IBinder? {
    return MyBinder()
  }

  private fun notify(uploads: List<MuxUpload>) {

  }

  private fun observeUpload(upload: MuxUpload) {

  }

  private fun updateUploadList(uploads: List<MuxUpload>) {
    this.uploads.forEach { it.setProgressListener(null) }

    val currentUploads = uploads.union(this.uploads)
    this.uploads = uploads
  }

  private inner class UploadListListener: UploadEventListener<List<MuxUpload>> {
    override fun onEvent(event: List<MuxUpload>) {
      val service = this@BackgroundUploadService
      service.updateUploadList(event)
    }
  }

  private inner class MyBinder : Binder() {
    fun getService(): BackgroundUploadService = this@BackgroundUploadService
  }
}