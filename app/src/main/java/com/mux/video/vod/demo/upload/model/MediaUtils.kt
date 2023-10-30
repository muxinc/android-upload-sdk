package com.mux.video.vod.demo.upload.model

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

suspend fun extractThumbnail(videoFile: File): Bitmap? {
  val thumbnailBitmap = withContext(Dispatchers.IO) {
    try {
      MediaMetadataRetriever().use {
        it.setDataSource(videoFile.absolutePath)
        val frameTime =
          it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLong()
            ?.div(2)
            ?: 0
        it.getFrameAtTime(frameTime * 1000)
      }
    } catch (e: Exception) {
      Log.d("CreateUploadViewModel", "Error getting thumb bitmap")
      null
    }
  } // val thumbnailBitmap = ...
  return thumbnailBitmap
}