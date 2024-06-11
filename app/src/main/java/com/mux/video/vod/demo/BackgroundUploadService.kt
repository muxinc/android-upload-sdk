package com.mux.video.vod.demo

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadEventListener
import com.mux.video.upload.api.UploadStatus

/**
 * Service that monitors ongoing [MuxUpload]s, showing progress notifications for them. This
 * service will enter the foreground whenever there are uploads in progress and will exit foreground
 * and stop itself when there are no more uploads in progress (ie, all have completed, paused, or
 * failed)
 */
class BackgroundUploadService : Service() {

  companion object {
    private const val TAG = "BackgroundUploadService"

    const val ACTION_START = "start"
    const val NOTIFICATION_PROGRESS = 200001
    const val CHANNEL_UPLOAD_PROGRESS = "upload_progress"
  }

  private var uploadListListener: UploadListListener? = null

  // uploads tracked by this Service, regardless of state. cleared when the service is destroyed
  private val uploadsByFile = mutableMapOf<String, MuxUpload>()

  // todo - Create Notification Channels
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

  private fun notifyWithCurrentUploads() = notify(this.uploadsByFile.values)

  @SuppressLint("InlinedApi") // inline use of FOREGROUND_SERVICE
  private fun notify(uploads: Collection<MuxUpload>) {
    // todo - Manage foreground-iness: startForeground when there are running uploads, else don't
    // todo - Two notification styles: 1 video and many videos
    // todo - notification can't be swiped while in-progress (but provide cancel btns)
    // todo - cancel notification if no uploads are running
    // todo - show new notification for completion (clear count of completed uploads when both notifs are gone)

    // todo - hey wait we don't want to do notifications except by foreground svc

    if (uploads.isEmpty()) {
      // only notify if there are uploads being tracked (in-progress or finished)
      return
    }

    val uploadsInProgress = uploads.filter { it.isRunning }
    val uploadsCompleted = uploads.filter { it.isSuccessful }
//    val uploadsPaused = uploads.filter { it.isPaused }
    val uploadsFailed = uploads.filter { it.error != null }

    Log.d(TAG, "notify: uploadsInProgress: ${uploadsInProgress.size}")
    Log.d(TAG, "notify: uploadsCompleted: ${uploadsCompleted.size}")
//    Log.d(TAG, "notify: uploadsPaused: ${uploadsPaused.size}")
    Log.d(TAG, "notify: uploadsFailed: ${uploadsFailed.size}")

    val builder = NotificationCompat.Builder(this, CHANNEL_UPLOAD_PROGRESS)
    builder.setContentTitle(getString(R.string.app_name))
    builder.setAutoCancel(false)
    builder.setOngoing(true)

    if (uploadsInProgress.isNotEmpty()) {
      if (uploadsInProgress.size == 1 && this.uploadsByFile.size == 1) {
        // Special case: A single upload in progress, with a single upload requested
        val upload = uploadsInProgress.first()
        val kbUploaded = (upload.currentProgress.bytesUploaded / 1024).toInt()
        val kbTotal = (upload.currentProgress.totalBytes / 1024).toInt()

        builder.setProgress(kbUploaded, kbTotal, false)
        builder.setContentText(
          resources.getQuantityString(
            R.plurals.notif_txt_uploading, 1, 1, 1
          )
        )
      } else {
        // Multiple uploads requested simultaneously so we batch them into one
        val totalKbUploaded = uploadsInProgress.sumOf { it.currentProgress.bytesUploaded / 1024 }
        val totalKb = uploadsInProgress.sumOf { it.currentProgress.totalBytes / 1024 }
        builder.setProgress(totalKbUploaded.toInt(), totalKb.toInt(), false)
        builder.setContentText(
          resources.getQuantityString(
            R.plurals.notif_txt_uploading,
            uploadsInProgress.size,
            uploadsInProgress.size + 1,
            uploads.size + 1,
          )
        )
      }
    } else if (uploadsFailed.isNotEmpty()) {
      builder.setContentText(
        resources.getQuantityString(
          R.plurals.notif_txt_failed,
          uploadsFailed.size,
          uploadsFailed.size
        )
      )
    } else if (uploadsCompleted.isNotEmpty()) {
      builder.setContentText(
        resources.getQuantityString(
          R.plurals.notif_txt_failed,
          uploadsFailed.size,
          uploadsFailed.size
        )
      )
    }

    // always startForeground even if we're about to detach (to update the notification)
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_PROGRESS,
      builder.build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    )

    if (uploadsInProgress.isEmpty()) {
      // we only need foreground while uploads are actually running
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }
  }

  private fun updateCurrentUploads(uploads: List<MuxUpload>) {
    this.uploadsByFile.values.forEach { it.clearListeners() }
    uploads.forEach {
      this.uploadsByFile[it.videoFile.path] = it
      it.setStatusListener(UploadStatusListener())
    }
  }

  private inner class UploadListListener : UploadEventListener<List<MuxUpload>> {
    override fun onEvent(event: List<MuxUpload>) {
      val service = this@BackgroundUploadService
      service.updateCurrentUploads(event)
      service.notifyWithCurrentUploads()
    }
  }

  private inner class UploadStatusListener : UploadEventListener<UploadStatus> {
    override fun onEvent(event: UploadStatus) {
      val service = this@BackgroundUploadService
      service.notifyWithCurrentUploads()
    }
  }

  private inner class MyBinder : Binder() {
    fun getService(): BackgroundUploadService = this@BackgroundUploadService
  }
}