package com.mux.video.upload.api

/**
 * The current state of the upload. Uploads are first examined, potentially processed, then uploaded
 * to Mux Video.
 */
sealed class UploadStatus {

  // Java Compatibility

  /**
   * The progress, in bytes, of the upload. If the file isn't uploading yet, this will be null.
   *
   * This is provided for java callers. Kotlin callers can use [UploadStatus] as a sealed class as
   * normal
   */
  open fun getProgress(): MuxUpload.Progress? = null

  /**
   * If this upload failed, returns the error that caused the failure
   *
   * This is provided for java callers. Kotlin callers can use [UploadStatus] as a sealed class as
   * normal
   */
  open fun getError(): Exception? = null

  /**
   * Returns whether or not the uplod was successful
   */
  @Suppress("unused")
  fun isSuccessful(): Boolean = this is UploadSuccess

  // Subclasses

  /**
   * This upload hos not been started. It is ready to start by calling [MuxUpload.start]
   */
  object Ready: UploadStatus()

  /**
   * This upload has been started via [MuxUpload.start] but has not yet started processing anything
   */
  object Started: UploadStatus()

  /**
   * This upload is being prepared. If standardization is required, it is done during this step
   *
   * @see MuxUpload.Builder.standardizationRequested
   */
  object Preparing: UploadStatus()

  /**
   * The upload is currently being sent to Mux Video. The progress is available
   */
  class Uploading(val uploadProgress: MuxUpload.Progress): UploadStatus() {
    override fun getProgress(): MuxUpload.Progress = uploadProgress
  }

  /**
   * The upload is currently paused. Part of the video file may have already been uploaded to Mux
   * Video, but no data is currently being sent
   */
  class UploadPaused(val uploadProgress: MuxUpload.Progress): UploadStatus() {
    override fun getProgress(): MuxUpload.Progress = uploadProgress
  }

  /**
   * The upload has failed. Part of the file may have already been uploaded, and this upload can be
   * resumed from this state via [MuxUpload.start]
   */
  class UploadFailed(val exception: Exception, val uploadProgress: MuxUpload.Progress): UploadStatus() {
    override fun getError(): Exception = exception
    override fun getProgress(): MuxUpload.Progress = uploadProgress
  }

  /**
   * The upload succeeded. The file has been uploaded to Mux Video and will be processed shortly
   */
  class UploadSuccess(val uploadProgress: MuxUpload.Progress): UploadStatus() {
    override fun getProgress(): MuxUpload.Progress = uploadProgress
  }
}
