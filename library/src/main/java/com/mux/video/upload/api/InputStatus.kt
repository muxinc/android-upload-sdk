package com.mux.video.upload.api

/**
 * The current state of the upload. Uploads are first examined, potentially processed, then uploaded
 * to Mux Video
 */
sealed class InputStatus {

  // Java Compatibility

  /**
   * The progress, in bytes, of the upload. If the file isn't uploading yet, this will be null.
   *
   * This is provided for java callers. Kotlin callers can use [InputStatus] as a sealed class as
   * normal
   */
  open fun getProgress(): MuxUpload.Progress? = null

  /**
   * If this upload failed, returns the error that caused the failure
   *
   * This is provided for java callers. Kotlin callers can use [InputStatus] as a sealed class as
   * normal
   */
  open fun getError(): Exception? = null

  // Subclasses

  /**
   * This upload hos not been started. It is ready to start by calling [MuxUpload.start]
   */
  object READY : InputStatus()

  /**
   * This upload has been started via [MuxUpload.start] but has not yet started processing anything
   */
  object STARTED: InputStatus()

  /**
   * This upload is being prepared. If standardization is required, it is done during this step
   *
   * @see MuxUpload.Builder.standardizationRequested
   */
  object PREPARING: InputStatus()

  /**
   * The upload is currently being sent to Mux Video. The progress is available
   */
  class UPLOADING(val progress: MuxUpload.Progress): InputStatus() {
    override fun getProgress(): MuxUpload.Progress = progress
  }

  /**
   * The upload is currently paused. Part of the video file may have already been uploaded to Mux
   * Video, but no data is currently being sent
   */
  class UPLOAD_PAUSED(val progress: MuxUpload.Progress): InputStatus() {
    override fun getProgress(): MuxUpload.Progress = progress
  }

  /**
   * The upload has failed. Part of the file may have already been uploaded, and this upload can be
   * resumed from this state via [MuxUpload.start]
   */
  class UPLOAD_FAILED(val exception: Exception): InputStatus() {
    override fun getError(): Exception = exception
  }

  /**
   * The upload succeeded. The file has been uploaded to Mux Video and will be processed shortly
   */
  class UPLOAD_SUCCESS(val progress: MuxUpload.Progress): InputStatus() {
    override fun getProgress(): MuxUpload.Progress = progress
  }
}
