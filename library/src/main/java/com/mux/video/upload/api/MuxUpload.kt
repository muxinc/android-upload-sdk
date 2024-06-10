package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload.Builder
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.update
import kotlinx.coroutines.*
import java.io.File

/**
 * Represents an upload of a video as a Mux Video asset. In order to use this SDK, you must first
 * create a [direct upload](https://docs.mux.com/guides/video/upload-files-directly) server-side,
 * then return that direct upload PUT URL to your app.
 *
 * Once you have a PUT URL, you can create and [start] your upload using the [Builder]
 *
 * For example:
 * ```
 * // Start a new upload
 * val upload = MuxUpload.Builder(myUploadUrl, myInputFile).build()
 * upload.setResultListener { myHandleResult(it) }
 * upload.setProgressListener { myHandleProgress(it) }
 * upload.start()
 * ```
 *
 * For full documentation on how to configure your upload, see the [Builder]
 *
 * @see Builder
 * @see MuxUploadManager
 */
class MuxUpload private constructor(
  private var uploadInfo: UploadInfo,
  private val autoManage: Boolean = true,
  initialStatus: UploadStatus = UploadStatus.Ready
) {

  /**
   * File containing the video to be uploaded
   */
  val videoFile: File get() = uploadInfo.inputFile

  /**
   * The current state of the upload. To be notified of state updates, you can use
   * [setProgressListener] and [setResultListener]
   */
  val currentProgress: Progress
    get() = lastKnownProgress ?: uploadInfo.statusFlow?.value?.getProgress() ?: Progress(
      totalBytes = videoFile.length()
    )

  /**
   * The current status of this upload.
   *
   * To be notified of status updates (including upload progress), use [setStatusListener]
   */
  @Suppress("MemberVisibilityCanBePrivate")
  val uploadStatus: UploadStatus

  /**
   * True when the upload is running, false if it's paused, failed, or canceled
   */
  val isRunning get() = uploadInfo.isRunning()

  /**
   * True when the upload is paused by [pause], false otherwise
   */
  val isPaused get() = currentStatus is UploadStatus.UploadPaused

  /**
   * If the upload has failed, gets the error associated with the failure
   */
  val error get() = _error ?: uploadInfo.statusFlow?.value?.getError()
  private var _error: Exception? = null

  /**
   * True if the upload was successful, false otherwise
   */
  val isSuccessful get() = uploadInfo.statusFlow?.value?.isSuccessful() ?: _successful
  private var _successful: Boolean = false

  private var resultListener: UploadEventListener<Result<Progress>>? = null
  private var progressListener: UploadEventListener<Progress>? = null
  private var statusListener: UploadEventListener<UploadStatus>? = null
  private var observerJob: Job? = null
  private var currentStatus: UploadStatus = UploadStatus.Ready
  private val lastKnownProgress: Progress? get() = currentStatus.getProgress()

  private val callbackScope: CoroutineScope = MainScope()
  private val logger get() = MuxUploadSdk.logger

  init {
    // Catch Events if an upload was already in progress
    observeUpload(uploadInfo)
  }

  /**
   * Starts this Upload. You don't need to hold onto this object in order for the upload to
   * complete, it will continue in parallel with the rest of your app. You can always get a handle
   * to an ongoing upload by using [MuxUploadManager.findUploadByFile]
   *
   * To suspend the execution of the upload, use [pause]. To cancel it completely, use [cancel]
   *
   * @param forceRestart Start the upload from the beginning even if the file is partially uploaded
   *
   * @see pause
   * @see cancel
   * @see MuxUploadManager
   */
  @JvmOverloads
  fun start(forceRestart: Boolean = false) {
    startInner(forceRestart = forceRestart)
  }

  // Starts in the given coroutine scope.
  // Auto-managed jobs do not honor the coroutineScope param, they are always in the UploadManager's
  //   context
  private fun startInner(
    forceRestart: Boolean = false,
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
  ) {
    // Get an updated UploadInfo with a job & event channels
    uploadInfo = if (autoManage) {
      // We may or may not get a fresh worker, depends on if the upload is already going
      /*uploadInfo =*/ MuxUploadManager.startJob(uploadInfo, forceRestart)
    } else {
      // If we're not managing the worker, the job is purely internal to this object
      /*uploadInfo =*/ MuxUploadSdk.uploadJobFactory().createUploadJob(uploadInfo, coroutineScope)
    }

    logger.i("MuxUpload", "started upload: ${uploadInfo.inputFile}")
    observeUpload(uploadInfo)
  }

  /**
   * If the upload has not succeeded, this function will suspend until the upload completes and
   * return the result
   *
   * If the upload had failed, it will be restarted and this function will suspend until it
   * completes
   *
   * If the upload already succeeded, the old result will be returned immediately
   */
  @Throws
  @Suppress("unused")
  @JvmSynthetic
  suspend fun awaitSuccess(): Result<UploadStatus> {
    val status = uploadStatus // base our logic on a stable snapshot of the status
    return if (status is UploadStatus.UploadSuccess) {
      Result.success(status) // If we succeeded already, don't start again
    } else {
      coroutineScope {
        startInner(coroutineScope = this)
        uploadInfo.uploadJob?.await() ?: Result.failure(Exception("Upload failed to start"))
      }
    }
  }

  /**
   * Pauses the upload. If the upload was already paused, this method has no effect
   *
   * You can resume the upload where it left off by calling [start]
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun pause() {
    uploadInfo = if (autoManage) {
      observerJob?.cancel("user requested pause")
      /*uploadInfo =*/ MuxUploadManager.pauseJob(uploadInfo)
    } else {
      observerJob?.cancel("user requested pause")
      uploadInfo.uploadJob?.cancel()
      /*uploadInfo =*/ uploadInfo.update(
        uploadJob = null,
        statusFlow = null,
      )
    }
    lastKnownProgress?.let { state -> progressListener?.onEvent(state) }
  }

  /**
   * Cancels this upload. The upload job will be canceled and it will not be possible to start this
   * job again where it left off
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun cancel() {
    if (autoManage) {
      MuxUploadManager.cancelJob(uploadInfo)
    } else {
      uploadInfo.uploadJob?.cancel("user requested cancel")
    }
    observerJob?.cancel("user requested cancel")
  }

  /**
   * Sets a listener for progress updates on this upload
   *
   * @see setStatusListener
   */
  @MainThread
  fun setProgressListener(listener: UploadEventListener<Progress>?) {
    progressListener = listener
    lastKnownProgress?.let { listener?.onEvent(it) }
  }

  /**
   * Sets a listener for success or failure updates on this upload
   *
   * @see setStatusListener
   */
  @MainThread
  fun setResultListener(listener: UploadEventListener<Result<Progress>>) {
    resultListener = listener
    lastKnownProgress?.let {
      if (it.bytesUploaded >= it.totalBytes) {
        listener.onEvent(Result.success(it))
      }
    }
  }

  /**
   * Set a listener for the overall status of this upload.
   *
   * @see UploadStatus
   */
  @MainThread
  fun setStatusListener(listener: UploadEventListener<UploadStatus>?) {
    statusListener = listener
    listener?.onEvent(currentStatus)
  }

  /**
   * Clears all listeners set on this object
   */
  @Suppress("unused")
  @MainThread
  fun clearListeners() {
    resultListener = null
    progressListener = null
    statusListener = null
  }

  private fun newObserveProgressJob(upload: UploadInfo): Job {
    // Job that collects and notifies state updates on the main thread (suspending on main is safe)
    return callbackScope.launch {
      upload.statusFlow?.let { flow ->
        launch {
          flow.collect { status ->
            // Update the status of our upload
            currentStatus = status
            statusListener?.onEvent(status)

            // Notify the old listeners
            when (status) {
              is UploadStatus.Uploading -> { progressListener?.onEvent(status.uploadProgress) }
              is UploadStatus.UploadPaused -> { progressListener?.onEvent(status.uploadProgress) }
              is UploadStatus.UploadSuccess -> {
                _successful = true
                progressListener?.onEvent(status.uploadProgress)
                resultListener?.onEvent(Result.success(status.uploadProgress))
              }
              is UploadStatus.UploadFailed -> {
                progressListener?.onEvent(status.uploadProgress) // Make sure we're most up-to-date
                if (status.exception !is CancellationException) {
                  _error = status.exception
                  resultListener?.onEvent(Result.failure(status.exception))
                }
              }
              else -> { } // no relevant info
            }
          }
        }
      }
    }
  }

  private fun observeUpload(uploadInfo: UploadInfo) {
    observerJob?.cancel("switching observers")
    observerJob = newObserveProgressJob(uploadInfo)
  }

  init {
    uploadStatus = initialStatus
  }

  /**
   * The current progress of an upload, in terms of time elapsed and data transmitted
   */
  data class Progress(
    val bytesUploaded: Long = 0,
    val totalBytes: Long = 0,
    val startTime: Long = 0,
    val updatedTime: Long = 0,
  )

  /**
   * Builds instances of [MuxUpload].
   *
   * If you wish for fine-grained control over the upload process, some configuration is available.
   *
   * For example:
   * ```
   * // Adapt to your upload to current network conditions
   * val chunkSize = if (/* onWifi */) {
   *   16 * 1024 * 1024 // 16M, bigger chunks go faster
   * } else {
   *   8 * 1024 * 1024 // 8M, smaller chunks are more reliable
   * }
   *
   * val upload = MuxUpload.Builder(myUploadUrl, myInputFile)
   *   .chunkSize(chunkSize) // Mux's default is 8Mb
   *   .retriesPerChunk(5) // Mux's default is 3
   *   .build()
   * ```
   *
   * @param uploadUri the URL obtained from the Direct video up
   * @param videoFile a File that represents the video file you want to upload
   */
  @Suppress("MemberVisibilityCanBePrivate")
  class Builder constructor(val uploadUri: Uri, val videoFile: File) {

    /**
     * Create a new Builder with the specified input file and upload URL
     *
     * @param uploadUri the URL obtained from the Direct video up
     * @param videoFile a File that represents the video file you want to upload
     */
    @Suppress("unused")
    constructor(uploadUri: String, videoFile: File)
            : this(Uri.parse(uploadUri), videoFile)

    private var manageTask: Boolean = true
    private var uploadInfo: UploadInfo = UploadInfo(
      // Default values
      remoteUri = uploadUri,
      inputFile = videoFile,
      chunkSize = 8 * 1024 * 1024, // GCP recommends at least 8M chunk size
      retriesPerChunk = 3,
      standardizationRequested = true,
      optOut = false,
      uploadJob = null,
      statusFlow = null,
    )
    /**
     * Allow Mux to manage and remember the state of this upload
     */
    @Suppress("unused")
    fun manageUploadTask(autoManage: Boolean): Builder {
      manageTask = autoManage
      return this
    }

    /**
     * If requested, the Upload SDK will try to standardize the input file in order to optimize it
     * for use with Mux Video
     */
    @Suppress("unused")
    fun standardizationRequested(enabled: Boolean): Builder {
      uploadInfo.update(standardizationRequested = enabled)
      return this
    }

    /**
     * The Upload SDK will upload your file in smaller chunks, which can be more reliable in adverse
     * network conditions.
     *
     * @param sizeBytes The chunk size in bytes. Mux's default is 8M
     */
    @Suppress("unused")
    fun chunkSize(sizeBytes: Int): Builder {
      uploadInfo.update(chunkSize = sizeBytes)
      return this
    }

    /**
     * Allows you to opt out of Mux's performance analytics tracking. We track metrics related to
     * the overall performance and reliability of your upload, in order to make our SDK better.
     *
     * If you would perfer not to share this information with us, you may opt out by passing `true`
     * here.
     */
    @Suppress("unused")
    fun optOutOfEventTracking(optOut: Boolean): Builder {
      uploadInfo.update(optOut = optOut)
      return this
    }

    /**
     * The Upload SDK will upload your file in smaller chunks, which can be more reliable in adverse
     * network conditions. Each chunk can be retried individually, up to the given number of times
     *
     * @param retries The number of retries per chunk. Mux's default is 3
     */
    @Suppress("unused")
    fun retriesPerChunk(retries: Int): Builder {
      uploadInfo.update(retriesPerChunk = retries)
      return this
    }

    /**
     * Creates a new [MuxUpload] with the given configuration.
     */
    fun build() = MuxUpload(uploadInfo)
  }

  internal companion object {
    /**
     * Internal constructor-like method for creating instances of this class from the
     * [MuxUploadManager]
     */
    @JvmSynthetic
    internal fun create(uploadInfo: UploadInfo, initialStatus: UploadStatus = UploadStatus.Ready)
      = MuxUpload(uploadInfo = uploadInfo, initialStatus = initialStatus)
  }
}
