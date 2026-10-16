package com.mux.video.upload.internal.standardization

import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray

internal data class CodecMetadata(
  val pixelFormat: MediaFact<PixelFormat> = MediaFact.Unknown,
  val color: ColorMetadata = ColorMetadata(),
)

/** Fills missing pixel/color facts only; platform metadata is authoritative when present. */
internal object CodecMetadataReader {
  const val MAX_CONFIGURATION_BYTES = 64 * 1024

  fun video(codec: VideoCodec?, configurations: List<ByteArray>): CodecMetadata {
    if (configurations.sumOf { it.size.toLong() } > MAX_CONFIGURATION_BYTES) return CodecMetadata()
    return try {
      val results = configurations.flatMap(::annexBNalUnits).mapNotNull { nal ->
        when {
          codec == VideoCodec.H264 && nal.isNotEmpty() && nal[0].toInt() and 31 == 7 -> avc(nal)
          codec == VideoCodec.Hevc && nal.size >= 2 && (nal[0].toInt() and 126) shr 1 == 33 -> hevc(nal)
          else -> null
        }
      }
      // Several SPS descriptions may signal changing formats; do not choose an arbitrary one.
      results.distinct().singleOrNull() ?: CodecMetadata()
    } catch (_: LinkageError) { CodecMetadata() }
      catch (_: RuntimeException) { CodecMetadata() }
  }

  private fun avc(nal: ByteArray): CodecMetadata {
    val sps = NalUnitUtil.parseSpsNalUnit(nal, 0, nal.size)
    val bits = ParsableNalUnitBitArray(nal, 1, nal.size)
    val profile = bits.readBits(8)
    bits.skipBits(16)
    bits.readUnsignedExpGolombCodedInt() // seq_parameter_set_id
    val chroma = when (profile) {
      66, 77, 88 -> 1
      100, 110, 122, 244, 44, 83, 86, 118, 128, 138 -> bits.readUnsignedExpGolombCodedInt()
      else -> return CodecMetadata()
    }
    return CodecMetadata(
      pixelFormat = pixels(sps.bitDepthLumaMinus8, sps.bitDepthChromaMinus8, chroma),
      color = color(sps.colorSpace, sps.colorTransfer, sps.colorRange),
    )
  }

  private fun hevc(nal: ByteArray): CodecMetadata {
    // Single-layer SPS only; multilayer configuration needs separate evidence.
    if ((nal[0].toInt() and 1) != 0 || (nal[1].toInt() and 248) != 0) return CodecMetadata()
    if (!boundedHevcSyntax(nal)) return CodecMetadata()
    val sps = NalUnitUtil.parseH265SpsNalUnit(nal, 0, nal.size, null)
    return CodecMetadata(
      pixelFormat = pixels(sps.bitDepthLumaMinus8, sps.bitDepthChromaMinus8, sps.chromaFormatIdc),
      color = color(sps.colorSpace, sps.colorTransfer, sps.colorRange),
    )
  }

  /** Bound allocation-driving SPS fields before calling the shared parser. */
  private fun boundedHevcSyntax(nal: ByteArray): Boolean {
    val bits = ParsableNalUnitBitArray(nal, 2, nal.size)
    bits.skipBits(4)
    val layers = bits.readBits(3)
    if (layers > 6) return false
    bits.skipBit()
    bits.skipBits(96) // general profile/tier/level
    val profilePresent = BooleanArray(layers)
    val levelPresent = BooleanArray(layers)
    for (i in 0 until layers) {
      profilePresent[i] = bits.readBit()
      levelPresent[i] = bits.readBit()
    }
    if (layers > 0) bits.skipBits((8 - layers) * 2)
    for (i in 0 until layers) {
      if (profilePresent[i]) bits.skipBits(88)
      if (levelPresent[i]) bits.skipBits(8)
    }
    if (bits.readUnsignedExpGolombCodedInt() !in 0..15) return false
    val chroma = bits.readUnsignedExpGolombCodedInt()
    if (chroma !in 0..3) return false
    if (chroma == 3) bits.skipBit()
    repeat(2) { if (bits.readUnsignedExpGolombCodedInt() !in 1..65535) return false }
    if (bits.readBit()) repeat(4) { bits.readUnsignedExpGolombCodedInt() }
    repeat(2) { if (bits.readUnsignedExpGolombCodedInt() !in 0..8) return false }
    val pocBits = bits.readUnsignedExpGolombCodedInt()
    if (pocBits !in 0..12) return false
    val start = if (bits.readBit()) 0 else layers
    for (i in start..layers) repeat(3) { bits.readUnsignedExpGolombCodedInt() }
    repeat(6) { bits.readUnsignedExpGolombCodedInt() }
    if (bits.readBit() && bits.readBit()) {
      for (sizeId in 0..3) {
        for (matrixId in 0..5 step if (sizeId == 3) 3 else 1) {
          if (!bits.readBit()) bits.readUnsignedExpGolombCodedInt() else {
            if (sizeId > 1) bits.readSignedExpGolombCodedInt()
            repeat(minOf(64, 1 shl (4 + 2 * sizeId))) { bits.readSignedExpGolombCodedInt() }
          }
        }
      }
    }
    bits.skipBits(2)
    if (bits.readBit()) {
      bits.skipBits(8)
      repeat(2) { bits.readUnsignedExpGolombCodedInt() }
      bits.skipBit()
    }
    val sets = bits.readUnsignedExpGolombCodedInt()
    if (sets !in 0..64) return false
    var previousCount = 0
    for (i in 0 until sets) {
      if (i > 0 && bits.readBit()) {
        // Media3 derives predicted sets. Only a final predicted set can be checked without
        // duplicating those calculations; longer predicted chains remain unknown.
        if (i != sets - 1) return false
        bits.skipBit()
        bits.readUnsignedExpGolombCodedInt()
        repeat(previousCount + 1) { if (!bits.readBit()) bits.skipBit() }
      } else {
        val negative = bits.readUnsignedExpGolombCodedInt()
        val positive = bits.readUnsignedExpGolombCodedInt()
        if (negative !in 0..16 || positive !in 0..16 || negative + positive > 16) return false
        previousCount = negative + positive
        repeat(previousCount) {
          bits.readUnsignedExpGolombCodedInt()
          bits.skipBit()
        }
      }
    }
    if (bits.readBit()) {
      val count = bits.readUnsignedExpGolombCodedInt()
      if (count !in 0..32) return false
      repeat(count) { bits.skipBits(pocBits + 5) }
    }
    return true
  }

  private fun pixels(luma: Int, chroma: Int, subsampling: Int): MediaFact<PixelFormat> =
    if (luma != chroma || luma !in 0..8 || subsampling !in 0..3) MediaFact.Unknown
    else MediaFact.Known(PixelFormat(luma + 8,
      if (subsampling == 1) ChromaSubsampling.Yuv420 else ChromaSubsampling.Other))

  private fun color(standard: Int, transfer: Int, range: Int) = ColorMetadata(
    positiveCode(standard), positiveCode(transfer), positiveCode(range))

  /** MediaExtractor supplies AVC/HEVC CSD in Annex B form, with three- or four-byte prefixes. */
  private fun annexBNalUnits(data: ByteArray): List<ByteArray> {
    val units = mutableListOf<ByteArray>()
    val flags = BooleanArray(3)
    var prefix = NalUnitUtil.findNalUnit(data, 0, data.size, flags)
    while (prefix < data.size) {
      val payload = prefix + 3
      NalUnitUtil.clearPrefixFlags(flags)
      val next = NalUnitUtil.findNalUnit(data, payload, data.size, flags)
      // A four-byte prefix includes a leading zero outside the three-byte start code.
      val end = if (next > payload && next < data.size && data[next - 1] == 0.toByte()) next - 1 else next
      if (payload < end) units.add(data.copyOfRange(payload, end))
      prefix = next
    }
    return units
  }

}
