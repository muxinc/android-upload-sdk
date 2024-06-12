package com.mux.video.vod.demo

import android.annotation.TargetApi
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUploadManager

class UploadExampleApp : Application() {

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
     createNotificationChannel()
    }
    MuxUploadSdk.initialize(this)
    if (MuxUploadManager.allUploadJobs().isNotEmpty()) {
      val startIntent = Intent(this, UploadNotificationService::class.java)
      startIntent.action = UploadNotificationService.ACTION_START
      startService(startIntent)
    }
  }

  @TargetApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      UploadNotificationService.CHANNEL_UPLOAD_PROGRESS,
      getString(R.string.notif_channel_name),
      NotificationManager.IMPORTANCE_LOW
    )
    channel.description = getString(R.string.notif_channel_desc)
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }
}
