package com.mux.video.upload.internal

class Resample {
    fun resample(
        data: ByteArray,
        length: Int,
        stereo: Boolean,
        inFrequency: Int,
        outFrequency: Int
    ): ByteArray? {
        if (inFrequency < outFrequency) return upsample(
            data,
            length,
            stereo,
            inFrequency,
            outFrequency
        )
        return if (inFrequency > outFrequency) downsample(
            data,
            length,
            stereo,
            inFrequency,
            outFrequency
        ) else trimArray(data, length)
    }

    /**
     * Basic upsampling algorithm. Uses linear approximation to fill in the
     * missing data.
     *
     * @param data          Input data
     * @param length        The current size of the input array (usually, data.length)
     * @param inputIsStereo True if input is inputIsStereo
     * @param inFrequency   Frequency of input
     * @param outFrequency  Frequency of output
     *
     * @return Upsampled audio data
     */
    private fun upsample(
        data: ByteArray,
        length: Int,
        inputIsStereo: Boolean,
        inFrequency: Int,
        outFrequency: Int
    ): ByteArray? {

        // Special case for no action
        if (inFrequency == outFrequency) return trimArray(data, length)
        val scale = inFrequency.toDouble() / outFrequency.toDouble()
        var pos = 0.0
        val output: ByteArray
        if (!inputIsStereo) {
            output = ByteArray((length / scale).toInt())
            for (i in output.indices) {
                var inPos = pos.toInt()
                var proportion = pos - inPos
                if (inPos >= length - 1) {
                    inPos = length - 2
                    proportion = 1.0
                }
                output[i] =
                    Math.round(data[inPos] * (1 - proportion) + data[inPos + 1] * proportion)
                        .toByte()
                pos += scale
            }
        } else {
            output = ByteArray(2 * (length / 2 / scale).toInt())
            for (i in 0 until output.size / 2) {
                val inPos = pos.toInt()
                var proportion = pos - inPos
                var inRealPos = inPos * 2
                if (inRealPos >= length - 3) {
                    inRealPos = length - 4
                    proportion = 1.0
                }
                output[i * 2] =
                    Math.round(data[inRealPos] * (1 - proportion) + data[inRealPos + 2] * proportion)
                        .toByte()
                output[i * 2 + 1] =
                    Math.round(data[inRealPos + 1] * (1 - proportion) + data[inRealPos + 3] * proportion)
                        .toByte()
                pos += scale
            }
        }
        return output
    }

    /**
     * Basic downsampling algorithm. Uses linear approximation to reduce data.
     *
     * @param data          Input data
     * @param length        The current size of the input array (usually, data.length)
     * @param inputIsStereo True if input is inputIsStereo
     * @param inFrequency   Frequency of input
     * @param outFrequency  Frequency of output
     *
     * @return Downsampled audio data
     */
    private fun downsample(
        data: ByteArray,
        length: Int,
        inputIsStereo: Boolean,
        inFrequency: Int,
        outFrequency: Int
    ): ByteArray? {

        // Special case for no action
        if (inFrequency == outFrequency) return trimArray(data, length)
        val scale = outFrequency.toDouble() / inFrequency.toDouble()
        val output: ByteArray
        var pos = 0.0
        var outPos = 0
        if (!inputIsStereo) {
            var sum = 0.0
            output = ByteArray((length * scale).toInt())
            var inPos = 0
            while (outPos < output.size) {
                val firstVal = data[inPos++].toDouble()
                var nextPos = pos + scale
                if (nextPos >= 1) {
                    sum += firstVal * (1 - pos)
                    output[outPos++] = Math.round(sum).toByte()
                    nextPos -= 1.0
                    sum = nextPos * firstVal
                } else {
                    sum += scale * firstVal
                }
                pos = nextPos
                if (inPos >= length && outPos < output.size) {
                    output[outPos++] = Math.round(sum / pos).toByte()
                }
            }
        } else {
            var sum1 = 0.0
            var sum2 = 0.0
            output = ByteArray(2 * (length / 2 * scale).toInt())
            var inPos = 0
            while (outPos < output.size) {
                val firstVal = data[inPos++].toDouble()
                val nextVal = data[inPos++].toDouble()
                var nextPos = pos + scale
                if (nextPos >= 1) {
                    sum1 += firstVal * (1 - pos)
                    sum2 += nextVal * (1 - pos)
                    output[outPos++] = Math.round(sum1).toByte()
                    output[outPos++] = Math.round(sum2).toByte()
                    nextPos -= 1.0
                    sum1 = nextPos * firstVal
                    sum2 = nextPos * nextVal
                } else {
                    sum1 += scale * firstVal
                    sum2 += scale * nextVal
                }
                pos = nextPos
                if (inPos >= length && outPos < output.size) {
                    output[outPos++] = Math.round(sum1 / pos).toByte()
                    output[outPos++] = Math.round(sum2 / pos).toByte()
                }
            }
        }
        return output
    }

    /**
     * @param data   Data
     * @param length Length of valid data
     *
     * @return Array trimmed to length (or same array if it already is)
     */
    fun trimArray(data: ByteArray, length: Int): ByteArray? {
        if (data.size == length) return data
        val output = ByteArray(length)
        System.arraycopy(output, 0, data, 0, length)
        return output
    }
}