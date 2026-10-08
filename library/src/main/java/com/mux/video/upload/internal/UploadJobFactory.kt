package com.mux.video.upload.internal

import com.mux.video.upload.MuxUploadSdk
import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.MuxUploadManager
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.internal.standardization.PreparedUpload
import com.mux.video.upload.internal.standardization.UploadPreparation
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.util.UUID

@JvmSynthetic
internal fun startUploadJob(upload: UploadInfo): UploadInfo = MuxUploadSdk.uploadJobFactory()
  .createUploadJob(upload, CoroutineScope(Dispatchers.Default))

/** Owns payload selection and the transition from cancellable preparation to transport. */
internal class UploadJobFactory internal constructor(
  private val prepare: suspend (UploadInfo) -> PreparedUpload = {
    UploadPreparation().prepare(it, checkNotNull(MuxUploadManager.appContext))
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

  fun createUploadJob(uploadInfo: UploadInfo, outerScope: CoroutineScope): UploadInfo {
    uploadInfo.attempt?.supersede()
    val preparation = uploadInfo.attempt?.preparation ?: UploadPreparationState().apply {
      originalSelected = uploadInfo.restoredFromOriginal || hasOriginalResumeState(uploadInfo)
    }
    val startTime = System.currentTimeMillis()
    val attempt = UploadAttempt(preparation, MuxUpload.Progress(
      bytesUploaded = readLastByteForFile(uploadInfo), totalBytes = uploadInfo.inputFile.length(),
      startTime = startTime, updatedTime = startTime))
    var runningInfo = uploadInfo.update(attempt = attempt, statusFlow = attempt.status.asStateFlow())
    // Lazy startup lets the returned identity be installed before any completion callback.
    val job = outerScope.async(start = CoroutineStart.LAZY) {
      val sessionId = UUID.randomUUID().toString()
      val metrics = UploadMetrics.create()
      try {
        // An old export or request must finish cancellation before another attempt uses its files.
        uploadInfo.uploadJob?.join()
        ensureActive()
        if (uploadInfo.generatedResumeBlocked || preparation.generatedRequestStarted || uploadInfo.standardizedFile != null)
          throw GeneratedResumeBlockedException()
        if (!preparation.originalSelected && preparation.verified == null && uploadInfo.isStandardizationRequested()) {
          attempt.publish(UploadStatus.Preparing)
          when (val result = prepare(runningInfo)) {
            is PreparedUpload.Original -> {
              preparation.originalSelected = true
              MuxUploadSdk.logger.d("MuxUploadPreparation", result.diagnostic.toString())
            }
            is PreparedUpload.Generated -> attempt.retainGenerated(result.output)
          }
        }
        ensureActive()
        // A retained validated file can be replaced or modified while paused, before transport.
        val verified = preparation.verified?.takeIf { it.matchesValidation() }
        if (preparation.verified != null && verified == null) {
          preparation.deleteOwnedFile()
          preparation.originalSelected = true
        }
        val selected = verified?.file ?: uploadInfo.inputFile
        val fileSize = selected.length()
        check(selected.isFile && selected.canRead() && fileSize > 0) { "Upload payload is unreadable or empty" }
        attempt.selectPayload(fileSize)
        var totalBytesSent = attempt.confirmedProgress().bytesUploaded
        check(totalBytesSent in 0..fileSize) { "Saved original offset is outside the payload" }
        withContext(Dispatchers.IO) { BufferedInputStream(FileInputStream(selected)) }.use { stream ->
          withContext(Dispatchers.IO) {
            var remaining = totalBytesSent
            while (remaining > 0) {
              val skipped = stream.skip(remaining)
              check(skipped > 0) { "Unable to seek to saved original offset" }
              remaining -= skipped
            }
          }
          attempt.publish(UploadStatus.Uploading(attempt.confirmedProgress()))
          val buffer = ByteArray(uploadInfo.chunkSize)
          while (totalBytesSent < fileSize) {
            ensureActive()
            check(verified?.matchesValidation() != false) { "Validated payload changed. Create a new Direct Upload." }
            val count = minOf(buffer.size.toLong(), fileSize - totalBytesSent).toInt()
            withContext(Dispatchers.IO) {
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
              // Save the generated request marker before transport, even if no response arrives.
              attempt.beginTransport(verified != null) {
                writeUploadState(runningInfo, attempt.confirmedProgress())
              }
              val final = createWorker(chunk, runningInfo, progress).upload()
              ensureActive()
              totalBytesSent += final.bytesUploaded
              val acknowledged = final.copy(bytesUploaded = totalBytesSent, totalBytes = fileSize, startTime = startTime)
              attempt.acknowledge(acknowledged) { writeUploadState(runningInfo, acknowledged) }
            } finally { observer.cancelAndJoin() }
          }
        }
        ensureActive()
        val final = attempt.confirmedProgress()
        val success = UploadStatus.UploadSuccess(final)
        if (attempt.finish(success)) {
          if (!uploadInfo.optOut) metrics.reportUploadSucceeded(startTime, final.updatedTime, 0, sessionId, uploadInfo)
          withContext(Dispatchers.Main) { MuxUploadManager.jobFinished(runningInfo) }
        }
        Result.success(success)
      } catch (e: CancellationException) {
        // Pause/cancel owns the public transition. Cancellation is never an upload result.
        throw e
      } catch (e: Exception) {
        val failure = UploadStatus.UploadFailed(e, attempt.confirmedProgress())
        if (attempt.finish(failure)) {
          MuxUploadSdk.logger.e("MuxUpload", "Upload failed")
          if (!uploadInfo.optOut) metrics.reportUploadFailed(startTime, System.currentTimeMillis(), 0,
            "Upload failed", sessionId, uploadInfo)
          withContext(Dispatchers.Main) { MuxUploadManager.jobFinished(runningInfo, false) }
        }
        Result.failure(e)
      } finally {
        if (!attempt.isStopped() || attempt.isCancelled()) preparation.deleteOwnedFile()
      }
    }
    runningInfo = runningInfo.update(uploadJob = job)
    job.start()
    return runningInfo
  }
}
