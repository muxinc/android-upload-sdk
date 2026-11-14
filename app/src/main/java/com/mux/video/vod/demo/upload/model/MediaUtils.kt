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
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(videoFile.absolutePath)
        val frameTime =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLong()
            ?.div(2)
            ?: 0
        retriever.getFrameAtTime(frameTime * 1000)
      } finally {
        retriever.release()
      }
    } catch (e: Exception) {
      Log.d("CreateUploadViewModel", "Error getting thumb bitmap")
      null
    }
  } // val thumbnailBitmap = ...
  return thumbnailBitmap
}
