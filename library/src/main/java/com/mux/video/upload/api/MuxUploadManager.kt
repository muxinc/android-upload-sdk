package com.mux.video.upload.api

import androidx.annotation.MainThread
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.assertMainThread
import com.mux.video.upload.internal.createUploadJob
import java.io.File

object MuxUploadManager {

  // TODO: The production version will keep a persistent cache of
  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()

  /**
   * Finds an in-progress (or recently-failed) upload and returns an object to track it, if it was
   * in progress
   */
  fun findUploadByFile(videoFile: File): MuxUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxUpload.create(it) }

  /**
   * Adds a new job to this manager.
   * If it's not started, it will be started
   * If it is started, it will be restarted with new parameters
   */
  @JvmSynthetic
  @MainThread
  internal fun startJob(upload: UploadInfo, restart: Boolean = false): UploadInfo {
    assertMainThread()
    return upsertUpload(upload, restart)
  }

  @JvmSynthetic
  @MainThread
  internal fun cancelJob(upload: UploadInfo) {
    assertMainThread()
    uploadsByFilename[upload.file.absolutePath]?.let { cancelJobInner(it) }
    uploadsByFilename -= upload.file.absolutePath
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: UploadInfo) {
    assertMainThread()
    uploadsByFilename -= upload.file.absolutePath
  }

  private fun cancelJobInner(upload: UploadInfo) {
    upload.uploadJob?.cancel()
  }

  private fun upsertUpload(upload: UploadInfo, restart: Boolean): UploadInfo {
    val filename = upload.file.absolutePath
    var finalUpload = uploadsByFilename[filename]
    // Use the old job if possible (unless requested otherwise)
    if (finalUpload?.uploadJob == null) {
      finalUpload = createUploadJob(upload)
    } else {
      if (restart) {
        cancelJobInner(upload)
        finalUpload = createUploadJob(upload)
      }
    }
    uploadsByFilename += upload.file.absolutePath to finalUpload
    return finalUpload
  }
}