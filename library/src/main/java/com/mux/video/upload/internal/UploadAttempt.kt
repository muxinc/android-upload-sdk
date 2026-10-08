package com.mux.video.upload.internal

import com.mux.video.upload.api.MuxUpload
import com.mux.video.upload.api.UploadStatus
import com.mux.video.upload.internal.standardization.SdrGeneratedFile
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/** Handles for one destination follow replacements through this shared record. */
internal class UploadSession {
  val current = MutableStateFlow<UploadInfo?>(null)
}

/** Shared across in-process pause/resume, never reconstructed as a generated payload from disk. */
internal class UploadPreparationState {
  @Volatile var originalSelected = false
  @Volatile var verified: SdrGeneratedFile? = null
  @Volatile var generatedRequestStarted = false
  private val pendingCleanup = mutableSetOf<SdrGeneratedFile>()

  @Synchronized fun trackCleanup(output: SdrGeneratedFile) { pendingCleanup += output }

  @Synchronized fun discardLateOutput(output: SdrGeneratedFile) {
    if (verified !== output && !output.delete()) pendingCleanup += output
  }

  @Synchronized fun deleteOwnedFile() {
    if (verified?.delete() != false) verified = null
    pendingCleanup.removeAll { it.delete() }
  }
}

/** Serializes terminal transitions with transport acknowledgements and persistence. */
internal class UploadAttempt(
  val preparation: UploadPreparationState,
  initialProgress: MuxUpload.Progress,
  val previousPersistenceOwnerId: String? = null,
) {
  val id: String = UUID.randomUUID().toString()
  val status = MutableStateFlow<UploadStatus>(UploadStatus.Started)
  private var stopped = false
  private var cancelled = false
  private var restarting = false
  private var terminal = false
  private var superseded = false
  private var confirmed = initialProgress
  private var transportPayloadGenerated: Boolean? = null
  private var replacement: UploadReplacedException? = null

  @Synchronized fun isStopped() = stopped
  @Synchronized fun isCancelled() = cancelled
  @Synchronized fun isRestarting() = restarting
  @Synchronized fun replacementFailure() = replacement
  @Synchronized fun confirmedProgress() = confirmed
  @Synchronized fun supersede() { superseded = true }

  @Synchronized fun retainGenerated(output: SdrGeneratedFile) {
    if (superseded) preparation.discardLateOutput(output)
    else if (cancelled || replacement != null) {
      preparation.trackCleanup(output)
      preparation.deleteOwnedFile()
    } else preparation.verified = output
  }

  @Synchronized fun trackCleanup(output: SdrGeneratedFile) {
    if (superseded) { preparation.discardLateOutput(output); return }
    preparation.trackCleanup(output)
    if (cancelled || terminal) preparation.deleteOwnedFile()
  }

  @Synchronized fun selectPayload(totalBytes: Long) {
    confirmed = confirmed.copy(totalBytes = totalBytes)
  }

  @Synchronized fun beginTransport(generated: Boolean, persist: () -> Unit) {
    if (stopped || terminal || superseded) throw kotlinx.coroutines.CancellationException("Upload stopped")
    if (transportPayloadGenerated == generated) return
    if (generated) preparation.generatedRequestStarted = true
    else preparation.originalSelected = true
    // Claim persistence ownership before the first request of each attempt/payload choice.
    persist()
    transportPayloadGenerated = generated
  }

  @Synchronized fun publish(value: UploadStatus) {
    if (!stopped && !terminal && !superseded) status.value = value
  }

  @Synchronized fun acknowledge(progress: MuxUpload.Progress, persist: () -> Unit) {
    if (stopped || terminal || superseded) return
    confirmed = progress
    persist()
    status.value = UploadStatus.Uploading(progress)
  }

  @Synchronized fun pause(persist: (MuxUpload.Progress) -> Unit) {
    if (stopped || terminal) return
    stopped = true
    status.value = UploadStatus.UploadPaused(confirmed)
    persist(confirmed)
  }

  fun cancel(forRestart: Boolean = false, forget: () -> Unit) {
    synchronized(this) {
      if (cancelled) return
      stopped = true
      cancelled = true
      restarting = forRestart
      forget()
    }
    preparation.deleteOwnedFile()
  }

  fun replace(forget: () -> Unit) {
    synchronized(this) {
      if (cancelled || replacement != null) return
      replacement = UploadReplacedException()
      stopped = true
      if (!terminal) {
        terminal = true
        status.value = UploadStatus.UploadFailed(checkNotNull(replacement), confirmed)
      }
      forget()
    }
    preparation.deleteOwnedFile()
  }

  @Synchronized fun finish(value: UploadStatus): Boolean {
    if (stopped || terminal || superseded) return false
    terminal = true
    status.value = value
    return true
  }
}

/** Both paused restoration and transport start with the same progress/ownership rules. */
internal fun createUploadAttempt(upload: UploadInfo, saved: UploadResumeState): UploadAttempt {
  val preparation = upload.attempt?.preparation ?: UploadPreparationState().apply {
    originalSelected = upload.restoredFromOriginal || saved.originalSelected
  }
  val now = System.currentTimeMillis()
  return UploadAttempt(preparation, MuxUpload.Progress(
    bytesUploaded = if (saved.generatedResumeBlocked) 0 else saved.bytesSent,
    totalBytes = upload.inputFile.length(), startTime = now, updatedTime = now),
    previousPersistenceOwnerId = saved.attemptId)
}

internal class UploadReplacedException : IllegalStateException("Upload was replaced by a new destination.")

internal class GeneratedResumeBlockedException : IllegalStateException(
  "Generated payload identity or server offset is unverified. Create a new Direct Upload."
)

internal class UploadCancelledException : IllegalStateException("Upload was cancelled. Create a new upload handle.")
