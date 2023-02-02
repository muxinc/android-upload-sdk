package com.mux.video.upload.api

import androidx.annotation.MainThread
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.assertMainThread
import com.mux.video.upload.internal.createUploadJob
import java.io.File

object MuxVodUploadManager {

  // TODO: The production version will keep a persistent cache of
  private val uploadsByFilename: MutableMap<String, UploadInfo> = mutableMapOf()

  /**
   * Finds an in-progress (or recently-failed) upload and returns an object to track it, if it was
   * in progress
   */
  fun findUploadByFile(videoFile: File): MuxVodUpload? =
    uploadsByFilename[videoFile.absolutePath]?.let { MuxVodUpload.create(it) }

  /**
   * Adds a new job to this manager.
   * If it's not started, it will be started
   * If it is started, it will be restarted with new parameters
   */
  @JvmSynthetic
  @MainThread
  internal fun startJob(upload: UploadInfo, restart: Boolean = false) {
    assertMainThread()
    upsertUpload(upload, restart)
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

  private fun createJob() {}

  private fun cancelJobInner(upload: UploadInfo) {
    upload.uploadJob?.cancel()
  }

  private fun upsertUpload(upload: UploadInfo, restart: Boolean) {
    val filename = upload.file.absolutePath
    var existingUpload = uploadsByFilename[filename]
    // Use the old job if possible (unless requested otherwise)
    if (existingUpload?.uploadJob == null) {
      existingUpload = createUploadJob(upload)
    } else {
      if (restart) {
        cancelJobInner(upload)
        existingUpload = createUploadJob(upload)
      }
    }

    // TODO: New UploadInfo with job (or same old one)
    uploadsByFilename += upload.file.absolutePath to existingUpload
  }
}
