package com.mux.video.upload.internal.standardization

import com.mux.video.upload.internal.MaximumResolution

internal enum class AcceptanceTier { UpTo1080p, HighResolution }
internal enum class MediaRole { SourceInput, GeneratedOutput }
internal enum class PolicyStatus { Compliant, NonCompliant, Unknown }
internal enum class PolicyRequirement {
  VideoCodec, VideoResolution, FrameRate, AverageBitrate, MaximumGopBitrate,
  KeyframeInterval, GopStructure, PixelFormat, DynamicRange, Audio, EditList,
}

internal data class PolicySelection(
  val acceptanceTier: AcceptanceTier,
  val generatedOutputDimensions: Dimensions,
) {
  constructor(maximumResolution: MaximumResolution) : this(
    acceptanceTier = when (maximumResolution) {
      MaximumResolution.Preset2560x1440, MaximumResolution.Preset3840x2160 -> AcceptanceTier.HighResolution
      else -> AcceptanceTier.UpTo1080p
    },
    generatedOutputDimensions = Dimensions(maximumResolution.width, maximumResolution.height),
  )
}

internal data class TierLimits(
  val maximumSourceDimension: Int,
  val frameRateRange: ClosedFloatingPointRange<Double>,
  val maximumAverageBitrate: Long,
  val maximumGopBitrate: Long?,
  val maximumKeyframeIntervals: Map<VideoCodec, Double>,
)

/** Published acceptance ceilings, separate from encoder targets. Boundaries match shipped Swift. */
internal data class StandardInputPolicyProfile(
  val upTo1080p: TierLimits,
  val highResolution: TierLimits,
) {
  companion object {
    val PublishedMux = StandardInputPolicyProfile(
      upTo1080p = TierLimits(2048, 5.0..120.0, 8_000_000, 16_000_000,
        mapOf(VideoCodec.H264 to 20.0, VideoCodec.Hevc to 10.0)),
      highResolution = TierLimits(4096, 5.0..60.0, 20_000_000, null,
        mapOf(VideoCodec.H264 to 10.0, VideoCodec.Hevc to 6.0)),
    )
  }
}

internal data class PolicyEvaluation(val checks: Map<PolicyRequirement, PolicyStatus>) {
  val outcome: PolicyStatus get() = when {
    PolicyStatus.NonCompliant in checks.values -> PolicyStatus.NonCompliant
    PolicyStatus.Unknown in checks.values -> PolicyStatus.Unknown
    else -> PolicyStatus.Compliant
  }
  val nonCompliantRequirements: Set<PolicyRequirement>
    get() = checks.filterValues { it == PolicyStatus.NonCompliant }.keys
  val unknownRequirements: Set<PolicyRequirement>
    get() = checks.filterValues { it == PolicyStatus.Unknown }.keys
}

internal class StandardInputPolicyEvaluator(
  val profile: StandardInputPolicyProfile = StandardInputPolicyProfile.PublishedMux,
) {
  fun applicableLimits(dimensions: MediaFact<Dimensions>, selection: PolicySelection): List<TierLimits> {
    if (selection.acceptanceTier == AcceptanceTier.UpTo1080p) return listOf(profile.upTo1080p)
    val size = dimensions.valueOrNull?.takeIf { it.isValid }
      ?: return listOf(profile.upTo1080p, profile.highResolution)
    return listOf(if (size.longSide <= profile.upTo1080p.maximumSourceDimension) profile.upTo1080p
      else profile.highResolution)
  }

  fun evaluate(
    facts: MediaFacts,
    selection: PolicySelection,
    role: MediaRole = MediaRole.SourceInput,
  ): PolicyEvaluation {
    val evaluations = applicableLimits(facts.displayDimensions, selection).map { evaluate(facts, selection, role, it) }
    if (evaluations.size == 1) return evaluations.single()
    // An unknown size cannot choose a tier. A check is proven only when both possible tiers agree.
    return PolicyEvaluation(PolicyRequirement.entries.associateWith { requirement ->
      val statuses = evaluations.map { it.checks.getValue(requirement) }.distinct()
      statuses.singleOrNull() ?: PolicyStatus.Unknown
    })
  }

  private fun evaluate(
    facts: MediaFacts,
    selection: PolicySelection,
    role: MediaRole,
    limits: TierLimits,
  ): PolicyEvaluation {
    val codec = facts.videoCodec.valueOrNull
    val intervalLimit = limits.maximumKeyframeIntervals[codec]
    return PolicyEvaluation(linkedMapOf(
      PolicyRequirement.VideoCodec to check(facts.videoCodec) { it != VideoCodec.Other },
      PolicyRequirement.VideoResolution to dimensions(facts.displayDimensions, selection, limits, role),
      PolicyRequirement.FrameRate to positiveFinite(facts.frameRate) {
        it in limits.frameRateRange
      },
      PolicyRequirement.AverageBitrate to positive(facts.averageBitrate) { it <= limits.maximumAverageBitrate },
      PolicyRequirement.MaximumGopBitrate to (limits.maximumGopBitrate?.let { maximum ->
        positive(facts.maximumGopBitrate) { it <= maximum }
      } ?: PolicyStatus.Compliant),
      PolicyRequirement.KeyframeInterval to (intervalLimit?.let { maximum ->
        positiveFinite(facts.maximumKeyframeIntervalSeconds) { it <= maximum }
      } ?: PolicyStatus.Unknown),
      PolicyRequirement.GopStructure to check(facts.gopStructure) { it == GopStructure.ClosedWithIdr },
      PolicyRequirement.PixelFormat to pixelFormat(facts),
      PolicyRequirement.DynamicRange to dynamicRange(facts),
      PolicyRequirement.Audio to audio(facts.audioTracks),
      PolicyRequirement.EditList to check(facts.editList) { it != EditList.Complex },
    ))
  }

  private fun dimensions(
    fact: MediaFact<Dimensions>, selection: PolicySelection, limits: TierLimits, role: MediaRole,
  ): PolicyStatus {
    val size = fact.valueOrNull?.takeIf { it.isValid } ?: return PolicyStatus.Unknown
    return status(when (role) {
      MediaRole.SourceInput -> size.longSide <= limits.maximumSourceDimension
      MediaRole.GeneratedOutput -> size.fitsWithin(selection.generatedOutputDimensions)
    })
  }

  private fun dynamicRange(facts: MediaFacts): PolicyStatus = when (facts.dynamicRange.valueOrNull) {
    null -> PolicyStatus.Unknown
    DynamicRange.Sdr -> PolicyStatus.Compliant
    DynamicRange.DolbyVision, DynamicRange.OtherHdr -> PolicyStatus.NonCompliant
    DynamicRange.Hlg, DynamicRange.Pq -> {
      val codec = facts.videoCodec.valueOrNull
      val pixel = facts.pixelFormat.valueOrNull
      if (codec == null || pixel == null) PolicyStatus.Unknown else status(
        codec == VideoCodec.Hevc && pixel == PixelFormat(10, ChromaSubsampling.Yuv420))
    }
  }

  private fun pixelFormat(facts: MediaFacts): PolicyStatus {
    val codec = facts.videoCodec.valueOrNull ?: return PolicyStatus.Unknown
    val format = facts.pixelFormat.valueOrNull ?: return PolicyStatus.Unknown
    val supportedDepth = when (codec) {
      VideoCodec.H264 -> format.bitDepth == 8
      VideoCodec.Hevc -> format.bitDepth == 8 || format.bitDepth == 10
      VideoCodec.Other -> false
    }
    return status(supportedDepth && format.chromaSubsampling == ChromaSubsampling.Yuv420)
  }

  private fun audio(fact: MediaFact<List<AudioTrack>>): PolicyStatus {
    val tracks = fact.valueOrNull ?: return PolicyStatus.Unknown
    if (tracks.isEmpty()) return PolicyStatus.Compliant
    if (tracks.size > 1) return PolicyStatus.Unknown
    return check(tracks.single().format) { it is AudioFormat.Aac && it.layout != AudioChannelLayout.Other }
  }

  private fun <T> check(fact: MediaFact<T>, predicate: (T) -> Boolean): PolicyStatus =
    fact.valueOrNull?.let { status(predicate(it)) } ?: PolicyStatus.Unknown

  private fun positive(fact: MediaFact<Long>, predicate: (Long) -> Boolean): PolicyStatus =
    fact.valueOrNull?.takeIf { it > 0 }?.let { status(predicate(it)) } ?: PolicyStatus.Unknown

  private fun positiveFinite(fact: MediaFact<Double>, predicate: (Double) -> Boolean): PolicyStatus =
    fact.valueOrNull?.takeIf { it.isFinite() && it > 0 }?.let { status(predicate(it)) } ?: PolicyStatus.Unknown

  private fun status(compliant: Boolean): PolicyStatus =
    if (compliant) PolicyStatus.Compliant else PolicyStatus.NonCompliant
}
