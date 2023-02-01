package com.mux.video.vod_ingest.api

import android.net.Uri
import androidx.annotation.MainThread
import com.mux.android.util.weak
import java.io.File

/**
 * Represents a task that does a single direct upload to a Mux Video asset previously created.
 *
 * TODO: Talk about creating the upload: https://docs.mux.com/api-reference/video#operation/create-direct-upload
 *
 * This prototype does a single streamed PUT request for the whole file and delivers results, but
 * the production version will have more sophisticated behavior.
 *
 * Create an instance of this class with the [Builder]
 */
class MuxVodUpload private constructor(
  private val putUri: Uri,
  private val srcFile: File,
  private val chunkSizeBytes: Int,
  private val retriesPerChunk: Int,
) {

  private val successCallbacks: MutableList<Callback<UploadState>> = mutableListOf()
  private val failureCallbacks: MutableList<Callback<Exception>> = mutableListOf()
  private val progressCallbacks: MutableList<Callback<UploadState>> = mutableListOf()

  /**
   * Starts this Upload. The Upload will continue in the background *even if this object is
   * destroyed*.
   * To suspend the execution of the upload, use [pause]. To cancel it completely, use [cancel]
   *
   * @param forceRestart Start the upload from the beginning even if the file is partially uploaded
   */
  @JvmOverloads
  fun start(forceRestart: Boolean = false) {
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    MuxVodUploadManager.jobStarted(this)
    // TODO: Add a WeakCallback that delegates to our callbacks here
  }

  fun pause() {
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    MuxVodUploadManager.jobFinished(this)
  }

  fun cancel() {
    // TODO: Return/track a Job (or something) for callers to track this progress themselves.
    MuxVodUploadManager.jobCanceled(this)
  }

  @MainThread
  fun addSuccessCallback(cb: Callback<UploadState>) {
    successCallbacks += cb
  }

  @MainThread
  fun removeSuccessCallback(cb: Callback<UploadState>) {
    successCallbacks -= cb
  }

  @MainThread
  fun addFailureCallback(cb: Callback<Exception>) {
    failureCallbacks += cb
  }

  @MainThread
  fun removeFailureCallback(cb: Callback<Exception>) {
    failureCallbacks -= cb
  }

  @MainThread
  fun addProgressCallback(cb: Callback<UploadState>) {
    progressCallbacks += cb
  }

  @MainThread
  fun removeProgressCallback(cb: Callback<UploadState>) {
    progressCallbacks -= cb
  }

  data class UploadState(
    val bytesUploaded: Long,
    val totalBytes: Long,
  )

  /**
   * Represents Callbacks from this object.
   */
  interface Callback<T> {
    // We could use blocks instead, but blocks are baroque on Java 1.6 b/c there's no lambdas
    /**
     * Implement to handle the callback.
     */
    @Throws
    fun invoke(t: T)
  }

  /**
   * Builds instances of this object
   *
   * @param uploadUri the URL obtained from the Direct video up
   */
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {

    constructor(uploadUri: String, videoFile: File) : this(Uri.parse(uploadUri), videoFile)

    fun chunkSize(sizeBytes: Int) {

    }

    fun retriesPerChunk(retries: Int) {

    }

    fun build() = MuxVodUpload(
      putUri = uploadUri,
      srcFile = videoFile,
      chunkSizeBytes = 0/*TODO*/,
      retriesPerChunk = 0/*TODO*/
    )
  }
}

// Callback that doesn't hold strong references to the outer context. If the Context is valid,
// something else will be holding a reference, so it'll stay valid as long as the caller cares
private class WeakCallback<T>(cb: MuxVodUpload.Callback<T>) : MuxVodUpload.Callback<T> {
  private val weakCb by weak(cb)
  @Throws
  override fun invoke(t: T) = weakCb?.invoke(t) ?: Unit
}
