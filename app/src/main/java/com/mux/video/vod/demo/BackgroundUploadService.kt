package com.mux.video.vod.demo

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadEventListener
import com.mux.video.upload.api.UploadStatus

class BackgroundUploadService : Service() {

  companion object {
    const val ACTION_START = "start"
    const val NOTIFICATION_ID = 2001
  }

  private var uploads = listOf<MuxUpload>()
  private var uploadListListener: UploadListListener? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val action = intent?.action
    if (action == ACTION_START) {
    } else {
      throw RuntimeException("Unknown action")
    }

    // can be commanded to start arbitrary number of times
    if (uploadListListener == null) {
      val lis = UploadListListener()
      this.uploadListListener = lis
      MuxUploadManager.addUploadsUpdatedListener(lis)
    }

    return super.onStartCommand(intent, flags, startId)
  }

  override fun onBind(intent: Intent?): IBinder? {
    return MyBinder()
  }

  override fun onDestroy() {
    uploadListListener?.let { MuxUploadManager.removeUploadsUpdatedListener(it) }
  }

  private fun notify(uploads: List<MuxUpload>) {
    // todo - Manage foreground-iness: startForeground when there are running uploads, else don't
    // todo - Two notification styles: 1 video and many videos
    // todo - notification can't be swiped while in-progress (but provide cancel btns)
    // todo - cancel notification if no uploads are running
    // todo - show new notification for completion (clear count of completed uploads when both notifs are gone)
  }

  private fun updateUploadList(uploads: List<MuxUpload>) {
    this.uploads = uploads.toList()
    notify(uploads)
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