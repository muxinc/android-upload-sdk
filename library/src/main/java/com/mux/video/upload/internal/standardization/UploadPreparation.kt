package com.mux.video.upload.internal.standardization

import android.content.Context
import com.mux.video.upload.internal.UploadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** Closed diagnostics contain no customer paths, URLs, or platform error text. */
internal sealed interface PreparationDiagnostic {
  data object Original : PreparationDiagnostic
  data object InspectionFailed : PreparationDiagnostic
  data object UnsupportedPlan : PreparationDiagnostic
  data object GeneratedResumeUnavailable : PreparationDiagnostic
  data class ConversionFailed(val reason: SdrConversionFailure) : PreparationDiagnostic
  data object Validated : PreparationDiagnostic
}

internal sealed interface PreparedUpload {
  val diagnostic: PreparationDiagnostic
  data class Original(override val diagnostic: PreparationDiagnostic = PreparationDiagnostic.Original) : PreparedUpload
  data class Generated(val output: SdrGeneratedFile,
    override val diagnostic: PreparationDiagnostic = PreparationDiagnostic.Validated) : PreparedUpload
}

internal class UploadPreparation(
  private val inspectMetadata: (java.io.File) -> MetadataInspectionResult = MediaMetadataInspector()::inspect,
  private val inspectSamples: (java.io.File, MediaMetadataInspection, () -> Boolean) -> MediaSampleInspection =
    { file, metadata, cancelled -> StandardInputTimelineInspector().inspect(file, metadata, cancelled) },
  private val convert: suspend (Context, UploadInfo, MediaMetadataInspection, MediaSampleInspection, StandardInputConversion) -> SdrConversionResult =
    { context, upload, metadata, source, conversion ->
      var export: SdrConversionAdapter.Attempt? = null
      try { suspendCancellableCoroutine { continuation ->
        val attempt = SdrConversionAdapter(context, onAllocated = { owned ->
          val preparation = checkNotNull(upload.attempt).preparation
          val saved = checkNotNull(preparation.generatedState).copy(ownedPath = owned.file.absolutePath)
          preparation.generatedState = saved
          com.mux.video.upload.internal.UploadPersistence.writeGenerated(upload, saved)
        }, onPendingCleanup = { upload.attempt?.trackCleanup(it) })
          .start(upload.inputFile, metadata, source, conversion) { result ->
          // Transfer ownership before dispatch: completion can race pause or cancellation.
          if (result is SdrConversionResult.Completed) upload.attempt?.retainGenerated(result.output)
          continuation.resume(result) { _, value, _ ->
            if (value is SdrConversionResult.Completed && upload.attempt == null) value.output.delete()
          }
        }
        export = attempt
        continuation.invokeOnCancellation { attempt.cancel() }
      } } finally {
        if (!coroutineContext.isActive) withContext(NonCancellable) {
          export?.let { attempt ->
            if (kotlinx.coroutines.withTimeoutOrNull(com.mux.video.upload.internal.PREPARATION_RELEASE_TIMEOUT_MS) {
                attempt.awaitRelease(); true } != true) {
              // A deadline must not permit another export while Media3 still owns resources.
              upload.attempt?.preparation?.releaseBarrier = attempt::awaitRelease
            }
          }
        }
      }
    },
) {
  suspend fun prepare(upload: UploadInfo, context: Context,
    generatedResumeVerified: Boolean = false): PreparedUpload = withContext(Dispatchers.IO) {
    fun original(reason: PreparationDiagnostic) = PreparedUpload.Original(reason)
    if (!upload.isStandardizationRequested()) return@withContext original(PreparationDiagnostic.Original)
    if (!generatedResumeVerified) return@withContext original(PreparationDiagnostic.GeneratedResumeUnavailable)
    val job = coroutineContext
    try {
      val metadata = (inspectMetadata(upload.inputFile) as? MetadataInspectionResult.Success)?.inspection
        ?: return@withContext original(PreparationDiagnostic.InspectionFailed)
      job.ensureActive()
      val source = inspectSamples(upload.inputFile, metadata) { !job[kotlinx.coroutines.Job]!!.isActive }
      job.ensureActive()
      val action = StandardInputPlanner().plan(source.facts, upload.inputStandardization).action
      if (action is StandardInputAction.UploadOriginal) return@withContext original(PreparationDiagnostic.Original)
      // The adapter provides source-specific preflight and proves actual output. Planning keeps
      // unsupported HDR and unsafe structure out of this SDR boundary.
      val conversion = when (action) {
        is StandardInputAction.Convert -> action.conversion
        is StandardInputAction.Fallback -> (action.reason as? FallbackReason.UnsupportedConversion)?.conversion
        is StandardInputAction.UploadOriginal -> null
      } ?: return@withContext original(PreparationDiagnostic.UnsupportedPlan)
      if (SdrEncodingTargets.from(conversion, metadata, source) == null)
        return@withContext original(PreparationDiagnostic.UnsupportedPlan)
      when (val result = convert(context, upload, metadata, source, conversion)) {
        is SdrConversionResult.Completed -> PreparedUpload.Generated(result.output)
        is SdrConversionResult.Failed -> original(PreparationDiagnostic.ConversionFailed(result.reason))
        SdrConversionResult.Cancelled -> throw kotlinx.coroutines.CancellationException("Preparation cancelled")
      }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (_: LinkageError) { original(PreparationDiagnostic.InspectionFailed) }
      catch (_: Exception) { original(PreparationDiagnostic.InspectionFailed) }
  }
}
