package com.mux.video.upload.api

import androidx.annotation.MainThread
import com.mux.video.upload.internal.MuxUploadInfo
import com.mux.video.upload.internal.assertMainThread
import java.io.File

object MuxVodUploadManager {

  // TODO: The production version will keep a persistent cache of
  private val uploadsByFilename: MutableMap<String, MuxUploadInfo> = mutableMapOf()

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
  internal fun registerJob(upload: MuxUploadInfo) {
    assertMainThread()
    upsertUpload(upload)
  }

  @JvmSynthetic
  @MainThread
  internal fun cancelJob(upload: MuxUploadInfo) {
    assertMainThread()
    uploadsByFilename -= upload.file.absolutePath
  }

  @JvmSynthetic
  @MainThread
  internal fun jobFinished(upload: MuxUploadInfo) {
    assertMainThread()
    uploadsByFilename -= upload.file.absolutePath
  }

  private fun upsertUpload(upload: MuxUploadInfo) {
    val filename = upload.file.absoluteFile
    uploadsByFilename += upload.file.absolutePath to upload
  }
}
