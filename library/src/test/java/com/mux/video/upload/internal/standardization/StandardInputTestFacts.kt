package com.mux.video.upload.internal.standardization

import com.mux.video.upload.internal.InputStandardization
import com.mux.video.upload.internal.MaximumResolution

// Normalized facts shared with the shipped Swift policy/planner tests; no fixture binaries or I/O.
internal fun compliantFacts(
  codec: VideoCodec = VideoCodec.H264,
  dimensions: Dimensions = Dimensions(1920, 1080),
  dynamicRange: DynamicRange = DynamicRange.Sdr,
  pixelFormat: PixelFormat = PixelFormat(8, ChromaSubsampling.Yuv420),
): MediaFacts = MediaFacts(
  videoCodec = known(codec),
  displayDimensions = known(dimensions),
  videoTrackCount = known(1),
  frameRate = known(30.0),
  averageBitrate = known(4_000_000L),
  maximumGopBitrate = known(5_000_000L),
  maximumKeyframeIntervalSeconds = known(2.0),
  gopStructure = known(GopStructure.ClosedWithIdr),
  pixelFormat = known(pixelFormat),
  dynamicRange = known(dynamicRange),
  audioTracks = known(listOf(AudioTrack(known(AudioFormat.Aac(AudioChannelLayout.Stereo))))),
  editList = known(EditList.Simple),
)

internal fun hdrFacts(range: DynamicRange = DynamicRange.Hlg): MediaFacts = compliantFacts(
  codec = VideoCodec.Hevc, dynamicRange = range, pixelFormat = PixelFormat(10, ChromaSubsampling.Yuv420),
)

internal fun fullCapabilities() = PlanningCapabilities(
  sourceIsDecodable = true,
  sourceTimelineIsProven = true,
  encodableVideoCodecs = setOf(VideoCodec.H264, VideoCodec.Hevc),
  remediableRequirements = PolicyRequirement.entries.toSet(),
  toneMappableDynamicRanges = setOf(DynamicRange.Hlg, DynamicRange.Pq),
  canPrepareCompliantAacAudio = true,
)

internal fun <T> known(value: T): MediaFact<T> = MediaFact.Known(value)
internal fun selection(resolution: MaximumResolution = MaximumResolution.Default) = PolicySelection(resolution)
internal fun options(resolution: MaximumResolution = MaximumResolution.Default) = InputStandardization(true, resolution)

/** Complete video color evidence for opt-in HDR adapter/preflight tests; no platform calls. */
internal fun hdrMetadata(facts: MediaFacts): MediaMetadataInspection {
  val codec = facts.videoCodec.valueOrNull!!
  val profile = if (codec == VideoCodec.Hevc) VideoProfile.HevcMain10 else VideoProfile.H264High
  val color = ColorMetadata(known(android.media.MediaFormat.COLOR_STANDARD_BT2020),
    known(if (facts.dynamicRange.valueOrNull == DynamicRange.Hlg) android.media.MediaFormat.COLOR_TRANSFER_HLG
      else android.media.MediaFormat.COLOR_TRANSFER_ST2084), known(android.media.MediaFormat.COLOR_RANGE_LIMITED))
  val video = VideoTrackMetadata(facts.videoCodec, known(profile), facts.displayDimensions, facts.displayDimensions,
    known(0), known(0), facts.pixelFormat, color, facts.dynamicRange)
  return MediaMetadataInspection(known(ContainerKind.IsoBaseMedia), listOf(TrackMetadata(0, known(0), known(1),
    TrackKind.Video, known(if (codec == VideoCodec.Hevc) "video/hevc" else "video/avc"), known(3.0), facts.frameRate,
    MediaFact.Unknown, video = video)), facts, 0)
}
