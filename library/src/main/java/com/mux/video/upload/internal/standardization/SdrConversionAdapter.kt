package com.mux.video.upload.internal.standardization

import com.mux.video.upload.internal.PayloadIdentity
import android.content.Context
import android.os.Looper
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal sealed interface SdrConversionResult {
  /** Caller owns retention until upload/resume reaches its terminal point. */
  data class Completed(val output: SdrStandardizedFile, val facts: MediaFacts) : SdrConversionResult
  data class Failed(val reason: SdrConversionFailure) : SdrConversionResult
  data object Cancelled : SdrConversionResult
}

/** The only deletion authority returned to orchestration; never accepts an arbitrary path. */
internal class SdrStandardizedFile private constructor(val file: File) {
  internal var validatedIdentity: PayloadIdentity? = null
    private set
  internal fun recordValidation(): Boolean {
    validatedIdentity = PayloadIdentity.capture(file)
    return validatedIdentity != null
  }
  internal fun matchesValidation(): Boolean = validatedIdentity?.matches(file) == true
  fun delete(): Boolean = try {
    file.canonicalFile == file.absoluteFile && (!file.exists() || file.delete())
  } catch (_: Exception) { false }

  companion object {
    /** Restoration accepts only the exact tracked file in the dedicated SDK area. */
    fun restore(cacheDir: File, path: String, identity: PayloadIdentity? = null): SdrStandardizedFile? = try {
      val area = File(cacheDir.canonicalFile, "mux-upload/standard-input")
      val file = File(path)
      if (area.canonicalFile != area.absoluteFile || file.absolutePath != path ||
        file.parentFile != area || file.canonicalFile != file.absoluteFile ||
        !file.name.startsWith("sdr-") || !file.name.endsWith(".mp4")) null
      else SdrStandardizedFile(file).also { it.validatedIdentity = identity }
    } catch (_: Exception) { null }

    fun allocate(cacheDir: File, requiredBytes: Long): SdrStandardizedFile? {
      val area = File(cacheDir.canonicalFile, "mux-upload/standard-input")
      if ((!area.isDirectory && !area.mkdirs()) || area.canonicalFile != area.absoluteFile || area.usableSpace < requiredBytes) return null
      return SdrStandardizedFile(File.createTempFile("sdr-", ".mp4", area))
    }
  }
}

/** Internal boundary for Media3 export and cancellation. */
internal interface SdrExportEngine {
  fun start(input: File, output: File, completed: (String?, String?) -> Unit,
    failed: (SdrConversionFailure) -> Unit)
  fun cancel()
}

/** Each attempt owns a coroutine job, one Transformer, and one explicitly tracked output file. */
internal class SdrConversionAdapter(
  context: Context,
  private val preflight: (StandardInputConversion, MediaMetadataInspection, SdrEncodingTargets, () -> Boolean) -> SdrEncoderCapability? =
    SdrCapabilityPreflight()::videoEncoder,
  private val validate: (File, MediaFacts, StandardInputTimelineFacts, StandardInputConversion, () -> Boolean) -> StandardInputOutputValidation =
    StandardInputOutputValidator()::validateStandardizedOutput,
  private val createEngine: (Context, Looper, StandardInputConversion, SdrEncodingTargets, SdrEncoderCapability) -> SdrExportEngine =
    ::Media3SdrExportEngine,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val onAllocated: (SdrStandardizedFile) -> Unit = {},
  private val onPendingCleanup: (SdrStandardizedFile) -> Unit = {},
) {
  private val appContext = context.applicationContext

  fun start(input: File, metadata: MediaMetadataInspection, source: MediaSampleInspection,
    conversion: StandardInputConversion, onResult: (SdrConversionResult) -> Unit): Attempt =
    Attempt(input, metadata, source, conversion, onResult).also { it.begin() }

  inner class Attempt internal constructor(
    private val input: File, private val metadata: MediaMetadataInspection,
    private val source: MediaSampleInspection, private val conversion: StandardInputConversion,
    private val onResult: (SdrConversionResult) -> Unit,
  ) {
    private val job = Job()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private val terminalLock = Any()
    private var terminal = false
    private val released = CompletableDeferred<Unit>()
    private var engine: SdrExportEngine? = null
    private var output: SdrStandardizedFile? = null
    /** Exact deletion authority retained if the OS refuses cleanup, including cancellation. */
    @Volatile var pendingCleanup: SdrStandardizedFile? = null
      private set
    private val sourceLength = input.length()
    private val sourceModified = input.lastModified()

    fun cancel() {
      synchronized(terminalLock) {
        if (terminal) return
        job.cancel()
      }
    }

    suspend fun awaitRelease() { released.await() }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    internal fun begin() {
      // Enter cleanup even when cancellation wins before the initial Main dispatch.
      scope.launch(start = CoroutineStart.ATOMIC) {
        val result = try {
          ensureActive()
          convert()
        } catch (_: CancellationException) { SdrConversionResult.Cancelled }
          catch (_: Exception) { SdrConversionResult.Failed(SdrConversionFailure.Export) }
          catch (_: LinkageError) { SdrConversionResult.Failed(SdrConversionFailure.Export) }
        withContext(NonCancellable) { finish(result) }
      }
    }

    private suspend fun convert(): SdrConversionResult {
      val targets = withContext(ioDispatcher) { SdrEncodingTargets.from(conversion, metadata, source) }
        ?: return SdrConversionResult.Failed(SdrConversionFailure.UnsupportedPlan)
      val capability = withContext(ioDispatcher) { preflight(conversion, metadata, targets) { !job.isActive } }
        ?: return SdrConversionResult.Failed(SdrConversionFailure.CapabilityUnavailable)
      val preparationFailure = withContext(ioDispatcher) {
        if (!sourceUnchanged()) return@withContext SdrConversionFailure.SourceChanged
        val required = source.timeline.durationSeconds.valueOrNull?.let(targets::estimatedOutputBytes)
          ?: return@withContext SdrConversionFailure.InsufficientSourceEvidence
        // Record ownership before returning across a cancellable dispatcher boundary.
        output = SdrStandardizedFile.allocate(appContext.cacheDir, required)
        output?.let(onAllocated)
        if (output == null) SdrConversionFailure.DiskSpace else null
      }
      if (preparationFailure != null) return SdrConversionResult.Failed(preparationFailure)
      val owned = checkNotNull(output)
      val exporter = createEngine(appContext, Looper.getMainLooper(), conversion, targets, capability)
      engine = exporter
      val exportFailure = suspendCancellableCoroutine<SdrConversionFailure?> { continuation ->
        val delivered = AtomicBoolean(false)
        continuation.invokeOnCancellation { delivered.set(true) }
        fun complete(reason: SdrConversionFailure?) {
          if (delivered.compareAndSet(false, true)) continuation.resume(reason)
        }
        exporter.start(input, owned.file, { videoMime, audioMime ->
          complete(if (videoMime != targets.videoMime ||
            audioMime != if (targets.audioChannels != null) MimeTypes.AUDIO_AAC else null)
            SdrConversionFailure.CodecChanged else null)
        }, ::complete)
      }
      if (exportFailure != null) return SdrConversionResult.Failed(exportFailure)
      return withContext(ioDispatcher) {
        if (!sourceUnchanged()) return@withContext SdrConversionResult.Failed(SdrConversionFailure.SourceChanged)
        when (val validation = validate(owned.file, source.facts, source.timeline, conversion) { !job.isActive }) {
          is StandardInputOutputValidation.Accepted -> {
            if (!sourceUnchanged()) return@withContext SdrConversionResult.Failed(SdrConversionFailure.SourceChanged)
            // The shared validator checks policy, pixels, cadence, and effective timeline.
            // Add this adapter's concrete encoder-profile and non-AAC channel contract.
            val audio = validation.facts.audioTracks.valueOrNull?.singleOrNull()?.format?.valueOrNull as? AudioFormat.Aac
            val expectedLayout = aacChannelLayout(targets.audioChannels).valueOrNull
            if (validation.facts.videoProfile != MediaFact.Known(targets.profile) ||
              (targets.audioChannels != null && audio?.layout != expectedLayout))
              SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid)
            else if (!owned.recordValidation()) SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid)
            else SdrConversionResult.Completed(owned, validation.facts)
          }
          is StandardInputOutputValidation.Rejected -> SdrConversionResult.Failed(SdrConversionFailure.OutputInvalid)
          StandardInputOutputValidation.Cancelled -> SdrConversionResult.Cancelled
        }
      }
    }

    private fun sourceUnchanged() = input.isFile && input.canRead() && sourceLength > 0 &&
      input.length() == sourceLength && input.lastModified() == sourceModified
    private suspend fun finish(proposed: SdrConversionResult) {
      var result = synchronized(terminalLock) {
        if (terminal) return
        terminal = true
        if (job.isCancelled) SdrConversionResult.Cancelled else proposed
      }
      try {
        runCatching { engine?.cancel() }
        if (result !is SdrConversionResult.Completed && withContext(ioDispatcher) { output?.delete() } == false) {
          pendingCleanup = output
          output?.let(onPendingCleanup)
          // Keep ownership at its exact path if the OS refuses deletion; never sweep a directory.
          if (result !is SdrConversionResult.Cancelled) result = SdrConversionResult.Failed(SdrConversionFailure.FileOwnership)
        }
        runCatching { onResult(result) }
      } finally {
        engine = null
        released.complete(Unit)
        scope.cancel()
      }
    }
  }
}
