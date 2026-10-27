package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.internal.standardization.SdrStandardizedFile
import com.mux.video.upload.internal.standardization.PreparedUpload
import com.mux.video.upload.internal.standardization.UploadPreparation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.io.IOException
import java.util.UUID

@JvmSynthetic
internal fun startUploadJob(upload: UploadInfo, saved: UploadResumeState? = null): UploadInfo = MuxUploadSdk.uploadJobFactory()
  .createUploadJob(upload, CoroutineScope(Dispatchers.Default), saved)

/** Owns payload selection and the transition from cancellable preparation to transport. */
internal class UploadJobFactory internal constructor(
  private val prepare: suspend (UploadInfo) -> PreparedUpload = {
    UploadPreparation().prepare(it, checkNotNull(MuxUploadManager.appContext), standardizedResumeVerified = true)
  },
  val createWorker: (ChunkWorker.Chunk, UploadInfo, MutableSharedFlow<MuxUpload.Progress>) -> ChunkWorker =
    ::createWorkerForSlice,
) {
  companion object {
    @JvmSynthetic internal fun create() = UploadJobFactory()
    private fun createWorkerForSlice(chunk: ChunkWorker.Chunk, uploadInfo: UploadInfo,
      progressFlow: MutableSharedFlow<MuxUpload.Progress>): ChunkWorker =
      ChunkWorker.create(chunk, uploadInfo, "video/*", progressFlow)
  }

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope, resumeState: UploadResumeState? = null): UploadInfo {
    uploadInfo.attempt?.supersede()
    val saved = resumeState ?: readUploadResumeState(uploadInfo)
    val attempt = createUploadAttempt(uploadInfo, saved)
    var runningInfo = uploadInfo.update(inputStandardization = saved.standardizedOptions ?: uploadInfo.inputStandardization,
      attempt = attempt, statusFlow = attempt.status.asStateFlow())
    // Lazy startup lets the returned identity be installed before any completion callback.
    val job = outerScope.async(start = CoroutineStart.LAZY) {
      val startTime = attempt.confirmedProgress().startTime
      val sessionId = UUID.randomUUID().toString()
      val metrics = UploadMetrics.create()
      var retire = false
      try {
        uploadInfo.uploadJob?.join()
        ensureActive()
        writeUploadState(runningInfo, attempt.confirmedProgress())
        val selected = selectPayload(runningInfo, saved)
        ensureActive()
        val fileSize = withContext(Dispatchers.IO) {
          check(selected.isFile && selected.canRead() && selected.length() > 0) { "Upload payload is unreadable or empty" }
          selected.length()
        }
        attempt.selectPayload(fileSize)
        uploadChunks(runningInfo, selected, fileSize)
        ensureActive()
        val final = attempt.confirmedProgress()
        val success = UploadStatus.UploadSuccess(final)
        retire = true
        if (attempt.finish(success)) {
          if (!uploadInfo.optOut) metrics.reportUploadSucceeded(startTime, final.updatedTime, 0, sessionId, uploadInfo)
          withContext(Dispatchers.Main) { MuxUploadManager.jobFinished(runningInfo) }
        }
        Result.success(success)
      } catch (e: CancellationException) {
        // Pause/cancel owns the public transition. Cancellation is never an upload result.
        throw e
      } catch (e: Exception) {
        if (e is UploadResumeBlockedException) retire = true
        val failure = UploadStatus.UploadFailed(e, attempt.confirmedProgress())
        if (attempt.finish(failure)) {
          val category = when (e) {
            is UploadResumeBlockedException -> "StandardizedResumeBlocked"
            is IOException -> "Io"
            is IllegalStateException, is IllegalArgumentException -> "InvalidPayload"
            else -> "Unexpected"
          }
          MuxUploadSdk.logger.e("MuxUpload", "Upload failed: $category")
          if (!uploadInfo.optOut) metrics.reportUploadFailed(startTime, System.currentTimeMillis(), 0,
            "Upload failed: $category", sessionId, uploadInfo)
          withContext(Dispatchers.Main) { MuxUploadManager.jobFinished(runningInfo, false) }
        }
        Result.failure(e)
      } finally {
        withContext(NonCancellable + Dispatchers.IO) {
          try {
            val preparation = attempt.preparation
            val retainForRestart = attempt.isRestarting() && preparation.standardizedState?.payload != null
            if (retire || attempt.isCancelled() && !retainForRestart || attempt.replacementFailure() != null) {
              preparation.deleteOwnedFile()
              UploadPersistence.retireStandardized(runningInfo, block = retire)
            } else preparation.standardizedState?.let { UploadPersistence.writeStandardized(runningInfo, it) }
          } catch (_: Exception) {
            MuxUploadSdk.logger.e("MuxUpload", "Standardized persistence cleanup failed")
          }
        }
      }
    }
    runningInfo = runningInfo.update(uploadJob = job)
    runningInfo.session.current.value = runningInfo
    job.start()
    return runningInfo
  }

  /** A selected original bypasses inspection and conversion on every later attempt. */
  private suspend fun selectPayload(upload: UploadInfo, saved: UploadResumeState): File {
    val attempt = checkNotNull(upload.attempt)
    val state = attempt.preparation
    if (upload.standardizedResumeBlocked || saved.standardizedResumeBlocked) throw UploadResumeBlockedException()
    if (state.originalSelected) return selectOriginal(upload)
    restoreStandardized(upload)
    state.verified?.let { return it.file }
    if (upload.isStandardizationRequested()) {
      val source = withContext(Dispatchers.IO) { PayloadIdentity.capture(upload.inputFile) }
      state.standardizedState = source?.let(::StandardizedResumeState)
      attempt.publish(UploadStatus.Preparing)
      when (val result = prepare(upload)) {
        is PreparedUpload.Standardized -> {
          attempt.retainStandardized(result.output)
          currentCoroutineContext().ensureActive()
          val output = checkNotNull(state.verified)
          if (!withContext(Dispatchers.IO) { output.matchesValidation() }) throw UploadResumeBlockedException()
          val record = checkNotNull(state.standardizedState).copy(phase = PreparationPhase.Validated,
            ownedPath = output.file.absolutePath, payload = output.validatedIdentity)
          state.standardizedState = record
          withContext(Dispatchers.IO) { UploadPersistence.writeStandardized(upload, record) }
          attempt.selectPayload(output.file.length(), 0)
          return output.file
        }
        is PreparedUpload.Original -> MuxUploadSdk.logger.d("MuxUploadPreparation", result.diagnostic.toString())
      }
    }
    return selectOriginal(upload)
  }

  private suspend fun selectOriginal(upload: UploadInfo): File = withContext(Dispatchers.IO) {
    UploadPersistence.selectOriginal(upload)
    checkNotNull(upload.attempt).preparation.apply { originalSelected = true; standardizedState = null }
    upload.inputFile
  }

  /** Incomplete exports sent no bytes. Completed exports keep their exact validated identity. */
  private suspend fun restoreStandardized(upload: UploadInfo) {
    val attempt = checkNotNull(upload.attempt)
    val state = attempt.preparation
    val saved = state.standardizedState ?: return
    if (state.verified == null) saved.ownedPath?.let { path ->
      val output = SdrStandardizedFile.restore(checkNotNull(MuxUploadManager.appContext).cacheDir, path, saved.payload)
        ?: throw UploadResumeBlockedException()
      if (saved.payload != null && !saved.abandoned) state.verified = output else state.trackCleanup(output)
    }
    if (saved.abandoned && saved.networkStarted) throw UploadResumeBlockedException()
    if (saved.payload != null && !saved.abandoned && withContext(Dispatchers.IO) { state.verified?.matchesValidation() == true })
      return
    // Transport fixes the selected bytes. A missing/changed payload must never be replaced then.
    if (saved.networkStarted) throw UploadResumeBlockedException()
    // A completed selection is final, even if its file disappears before the first request.
    if (saved.payload != null && !saved.abandoned) throw UploadResumeBlockedException()
    if (!withContext(Dispatchers.IO) { saved.source.matches(upload.inputFile) }) throw UploadResumeBlockedException()
    withContext(Dispatchers.IO) { state.deleteOwnedFile(); UploadPersistence.retireStandardized(upload) }
    state.standardizedState = null
    attempt.selectPayload(upload.inputFile.length(), 0)
  }

  /** Original and standardized payloads share offsets, acknowledgement checks, and retries. */
  private suspend fun uploadChunks(upload: UploadInfo, selected: File, fileSize: Long) {
    val attempt = checkNotNull(upload.attempt)
    val state = attempt.preparation
    val startTime = attempt.confirmedProgress().startTime
    var offset = attempt.confirmedProgress().bytesUploaded
    check(offset in 0..fileSize) { "Saved offset is outside the payload" }
    withContext(Dispatchers.IO) { RandomAccessFile(selected, "r") }.use { stream ->
      attempt.publish(UploadStatus.Uploading(attempt.confirmedProgress()))
      val buffer = ByteArray(upload.chunkSize)
      var transportSaved = false
      while (offset < fileSize) {
        currentCoroutineContext().ensureActive()
        val count = minOf(buffer.size.toLong(), fileSize - offset).toInt()
        withContext(Dispatchers.IO) {
          stream.seek(offset)
          stream.readFully(buffer, 0, count)
        }
        val chunk = ChunkWorker.Chunk(offset, offset + count - 1, fileSize, count, buffer)
        val progress = MutableSharedFlow<MuxUpload.Progress>(replay = 1,
          extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val chunkStart = offset
        coroutineScope {
          val observer = launch {
            progress.collect { value -> attempt.publish(UploadStatus.Uploading(value.copy(
              bytesUploaded = value.bytesUploaded + chunkStart, totalBytes = fileSize, startTime = startTime))) }
          }
          try {
            if (!transportSaved) {
              state.standardizedState?.let { record ->
                state.standardizedState = record.copy(phase = PreparationPhase.Uploading, networkStarted = true)
                withContext(Dispatchers.IO) { UploadPersistence.writeStandardized(upload, checkNotNull(state.standardizedState)) }
              }
              currentCoroutineContext().ensureActive()
              attempt.beginTransport(state.verified != null) { writeUploadState(upload, attempt.confirmedProgress()) }
              transportSaved = true
            }
            val final = withUploadRetries(upload.retriesPerChunk) {
              withContext(Dispatchers.IO) {
                check(selected.isFile && selected.canRead() && selected.length() == fileSize &&
                  state.verified?.matchesValidation() != false) { "Upload payload changed. Create a new Direct Upload." }
              }
              createWorker(chunk, upload, progress).upload()
            }
            currentCoroutineContext().ensureActive()
            check(final.bytesUploaded in 1..(fileSize - chunkStart)) { "Invalid chunk acknowledgement" }
            offset += final.bytesUploaded
            val acknowledged = final.copy(bytesUploaded = offset, totalBytes = fileSize, startTime = startTime)
            attempt.acknowledge(acknowledged) { writeUploadState(upload, acknowledged) }
          } finally { observer.cancelAndJoin() }
        }
      }
    }
  }
}

/** Retry the same byte range; accepted overlapping PUTs make a recovery query unnecessary. */
private suspend fun <T> withUploadRetries(limit: Int, request: suspend () -> T): T {
  var used = 0
  while (true) {
    currentCoroutineContext().ensureActive()
    try { return request() } catch (e: IOException) {
      if (used >= limit) throw e
      val waitMs = maxOf(1_000L shl used.coerceAtMost(3), (e as? UploadRetryException)?.retryAfterMs ?: 0)
      used++
      delay(waitMs)
    }
  }
}
