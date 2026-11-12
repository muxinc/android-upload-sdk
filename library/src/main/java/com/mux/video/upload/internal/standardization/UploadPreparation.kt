package com.mux.video.upload.internal.standardization

import android.content.Context
import com.mux.video.upload.internal.UploadInfo
import kotlinx.coroutines.Dispatchers
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
  data object StandardizedResumeUnavailable : PreparationDiagnostic
  data class ConversionFailed(val reason: SdrConversionFailure) : PreparationDiagnostic
  data object Validated : PreparationDiagnostic
}

internal sealed interface PreparedUpload {
  val diagnostic: PreparationDiagnostic
  data class Original(override val diagnostic: PreparationDiagnostic = PreparationDiagnostic.Original) : PreparedUpload
  data class Standardized(val output: SdrStandardizedFile,
    override val diagnostic: PreparationDiagnostic = PreparationDiagnostic.Validated) : PreparedUpload
}

internal class UploadPreparation(
  private val inspectMetadata: (java.io.File) -> MetadataInspectionResult = MediaMetadataInspector()::inspect,
  private val inspectSamples: (java.io.File, MediaMetadataInspection, () -> Boolean) -> MediaSampleInspection =
    { file, metadata, cancelled -> StandardInputTimelineInspector().inspect(file, metadata, cancelled) },
  private val inspectCapabilities: (StandardInputConversion, MediaMetadataInspection, MediaSampleInspection, () -> Boolean) -> PlanningCapabilities =
    SdrPlanningCapabilityProbe()::capabilities,
  private val convert: suspend (Context, UploadInfo, MediaMetadataInspection, MediaSampleInspection, StandardInputConversion) -> SdrConversionResult =
    { context, upload, metadata, source, conversion ->
      suspendCancellableCoroutine { continuation ->
        val attempt = SdrConversionAdapter(context, onAllocated = { owned ->
          val preparation = checkNotNull(upload.attempt).preparation
          val saved = checkNotNull(preparation.standardizedState).copy(ownedPath = owned.file.absolutePath)
          preparation.standardizedState = saved
          com.mux.video.upload.internal.UploadPersistence.writeStandardized(upload, saved)
        }, onPendingCleanup = { upload.attempt?.trackCleanup(it) })
          .start(upload.inputFile, metadata, source, conversion) { result ->
          // Transfer ownership before dispatch: completion can race pause or cancellation.
          if (result is SdrConversionResult.Completed) upload.attempt?.retainStandardized(result.output)
          continuation.resume(result) { _, value, _ ->
            if (value is SdrConversionResult.Completed && upload.attempt == null) value.output.delete()
          }
        }
        continuation.invokeOnCancellation { attempt.cancel() }
      }
    },
) {
  suspend fun prepare(upload: UploadInfo, context: Context,
    standardizedResumeVerified: Boolean = false): PreparedUpload = withContext(Dispatchers.IO) {
    fun original(reason: PreparationDiagnostic) = PreparedUpload.Original(reason)
    if (!upload.isStandardizationRequested()) return@withContext original(PreparationDiagnostic.Original)
    if (!standardizedResumeVerified) return@withContext original(PreparationDiagnostic.StandardizedResumeUnavailable)
    val job = coroutineContext
    val isCancelled = { !job[kotlinx.coroutines.Job]!!.isActive }
    try {
      val metadata = (inspectMetadata(upload.inputFile) as? MetadataInspectionResult.Success)?.inspection
        ?: return@withContext original(PreparationDiagnostic.InspectionFailed).also { StandardInputDiagnostics.inspectionFailed() }
      job.ensureActive()
      val source = inspectSamples(upload.inputFile, metadata, isCancelled)
      job.ensureActive()
      StandardInputDiagnostics.inspection("source", source.status, source.facts, source.sampleCount, source.elapsedNanos)
      val planner = StandardInputPlanner()
      val proposal = planner.propose(source.facts, upload.inputStandardization)
      val capabilities = if (proposal is StandardInputProposal.NeedsCapabilities) {
        inspectCapabilities(proposal.conversion, metadata, source, isCancelled).also { job.ensureActive() }
      } else PlanningCapabilities()
      val plan = planner.plan(proposal, capabilities)
      StandardInputDiagnostics.plan(plan, upload.inputStandardization)
      val action = plan.action
      if (action is StandardInputAction.UploadOriginal) return@withContext original(PreparationDiagnostic.Original)
      val conversion = (action as? StandardInputAction.Convert)?.conversion
        ?: return@withContext original(PreparationDiagnostic.UnsupportedPlan)
      StandardInputDiagnostics.conversionStarted()
      val started = System.nanoTime()
      when (val result = convert(context, upload, metadata, source, conversion)) {
        is SdrConversionResult.Completed -> {
          StandardInputDiagnostics.conversionCompleted(System.nanoTime() - started, result.output.file.length(), result.facts)
          PreparedUpload.Standardized(result.output)
        }
        is SdrConversionResult.Failed -> {
          StandardInputDiagnostics.conversionFailed(System.nanoTime() - started, result.reason)
          original(PreparationDiagnostic.ConversionFailed(result.reason))
        }
        SdrConversionResult.Cancelled -> throw kotlinx.coroutines.CancellationException("Preparation cancelled")
      }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
      catch (e: LinkageError) { StandardInputDiagnostics.unexpectedFailure(e); original(PreparationDiagnostic.InspectionFailed) }
      catch (e: Exception) { StandardInputDiagnostics.unexpectedFailure(e); original(PreparationDiagnostic.InspectionFailed) }
  }
}
