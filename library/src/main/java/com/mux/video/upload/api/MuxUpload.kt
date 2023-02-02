package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import com.mux.android.util.weak
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
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
class MuxUpload private constructor(uploadInfo: UploadInfo) {
  private var uploadInfo: UploadInfo
  private val successCallbacks: MutableList<Callback<State>> = mutableListOf()
  private val failureCallbacks: MutableList<Callback<Exception>> = mutableListOf()
  private val progressCallbacks: MutableList<Callback<State>> = mutableListOf()

  private val mainScope: CoroutineScope = MainScope()
  private val logger get() = MuxUploadSdk.logger

  init {
    this.uploadInfo = uploadInfo
    uploadInfo.successChannel?.let { consumeChannel(it, successCallbacks) }
    uploadInfo.progressChannel?.let { consumeChannel(it, progressCallbacks) }
    uploadInfo.errorChannel?.let { consumeChannel(it, failureCallbacks) }
  }

  /**
   * Starts this Upload. The Upload will continue in the background *even if this object is
   * destroyed*.
   * To suspend the execution of the upload, use [pause]. To cancel it completely, use [cancel]
   *
   * @param forceRestart Start the upload from the beginning even if the file is partially uploaded
   */
  @JvmOverloads
  fun start(forceRestart: Boolean = false) {
    // We may or may not get a fresh worker, depends on if the upload is already going
    uploadInfo = MuxUploadManager.startJob(uploadInfo, forceRestart)
    logger.i("MuxUpload", "started upload: ${uploadInfo.file}")

    uploadInfo.successChannel?.let { consumeChannel(it, successCallbacks) }
    uploadInfo.progressChannel?.let { consumeChannel(it, progressCallbacks) }
    uploadInfo.errorChannel?.let { consumeChannel(it, failureCallbacks) }
  }

  @Throws
  suspend fun awaitSuccess(): State  {
    start()
    return uploadInfo.uploadJob?.let { job ->
      val result = job.await()
      result.exceptionOrNull()?.let { throw it }
      result.getOrThrow()
    } ?: State(0, uploadInfo.file.length())
  }

  fun pause() {
    MuxUploadSdk.logger.w("MuxUpload", "pause() is not implemented yet")
  }

  fun cancel() {
    MuxUploadManager.cancelJob(uploadInfo)
    mainScope.cancel("user requested cancel")
  }

  @MainThread
  fun addSuccessCallback(cb: Callback<State>) {
    successCallbacks += cb
  }

  @MainThread
  fun removeSuccessCallback(cb: Callback<State>) {
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
  fun addProgressCallback(cb: Callback<State>) {
    progressCallbacks += cb
  }

  @MainThread
  fun removeProgressCallback(cb: Callback<State>) {
    progressCallbacks -= cb
  }

  // Consumes a channel until it closes, or until mainScope is canceled
  private fun <T> consumeChannel(channel: Channel<T>, callbacks: List<Callback<T>>) {
    mainScope.launch {
      channel.receiveAsFlow().collect { t ->
        logger.d("MuxUpload", "Flow: Updated $t")
        callbacks.forEach { it.invoke(t) }
      }
    }
  }

  data class State(
    val bytesUploaded: Long,
    val totalBytes: Long,
  )

  /**
   * Represents Callbacks from this object.
   */
  interface Callback<T> {
    // We could use blocks instead, but blocks are baroque on Java 1.6 with no lambdas
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
  @Suppress("MemberVisibilityCanBePrivate")
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {
    constructor(uploadUri: String, videoFile: File) : this(Uri.parse(uploadUri), videoFile)

    private var uploadInfo: UploadInfo = UploadInfo(
      // Default values
      remoteUri = uploadUri,
      file = videoFile,
      videoMimeType = "application/mp4",
      chunkSize = 32000 * 1024, //32M or so
      retriesPerChunk = 3,
      retryBaseTimeMs = 500,
      uploadJob = null,
      successChannel = null,
      progressChannel = null,
      errorChannel = null
    )

    fun chunkSize(sizeBytes: Long): Builder {
      uploadInfo.update(chunkSize = sizeBytes)
      return this
    }

    fun retriesPerChunk(retries: Int): Builder {
      uploadInfo.update(retriesPerChunk = retries)
      return this
    }

    fun backoffBaseTime(backoffTimeMillis: Long): Builder {
      uploadInfo.update(retryBaseTimeMs = backoffTimeMillis)
      return this
    }

    fun build() = MuxUpload(uploadInfo)
  }

  companion object {
    @JvmSynthetic
    internal fun create(uploadInfo: UploadInfo) = MuxUpload(uploadInfo)
  }
}
