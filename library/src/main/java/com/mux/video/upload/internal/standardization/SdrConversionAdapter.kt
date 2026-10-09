package com.mux.video.upload.internal.standardization

import com.mux.video.upload.internal.PayloadIdentity
import kotlinx.coroutines.CompletableDeferred
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MimeTypes
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface SdrConversionResult {
  /** Caller owns retention until upload/resume reaches its terminal point. */
  data class Completed(val output: SdrGeneratedFile, val facts: MediaFacts) : SdrConversionResult
  data class Failed(val reason: SdrConversionFailure) : SdrConversionResult
  data object Cancelled : SdrConversionResult
}

/** The only deletion authority returned to orchestration; never accepts an arbitrary path. */
internal class SdrGeneratedFile private constructor(val file: File) {
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
    fun restore(cacheDir: File, path: String, identity: PayloadIdentity? = null): SdrGeneratedFile? = try {
      val area = File(cacheDir.canonicalFile, "mux-upload/standard-input")
      val file = File(path)
      if (area.canonicalFile != area.absoluteFile || file.absolutePath != path ||
        file.parentFile != area || file.canonicalFile != file.absoluteFile ||
        !file.name.startsWith("sdr-") || !file.name.endsWith(".mp4")) null
      else SdrGeneratedFile(file).also { it.validatedIdentity = identity }
    } catch (_: Exception) { null }

    fun allocate(cacheDir: File, requiredBytes: Long): SdrGeneratedFile? {
      val area = File(cacheDir.canonicalFile, "mux-upload/standard-input")
      if ((!area.isDirectory && !area.mkdirs()) || area.canonicalFile != area.absoluteFile || area.usableSpace < requiredBytes) return null
      return SdrGeneratedFile(File.createTempFile("sdr-", ".mp4", area))
    }
  }
}

/** Internal boundary for Media3 export and cancellation. */
internal interface SdrExportEngine {
  fun start(input: File, output: File, completed: (String?, String?) -> Unit,
    failed: (SdrConversionFailure) -> Unit)
  fun cancel()
}

/** Each attempt owns one thread, one Transformer, and one explicitly tracked output file. */
internal class SdrConversionAdapter(
  context: Context,
  private val preflight: (StandardInputConversion, MediaMetadataInspection, SdrEncodingTargets, () -> Boolean) -> SdrEncoderCapability? =
    SdrCapabilityPreflight()::videoEncoder,
  private val validate: (File, MediaFacts, StandardInputTimelineFacts, StandardInputConversion, () -> Boolean) -> StandardInputOutputValidation =
    StandardInputOutputValidator()::validateGeneratedOutput,
  private val createEngine: (Context, Looper, StandardInputConversion, SdrEncodingTargets, SdrEncoderCapability) -> SdrExportEngine =
    ::Media3SdrExportEngine,
  private val onAllocated: (SdrGeneratedFile) -> Unit = {},
  private val onPendingCleanup: (SdrGeneratedFile) -> Unit = {},
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
    private val thread = HandlerThread("MuxSdrConversion").apply { start() }
    internal val looper: Looper = thread.looper
    private val handler = Handler(looper)
    private val cancelled = AtomicBoolean(false)
    private val terminalLock = Any()
    private var terminal = false
    private val released = CompletableDeferred<Unit>()
    private var engine: SdrExportEngine? = null
    private var output: SdrGeneratedFile? = null
    /** Exact deletion authority retained if the OS refuses cleanup, including cancellation. */
    @Volatile var pendingCleanup: SdrGeneratedFile? = null
      private set
    private val sourceLength = input.length()
    private val sourceModified = input.lastModified()

    fun cancel() {
      synchronized(terminalLock) {
        if (terminal) return
        cancelled.set(true)
      }
      handler.post { finish(SdrConversionResult.Cancelled) }
    }

    suspend fun awaitRelease() { released.await() }

    internal fun begin() { handler.post {
      guarded {
        if (cancelled.get()) return@guarded finish(SdrConversionResult.Cancelled)
        val targets = SdrEncodingTargets.from(conversion, metadata, source)
          ?: return@guarded fail(SdrConversionFailure.UnsupportedPlan)
        val capability = preflight(conversion, metadata, targets, cancelled::get)
          ?: return@guarded fail(SdrConversionFailure.CapabilityUnavailable)
        if (cancelled.get()) return@guarded finish(SdrConversionResult.Cancelled)
        if (!sourceUnchanged()) return@guarded fail(SdrConversionFailure.SourceChanged)
        val required = source.timeline.durationSeconds.valueOrNull?.let(targets::estimatedOutputBytes)
          ?: return@guarded fail(SdrConversionFailure.InsufficientSourceEvidence)
        val owned = SdrGeneratedFile.allocate(appContext.cacheDir, required)
          ?: return@guarded fail(SdrConversionFailure.DiskSpace)
        output = owned
        onAllocated(owned)
        if (cancelled.get()) return@guarded finish(SdrConversionResult.Cancelled)
        val exporter = createEngine(appContext, looper, conversion, targets, capability)
        engine = exporter
        exporter.start(input, owned.file, { videoMime, audioMime ->
          handler.post { guarded {
            if (isTerminal()) return@guarded
            if (videoMime != targets.videoMime || audioMime != if (targets.audioChannels != null) MimeTypes.AUDIO_AAC else null)
              return@guarded fail(SdrConversionFailure.CodecChanged)
            if (!sourceUnchanged()) return@guarded fail(SdrConversionFailure.SourceChanged)
            when (val validation = validate(owned.file, source.facts, source.timeline, conversion, cancelled::get)) {
              is StandardInputOutputValidation.Accepted -> {
                if (!sourceUnchanged()) return@guarded fail(SdrConversionFailure.SourceChanged)
                // The shared validator checks policy, pixels, cadence, and effective timeline.
                // Add this adapter's concrete encoder-profile and non-AAC channel contract.
                val audio = validation.facts.audioTracks.valueOrNull?.singleOrNull()?.format?.valueOrNull as? AudioFormat.Aac
                val expectedLayout = CodecMetadataReader.aacChannelLayout(targets.audioChannels)
                if (validation.facts.videoProfile != MediaFact.Known(targets.profile) ||
                  (targets.audioChannels != null && audio?.layout != expectedLayout)) fail(SdrConversionFailure.OutputInvalid)
                else if (!owned.recordValidation()) fail(SdrConversionFailure.OutputInvalid)
                else finish(SdrConversionResult.Completed(owned, validation.facts))
              }
              is StandardInputOutputValidation.Rejected -> fail(SdrConversionFailure.OutputInvalid)
              StandardInputOutputValidation.Cancelled -> finish(SdrConversionResult.Cancelled)
            }
          } }
        }, { reason -> handler.post { guarded { if (!isTerminal()) fail(reason) } } })
      }
    } }

    private fun sourceUnchanged() = input.isFile && input.canRead() && sourceLength > 0 &&
      input.length() == sourceLength && input.lastModified() == sourceModified
    private fun isTerminal() = synchronized(terminalLock) { terminal }
    private fun fail(reason: SdrConversionFailure) = finish(SdrConversionResult.Failed(reason))
    private fun guarded(block: () -> Unit) {
      if (isTerminal()) return
      try { block() }
      catch (_: Exception) { fail(SdrConversionFailure.Export) }
      catch (_: LinkageError) { fail(SdrConversionFailure.Export) }
    }

    private fun finish(proposed: SdrConversionResult) {
      val result = synchronized(terminalLock) {
        if (terminal) return
        terminal = true
        if (cancelled.get()) SdrConversionResult.Cancelled else proposed
      }
      try {
        runCatching { engine?.cancel() }
        if (result !is SdrConversionResult.Completed && output?.delete() == false) {
          pendingCleanup = output
          output?.let(onPendingCleanup)
          // Keep ownership at its exact path if the OS refuses deletion; never sweep a directory.
          if (result !is SdrConversionResult.Cancelled) {
            onResult(SdrConversionResult.Failed(SdrConversionFailure.FileOwnership))
            return
          }
        }
        onResult(result)
      } finally {
        engine = null
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        released.complete(Unit)
      }
    }
  }
}
