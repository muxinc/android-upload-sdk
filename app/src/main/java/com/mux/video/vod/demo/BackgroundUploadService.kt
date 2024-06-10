package com.mux.video.vod.demo

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    const val ACTION_START = "start"
    const val NOTIFICATION_PROGRESS = 200001
    const val NOTIFICATION_COMPLETE = 200002
    const val NOTIFICATION_RETRY = 200003

    const val CHANNEL_UPLOAD_PROGRESS = "upload_progress"
  }

  private var uploadListListener: UploadListListener? = null

  // todo - Create Notification Channels

  // uploads tracked by this Service, regardless of state. cleared when the service is destroyed
  private val uploadsByFile = mutableMapOf<String, MuxUpload>()

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

  private fun notify(uploads: Collection<MuxUpload>) {
    // todo - Manage foreground-iness: startForeground when there are running uploads, else don't
    // todo - Two notification styles: 1 video and many videos
    // todo - notification can't be swiped while in-progress (but provide cancel btns)
    // todo - cancel notification if no uploads are running
    // todo - show new notification for completion (clear count of completed uploads when both notifs are gone)

    val uploadsInProgress = uploads.filter { it.isRunning }
    val uploadsCompleted = uploads.filter { it.isSuccessful }
    val uploadsPaused = uploads.filter { it.isPaused }
    val uploadsFailed = uploads.filter { it.error != null }

    // todo- notify for each of the above
  }

  private fun updateCurrentUploads(uploads: List<MuxUpload>) {
    this.uploadsByFile.values.forEach { it.clearListeners() }
    uploads.forEach {
      this.uploadsByFile[it.videoFile.path] = it
      it.setStatusListener(UploadStatusListener())
    }
  }

  private fun createPauseNotification(
    uploadsPaused: Collection<MuxUpload>,
    notificationId: Int
  ): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_UPLOAD_PROGRESS)
    if (uploadsPaused.isEmpty()) {
      // If all uploads are finished then we can cancel the notification
    } else if (uploadsPaused.size == 1 && this.uploadsByFile.size == 1) {
      // A single upload in progress, with a single upload requested
      // todo - it's just one so we can make it a little fancy and show a thumbnail
    } else {
      // Multiple uploads requested simultaneously so we batch them into one
    }
    return builder.build()
  }

  private fun createCompletionNotification(
    uploadsComplete: Collection<MuxUpload>,
    notificationId: Int
  ): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_UPLOAD_PROGRESS)

    if (uploadsComplete.isEmpty()) {
      // If all uploads are finished then we can cancel the notification
    } else if (uploadsComplete.size == 1 && this.uploadsByFile.size == 1) {
      // A single upload in progress, with a single upload requested
      //  it's just one so we can make it a little fancy and show a thumbnail
    } else {
      // Multiple uploads requested simultaneously so we batch them into one
    }

    return builder.build()
  }

  private fun createProgressNotification(
    uploadsInProgress: Collection<MuxUpload>,
    notificationId: Int
  ): Notification {
    val builder = NotificationCompat.Builder(this, CHANNEL_UPLOAD_PROGRESS)

    if (uploadsInProgress.isEmpty()) {
      // If all uploads are finished then we can cancel the foreground notification
    } else if (uploadsInProgress.size == 1 && this.uploadsByFile.size == 1) {
      // A single upload in progress, with a single upload requested
      // todo - it's just one so we can make it a little fancy and show a thumbnail
    } else {
      // Multiple uploads requested simultaneously so we batch them into one
    }

    // todo - cancel button

    return builder.build()
  }

  private inner class UploadListListener: UploadEventListener<List<MuxUpload>> {
    override fun onEvent(event: List<MuxUpload>) {
      val service = this@BackgroundUploadService
      service.updateCurrentUploads(event)
      service.notifyWithCurrentUploads()
    }
  }

  private inner class UploadStatusListener: UploadEventListener<UploadStatus> {
    override fun onEvent(event: UploadStatus) {
      val service = this@BackgroundUploadService
      service.notifyWithCurrentUploads()
    }
  }

  private inner class MyBinder : Binder() {
    fun getService(): BackgroundUploadService = this@BackgroundUploadService
  }
}