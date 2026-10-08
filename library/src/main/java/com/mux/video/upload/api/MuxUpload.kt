package com.mux.video.upload.api

import android.net.Uri
import androidx.annotation.MainThread
import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload.Builder
import com.mux.video.upload.internal.MaximumResolution
import com.mux.video.upload.internal.UploadInfo
import com.mux.video.upload.internal.UploadSession
import com.mux.video.upload.internal.update
import com.mux.video.upload.internal.writeUploadState
import com.mux.video.upload.internal.forgetUploadState
import com.mux.video.upload.internal.readUploadResumeState
import com.mux.video.upload.internal.UploadCancelledException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
  uploadInfo: UploadInfo,
  private val autoManage: Boolean = true,
) {
  private var uploadInfo: UploadInfo = uploadInfo
    get() = field.session.current.value ?: field
    set(value) { field = value; value.session.current.value = value }

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
  val uploadStatus: UploadStatus get() = uploadInfo.statusFlow?.value ?: currentStatus

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
  val error get() = currentStatus.getError()

  /**
   * True if the upload was successful, false otherwise
   */
  val isSuccessful get() = currentStatus.isSuccessful()

  private var resultListener: UploadEventListener<Result<Progress>>? = null
  private var progressListener: UploadEventListener<Progress>? = null
  private var statusListener: UploadEventListener<UploadStatus>? = null
  private var observerJob: Job? = null
  private var observedSession: UploadSession? = null
  private var cancelled = false
  private var deliveredResultStatus: UploadStatus? = null
  private var deliveredStatus: UploadStatus? = null
  private var deliveredProgress: Progress? = null
  private val currentStatus: UploadStatus get() =
    uploadInfo.statusFlow?.value ?: lastKnownStatus ?: UploadStatus.Ready
  private var lastKnownStatus: UploadStatus? = null
  private val lastKnownProgress: Progress? get() = currentStatus.getProgress()

  private val callbackScope: CoroutineScope = MainScope()
  private val logger get() = MuxUploadSdk.logger

  init {
    if (uploadInfo.session.current.value == null) uploadInfo.session.current.value = uploadInfo
    // Catch state if an upload was already in progress
    // no need to observe: the Flow will have the most-recent values when queried
    uploadInfo.statusFlow?.value?.let { status -> this.lastKnownStatus = status }
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
    if (cancelled || uploadInfo.attempt?.isCancelled() == true || uploadInfo.attempt?.replacementFailure() != null) return
    if (!forceRestart && uploadInfo.uploadJob?.isActive == true && uploadInfo.attempt?.isStopped() != true) {
      observeUpload(uploadInfo)
      return
    }
    // Get an updated UploadInfo with a job & event channels
    uploadInfo = if (autoManage) {
      // We may or may not get a fresh worker, depends on if the upload is already going
      /*uploadInfo =*/ MuxUploadManager.startJob(uploadInfo, forceRestart)
    } else {
      // If we're not managing the worker, the job is purely internal to this object
      if (forceRestart) {
        val blocked = uploadInfo.generatedResumeBlocked || uploadInfo.attempt?.preparation?.generatedRequestStarted == true ||
          readUploadResumeState(uploadInfo).generatedResumeBlocked
        val previous = uploadInfo
        if (previous.attempt == null) forgetUploadState(previous)
        else previous.attempt.cancel { forgetUploadState(previous) }
        previous.uploadJob?.cancel()
        uploadInfo = uploadInfo.update(attempt = null, statusFlow = null,
          restoredFromOriginal = false, generatedResumeBlocked = blocked)
      }
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
   * If the upload already succeeded, the old result will be returned immediately.
   * [cancel] returns a failure to existing waiters and later calls without restarting.
   * [pause] interrupts existing waiters with [CancellationException]; the upload remains resumable.
   * Cancellation of the calling coroutine still throws [CancellationException]. Replacement by
   * a new destination returns a failure to existing waiters and ends this handle.
   */
  @Throws
  @Suppress("unused")
  @JvmSynthetic
  suspend fun awaitSuccess(): Result<UploadStatus> {
    if (cancelled || uploadInfo.attempt?.isCancelled() == true) return Result.failure(UploadCancelledException())
    val status = uploadStatus // base our logic on a stable snapshot of the status
    return if (status is UploadStatus.UploadSuccess) {
      Result.success(status) // If we succeeded already, don't start again
    } else {
      coroutineScope {
        startInner(coroutineScope = this)
        val awaited = uploadInfo
        replacementResult(awaited)?.let { return@coroutineScope it }
        try {
          awaited.uploadJob?.await() ?: Result.failure(Exception("Upload failed to start"))
        } catch (e: CancellationException) {
          // Upload cancellation/replacement must not cancel an active caller.
          currentCoroutineContext().ensureActive()
          replacementResult(awaited) ?: if (cancelled || awaited.attempt?.isCancelled() == true) {
            Result.failure(UploadCancelledException())
          } else throw e
        }
      }
    }
  }

  private fun replacementResult(upload: UploadInfo): Result<UploadStatus>? {
    val replacement = upload.attempt?.replacementFailure() ?: return null
    return when (val status = upload.statusFlow?.value) {
      is UploadStatus.UploadSuccess -> Result.success(status)
      is UploadStatus.UploadFailed -> Result.failure(status.exception)
      else -> Result.failure(replacement)
    }
  }

  /**
   * Pauses the upload. If the upload was already paused, this method has no effect
   *
   * You can resume the upload where it left off by calling [start]
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun pause() {
    if (cancelled || uploadInfo.attempt?.isCancelled() == true || uploadInfo.attempt?.replacementFailure() != null || isPaused) return
    uploadInfo = if (autoManage) {
      /*uploadInfo =*/ MuxUploadManager.pauseJob(uploadInfo)
    } else {
      uploadInfo.attempt?.pause { writeUploadState(uploadInfo, it) }
      uploadInfo.uploadJob?.cancel()
      uploadInfo
    }
    observeUpload(uploadInfo)
  }

  /**
   * Cancels this upload and ends this handle. Later [start] calls have no effect and
   * [awaitSuccess] returns a failure. Create a new handle to start another upload.
   * A destination that received generated bytes requires a new Direct Upload URL.
   */
  @Suppress("MemberVisibilityCanBePrivate")
  fun cancel() {
    if (cancelled) return
    cancelled = true
    if (autoManage) {
      MuxUploadManager.cancelJob(uploadInfo)
    } else {
      val current = uploadInfo
      current.attempt?.cancel { forgetUploadState(current) }
      current.uploadJob?.cancel("user requested cancel")
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
    if (progressListener !== listener) deliveredProgress = null
    progressListener = listener
    observeUpload(uploadInfo)
    if (!cancelled && uploadInfo.attempt?.isCancelled() != true) lastKnownProgress?.let { notifyProgress(it) }
  }

  /**
   * Sets a listener for success or failure updates on this upload
   *
   * @see setStatusListener
   */
  @MainThread
  fun setResultListener(listener: UploadEventListener<Result<Progress>>?) {
    if (resultListener !== listener) deliveredResultStatus = null
    resultListener = listener
    observeUpload(uploadInfo)
    notifyResult(currentStatus)
  }

  /**
   * Set a listener for the overall status of this upload.
   *
   * @see UploadStatus
   */
  @MainThread
  fun setStatusListener(listener: UploadEventListener<UploadStatus>?) {
    if (statusListener !== listener) deliveredStatus = null
    statusListener = listener
    observeUpload(uploadInfo)
    if (!cancelled && uploadInfo.attempt?.isCancelled() != true) notifyStatus(currentStatus)
  }

  /**
   * Clears all listeners set on this object
   */
  @Suppress("unused")
  @MainThread
  fun clearListeners() {
    observerJob?.cancel("clearing listeners")
    resultListener = null
    progressListener = null
    statusListener = null
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun newObserveProgressJob(upload: UploadInfo): Job = callbackScope.launch {
    upload.session.current.filterNotNull().flatMapLatest { observed ->
      (observed.statusFlow ?: flowOf(UploadStatus.Ready)).map { observed to it }
    }.collect { (observed, status) ->
      if (cancelled || uploadInfo.attempt !== observed.attempt || observed.attempt?.isCancelled() == true) return@collect
      lastKnownStatus = status
      notifyStatus(status)
      // A listener can synchronously pause, cancel, or replace the attempt.
      if (cancelled || uploadInfo.attempt !== observed.attempt || observed.attempt?.isCancelled() == true ||
        (observed.attempt?.isStopped() == true && status !is UploadStatus.UploadPaused && status !is UploadStatus.UploadFailed && status !is UploadStatus.UploadSuccess)) return@collect
      when (status) {
        is UploadStatus.Uploading -> notifyProgress(status.uploadProgress)
        is UploadStatus.UploadPaused -> notifyProgress(status.uploadProgress)
        is UploadStatus.UploadSuccess -> {
          notifyProgress(status.uploadProgress)
          if (!cancelled && observed.attempt?.isCancelled() != true && uploadInfo.attempt === observed.attempt) notifyResult(status)
        }
        is UploadStatus.UploadFailed -> {
          notifyProgress(status.uploadProgress)
          if (observed.attempt?.isCancelled() != true && uploadInfo.attempt === observed.attempt) notifyResult(status)
        }
        else -> {}
      }
    }
  }

  private fun notifyStatus(status: UploadStatus) {
    val listener = statusListener ?: return
    if (deliveredStatus === status) return
    deliveredStatus = status
    listener.onEvent(status)
  }

  private fun notifyProgress(progress: Progress) {
    val listener = progressListener ?: return
    if (deliveredProgress == progress) return
    deliveredProgress = progress
    listener.onEvent(progress)
  }

  private fun notifyResult(status: UploadStatus) {
    if (cancelled || uploadInfo.attempt?.isCancelled() == true || resultListener == null || deliveredResultStatus === status) return
    when (status) {
      is UploadStatus.UploadSuccess -> {
        deliveredResultStatus = status
        resultListener?.onEvent(Result.success(status.uploadProgress))
      }
      is UploadStatus.UploadFailed -> if (status.exception !is CancellationException) {
        deliveredResultStatus = status
        resultListener?.onEvent(Result.failure(status.exception))
      }
      else -> {}
    }
  }

  private fun observeUpload(uploadInfo: UploadInfo) {
    if (cancelled || (resultListener == null && progressListener == null && statusListener == null)) {
      observerJob?.cancel("no listeners")
      observerJob = null
    } else if (observerJob?.isActive != true || observedSession !== uploadInfo.session) {
      observerJob?.cancel("switching upload session")
      observedSession = uploadInfo.session
      observerJob = newObserveProgressJob(uploadInfo)
    }
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
  class Builder(val uploadUri: Uri, val videoFile: File) {

    /**
     * Create a new Builder with the specified input file and upload URL
     *
     * @param uploadUri the URL obtained from the Direct video up
     * @param videoFile a File that represents the video file you want to upload
     */
    @Suppress("unused")
    constructor(uploadUri: String, videoFile: File): this(Uri.parse(uploadUri), videoFile)

    private var manageTask: Boolean = true
    private var uploadInfo: UploadInfo = UploadInfo(
      // Default values
      remoteUri = uploadUri,
      inputFile = videoFile,
      chunkSize = 8 * 1024 * 1024, // GCP recommends at least 8M chunk size
      retriesPerChunk = 3,
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
     * for use with Mux Video. These are requested on-device output limits; if safe local
     * conversion is unavailable, the original file is uploaded. Configure the matching Direct Upload
     * `new_asset_settings.max_resolution_tier` separately.
     */
    @Suppress("unused")
    fun standardizationRequested(enabled: Boolean, maxResolution: MaximumResolution): Builder {
      uploadInfo = uploadInfo.update(inputStandardization = uploadInfo.inputStandardization.copy(
        standardizationRequested = enabled,
        maximumResolution = maxResolution,
      ))
      return this
    }

    /**
     * If requested, the Upload SDK will try to standardize the input file in order to optimize it
     * for use with Mux Video
     */
    @Suppress("unused")
    fun standardizationRequested(enabled: Boolean): Builder {
      uploadInfo = uploadInfo.update(inputStandardization = uploadInfo.inputStandardization.copy(
        standardizationRequested = enabled,
      ))
      return this
    }

    /**
     * Store the requested HDR behavior. Defaults to [HdrHandling.Preserve].
     * Unsupported local HDR conversion falls back to uploading the original file.
     */
    fun hdrHandling(handling: HdrHandling): Builder {
      uploadInfo = uploadInfo.update(inputStandardization = uploadInfo.inputStandardization.copy(
        hdrHandling = handling,
      ))
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
    fun build() = MuxUpload(uploadInfo, manageTask)
  }

  internal companion object {
    /**
     * Internal constructor-like method for creating instances of this class from the
     * [MuxUploadManager]
     */
    @JvmSynthetic
    internal fun create(uploadInfo: UploadInfo) = MuxUpload(uploadInfo = uploadInfo)
  }
}
