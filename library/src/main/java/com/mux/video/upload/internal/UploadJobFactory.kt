package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.internal.standardization.SdrGeneratedFile
import com.mux.video.upload.internal.standardization.PreparedUpload
import com.mux.video.upload.internal.standardization.UploadPreparation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile
import java.io.IOException
import java.util.UUID

@JvmSynthetic
internal fun startUploadJob(upload: UploadInfo, saved: UploadResumeState? = null): UploadInfo = MuxUploadSdk.uploadJobFactory()
  .createUploadJob(upload, CoroutineScope(Dispatchers.Default), saved)

/** Owns payload selection and the transition from cancellable preparation to transport. */
internal class UploadJobFactory internal constructor(
  private val prepare: suspend (UploadInfo) -> PreparedUpload = {
    UploadPreparation().prepare(it, checkNotNull(MuxUploadManager.appContext), generatedResumeVerified = true)
  },
  private val queryOffset: suspend (UploadInfo, Long) -> Long = GeneratedUploadProtocol::query,
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
    val preparation = attempt.preparation
    val startTime = attempt.confirmedProgress().startTime
    var runningInfo = uploadInfo.update(inputStandardization = saved.generatedOptions ?: uploadInfo.inputStandardization, attempt = attempt, statusFlow = attempt.status.asStateFlow())
    // Lazy startup lets the returned identity be installed before any completion callback.
    val job = outerScope.async(start = CoroutineStart.LAZY) {
      val sessionId = UUID.randomUUID().toString()
      val metrics = UploadMetrics.create()
      var mustRetireGenerated = false
      suspend fun query(total: Long, retries: GeneratedRetryBudget = GeneratedRetryBudget(runningInfo.retriesPerChunk)): Long {
        // Keep the payload and possible-remote-bytes marker when the network is unavailable.
        preparation.generatedState = checkNotNull(preparation.generatedState).copy(networkStarted = true)
        withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(runningInfo, checkNotNull(preparation.generatedState)) }
        while (true) {
          try {
            return queryOffset(runningInfo, total).also {
              if (it !in 0..total) throw GeneratedResumeBlockedException()
            }
          } catch (e: IOException) { retries.retry(e) }
        }
      }
      try {
        // An old export or request must finish cancellation before another attempt uses its files.
        uploadInfo.uploadJob?.join()
        ensureActive()
        for (owner in listOfNotNull(uploadInfo.predecessorPreparation, preparation).distinct()) {
          owner.releaseBarrier?.let { barrier ->
            if (withTimeoutOrNull(PREPARATION_RELEASE_TIMEOUT_MS) { barrier(); true } != true)
              throw PreparationReleasePendingException()
            owner.releaseBarrier = null
          }
        }
        var offset: Long? = null
        val restored = preparation.generatedState
        if (restored != null) {
          if (preparation.verified == null && restored.ownedPath != null) {
            val owned = SdrGeneratedFile.restore(checkNotNull(MuxUploadManager.appContext).cacheDir,
              restored.ownedPath, restored.payload) ?: throw GeneratedResumeBlockedException()
            if (restored.payload != null) preparation.verified = owned else preparation.trackCleanup(owned)
          }
          if (restored.abandoned || uploadInfo.generatedResumeBlocked || saved.generatedResumeBlocked)
            throw GeneratedResumeBlockedException()
          if (restored.payload != null) {
            offset = query(restored.payload.size)
            val valid = withContext(Dispatchers.IO) { preparation.verified?.matchesValidation() == true }
            if (!valid) {
              if (offset != 0L || !withContext(Dispatchers.IO) { restored.source.matches(uploadInfo.inputFile) })
                throw GeneratedResumeBlockedException()
              withContext(Dispatchers.IO) { preparation.deleteOwnedFile() }
              preparation.generatedState = restored.copy(phase = PreparationPhase.Preparing, ownedPath = null,
                payload = null, networkStarted = false)
              preparation.generatedRequestStarted = false
              withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(runningInfo, checkNotNull(preparation.generatedState)) }
            }
          } else {
            offset = 0L // A durable preparing marker proves this attempt sent no payload bytes.
            if (restored.networkStarted || !withContext(Dispatchers.IO) { restored.source.matches(uploadInfo.inputFile) })
              throw GeneratedResumeBlockedException()
            withContext(Dispatchers.IO) { preparation.deleteOwnedFile() }
          }
        } else if (uploadInfo.generatedResumeBlocked || saved.generatedResumeBlocked || preparation.generatedRequestStarted)
          throw GeneratedResumeBlockedException()
        if (!preparation.originalSelected && preparation.verified == null && runningInfo.isStandardizationRequested()) {
          val source = withContext(Dispatchers.IO) { PayloadIdentity.capture(uploadInfo.inputFile) }
          if (source != null) {
            preparation.generatedState = GeneratedResumeState(source)
          }
          attempt.publish(UploadStatus.Preparing)
          when (val result = prepare(runningInfo)) {
            is PreparedUpload.Original -> {
              preparation.originalSelected = true
              preparation.generatedState = null
              withContext(Dispatchers.IO) { UploadPersistence.retireGenerated(runningInfo) }
              MuxUploadSdk.logger.d("MuxUploadPreparation", result.diagnostic.toString())
            }
            is PreparedUpload.Generated -> {
              attempt.retainGenerated(result.output)
              offset = null // A newly prepared payload must confirm zero bytes for its own total.
            }
          }
        }
        ensureActive()
        var verified = preparation.verified
        if (verified != null && !withContext(Dispatchers.IO) { verified!!.matchesValidation() }) {
          if (preparation.generatedRequestStarted) throw GeneratedResumeBlockedException()
          withContext(Dispatchers.IO) { preparation.deleteOwnedFile() }
          preparation.originalSelected = true
          preparation.generatedState = null
          withContext(Dispatchers.IO) { UploadPersistence.retireGenerated(runningInfo) }
          verified = null
        }
        if (verified != null) {
          val identity = checkNotNull(verified.validatedIdentity)
          if (offset == null) {
            offset = query(identity.size)
            // A new generated selection cannot adopt bytes sent by an unrelated attempt.
            if (offset != 0L) {
              throw GeneratedResumeBlockedException()
            }
          }
          preparation.generatedState = checkNotNull(preparation.generatedState).copy(phase = PreparationPhase.Validated,
            ownedPath = verified.file.absolutePath, payload = identity)
          withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(runningInfo, checkNotNull(preparation.generatedState)) }
        }
        val selected = verified?.file ?: uploadInfo.inputFile
        val fileSize = withContext(Dispatchers.IO) { selected.length().also {
          check(selected.isFile && selected.canRead() && it > 0) { "Upload payload is unreadable or empty" }
        } }
        attempt.selectPayload(fileSize, bytesUploaded = offset)
        var totalBytesSent = if (verified != null) checkNotNull(offset) else attempt.confirmedProgress().bytesUploaded
        check(totalBytesSent in 0..fileSize) { "Saved offset is outside the payload" }
        if (verified != null) {
          val reconciled = attempt.confirmedProgress().copy(bytesUploaded = totalBytesSent,
            updatedTime = System.currentTimeMillis())
          attempt.acknowledge(reconciled) { writeUploadState(runningInfo, reconciled) }
        }
        withContext(Dispatchers.IO) { RandomAccessFile(selected, "r") }.use { stream ->
          attempt.publish(UploadStatus.Uploading(attempt.confirmedProgress()))
          val buffer = ByteArray(uploadInfo.chunkSize)
          var generatedTransportSaved = false
          var generatedRetries = GeneratedRetryBudget(runningInfo.retriesPerChunk)
          while (totalBytesSent < fileSize) {
            ensureActive()
            check(withContext(Dispatchers.IO) { verified?.matchesValidation() != false }) { "Validated payload changed. Create a new Direct Upload." }
            val count = minOf(buffer.size.toLong(), fileSize - totalBytesSent).toInt()
            withContext(Dispatchers.IO) {
              stream.seek(totalBytesSent)
              var read = 0
              while (read < count) {
                val size = stream.read(buffer, read, count - read)
                check(size > 0) { "Upload payload changed while reading" }
                read += size
              }
            }
            val chunk = ChunkWorker.Chunk(totalBytesSent, totalBytesSent + count - 1,
              fileSize, count, buffer)
            val progress = MutableSharedFlow<MuxUpload.Progress>(replay = 1,
              extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val offset = totalBytesSent
            val observer = launch {
              progress.collect { value -> attempt.publish(UploadStatus.Uploading(value.copy(
                bytesUploaded = value.bytesUploaded + offset, totalBytes = fileSize, startTime = startTime))) }
            }
            try {
              // commit() runs off the UI thread, outside the attempt monitor. No request may
              // start unless the complete identity and possible-remote-bytes marker are durable.
              if (verified != null && !generatedTransportSaved) {
                preparation.generatedState = checkNotNull(preparation.generatedState).copy(
                  phase = PreparationPhase.Uploading, networkStarted = true)
                withContext(Dispatchers.IO) { UploadPersistence.writeGenerated(runningInfo, checkNotNull(preparation.generatedState)) }
                generatedTransportSaved = true
              }
              ensureActive()
              attempt.beginTransport(verified != null) {
                writeUploadState(runningInfo, attempt.confirmedProgress())
              }
              val final = try {
                createWorker(chunk, runningInfo, progress).upload()
              } catch (e: IOException) {
                if (verified == null) throw e
                // Never replay uncertain generated bytes without asking the server first.
                generatedRetries.retry(e)
                val reconciled = query(fileSize, generatedRetries)
                if (reconciled !in totalBytesSent..(chunk.endByte + 1)) throw GeneratedResumeBlockedException()
                totalBytesSent = reconciled
                val acknowledged = attempt.confirmedProgress().copy(bytesUploaded = reconciled,
                  updatedTime = System.currentTimeMillis())
                attempt.acknowledge(acknowledged) { writeUploadState(runningInfo, acknowledged) }
                continue
              }
              generatedRetries = GeneratedRetryBudget(runningInfo.retriesPerChunk)
              ensureActive()
              if (verified != null && final.bytesUploaded !in 1..count.toLong()) throw GeneratedResumeBlockedException()
              totalBytesSent += final.bytesUploaded
              val acknowledged = final.copy(bytesUploaded = totalBytesSent, totalBytes = fileSize, startTime = startTime)
              attempt.acknowledge(acknowledged) { writeUploadState(runningInfo, acknowledged) }
            } finally { observer.cancelAndJoin() }
          }
        }
        ensureActive()
        val final = attempt.confirmedProgress()
        val success = UploadStatus.UploadSuccess(final)
        mustRetireGenerated = true
        if (attempt.finish(success)) {
          if (!uploadInfo.optOut) metrics.reportUploadSucceeded(startTime, final.updatedTime, 0, sessionId, uploadInfo)
          withContext(Dispatchers.Main) { MuxUploadManager.jobFinished(runningInfo) }
        }
        Result.success(success)
      } catch (e: CancellationException) {
        // Pause/cancel owns the public transition. Cancellation is never an upload result.
        throw e
      } catch (e: Exception) {
        if (e is GeneratedResumeBlockedException) mustRetireGenerated = true
        val failure = UploadStatus.UploadFailed(e, attempt.confirmedProgress())
        if (attempt.finish(failure)) {
          val category = when (e) {
            is GeneratedResumeBlockedException -> "GeneratedResumeBlocked"
            is java.io.IOException -> "Io"
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
            if (preparation.releaseBarrier != null) {
              preparation.generatedState?.let { state ->
                UploadPersistence.writeGenerated(runningInfo, state.copy(abandoned = attempt.isCancelled() ||
                  attempt.replacementFailure() != null))
              }
            } else if (mustRetireGenerated || attempt.isCancelled() || attempt.replacementFailure() != null ||
              preparation.verified == null && !attempt.isStopped()) {
              preparation.generatedState?.takeIf { mustRetireGenerated && it.networkStarted }?.let {
                UploadPersistence.writeGenerated(runningInfo, it)
              }
              preparation.deleteOwnedFile()
              UploadPersistence.retireGenerated(runningInfo)
            } else preparation.generatedState?.let { UploadPersistence.writeGenerated(runningInfo, it) }
          } catch (_: Exception) {
            // The durable pre-request marker remains authoritative after a cleanup-write failure.
            // Preserve the pause/cancel/result contract; a later restore still verifies/query-fails.
            MuxUploadSdk.logger.e("MuxUpload", "Generated persistence cleanup failed")
          }
        }
      }
    }
    runningInfo = runningInfo.update(uploadJob = job)
    runningInfo.session.current.value = runningInfo
    job.start()
    return runningInfo
  }
}

/** One budget covers uncertain chunk requests and their recovery queries. */
private class GeneratedRetryBudget(private val limit: Int) {
  private var used = 0
  suspend fun retry(failure: IOException) {
    if (used >= limit) throw failure
    val waitMs = 1_000L shl used.coerceAtMost(3)
    used++
    delay(waitMs)
  }
}
