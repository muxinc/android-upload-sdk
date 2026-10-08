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
  private var terminal = false
  private var superseded = false
  private var confirmed = initialProgress

  @Synchronized fun isStopped() = stopped
  @Synchronized fun isCancelled() = cancelled
  @Synchronized fun confirmedProgress() = confirmed
  @Synchronized fun supersede() { superseded = true }

  @Synchronized fun retainGenerated(output: SdrGeneratedFile) {
    if (superseded) preparation.discardLateOutput(output)
    else if (cancelled) {
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
    if (stopped) throw kotlinx.coroutines.CancellationException("Upload stopped")
    if (generated) preparation.generatedRequestStarted = true
    else preparation.originalSelected = true
    // Persist payload selection before the first request, even without an acknowledgement.
    persist()
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

  fun cancel(forget: () -> Unit) {
    synchronized(this) {
      if (cancelled) return
      stopped = true
      cancelled = true
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

internal class GeneratedResumeBlockedException : IllegalStateException(
  "Generated payload identity or server offset is unverified. Create a new Direct Upload."
)

internal class UploadCancelledException : IllegalStateException("Upload was cancelled. Create a new upload handle.")
