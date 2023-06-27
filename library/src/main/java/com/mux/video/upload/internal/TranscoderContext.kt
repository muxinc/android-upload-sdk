package com.mux.video.upload.internal

import android.content.Context
import android.content.ContextWrapper
import android.media.*
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.os.Environment
import android.util.Log
import io.github.crow_misia.libyuv.FilterMode
import io.github.crow_misia.libyuv.Nv12Buffer
import java.io.File
import java.nio.ByteBuffer
import java.util.*


class TranscoderContext internal constructor(
    private val uploadInfo: UploadInfo, private val appContext: Context
) {
    private val extractor: MediaExtractor = MediaExtractor()
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var outputVideoTrackIndex = -1
    private var outputAudioTrackIndex = -1
    // Used to configure decoders
    private var inputAudioFormat: MediaFormat? = null
    private var inputVideoFormat: MediaFormat? = null

    // Used to configure encoders
    private var outputAudioFormat: MediaFormat? = null
    private var outputVideoFormat: MediaFormat? = null

    // This is what decoder actually provide as an output, bit different then what we used to configure it
    private var videoDecoderOutputFormat: MediaFormat? = null
    private var decodedFrameWidth: Int = -1;
    private var decodedFrameHeight: Int = -1;
    private val targetWidth = 640
    private val targetedHeight = 480
    private var originalSizeYuv:Nv12Buffer? = null;
    private var scaledSizeYuv: Nv12Buffer = Nv12Buffer.allocate(targetWidth, targetedHeight);

    // Wait indefinetly for negative value, exit imidetly on 0, or timeout after a given us+
    private var dequeueTimeout:Long = 0;
    private var finished: Boolean = false;
    private var transcodeAudio = false;
    private var numberOfDecodedFrames = 0;
    private var numberOfEncodedFrames = 0;
    private var numberOfInputFrames = -1;

    private var videoDecoder:MediaCodec? = null
    private var audioDecoder:MediaCodec? = null
    private var videoEncoder:MediaCodec? = null
    private var audioEncoder:MediaCodec? = null

    init {
        val cacheDir = File(appContext.cacheDir, "mux-upload")
        cacheDir.mkdirs()
        val destFile = File(cacheDir, UUID.randomUUID().toString() + ".mp4")
//        destFile.createNewFile()
        muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        extractor.setDataSource(uploadInfo.file.absolutePath)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.lowercase()?.contains("video") == true) {
                videoTrackIndex = i;
                inputVideoFormat = format;
            }
            if (mime?.lowercase()?.contains("audio") == true) {
                audioTrackIndex = i;
                inputAudioFormat = format;
            }
        }
        configureDecoders()
    }

    fun getHWCapableEncoders(mimeType: String): ArrayList<MediaCodecInfo> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS);
        var result:ArrayList<MediaCodecInfo> = ArrayList<MediaCodecInfo>();
        for(codecInfo in list.codecInfos) {
            Log.i("CodecInfo", codecInfo.name)
            if(codecInfo.name.contains(mimeType) && codecInfo.isEncoder && codecInfo.isHardwareAccelerated) {
                result.add(codecInfo);
            }
        }
        return result;
    }

    fun checkIfTranscodingIsNeeded() {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            uploadInfo.inputFileFormat = format.getString("file-format");
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.lowercase()?.contains("video") == true) {
                val width:Int = format.getInteger(MediaFormat.KEY_WIDTH)
                val height:Int = format.getInteger(MediaFormat.KEY_HEIGHT)
                // Check if resolution is greater then 720p
                if (width > 1280) {
                    uploadInfo.shouldStandardize = true;
                }
                // Check if compersion is h264
                if (!mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                    uploadInfo.shouldStandardize = true;
                }
                // TODO check if the compression level is inapropriate, not sure what compression
                // AVC/High level is standard
            }
        }
    }

    private fun configureDecoders() {
        // Init decoders and encoders
        numberOfInputFrames = inputVideoFormat!!.getInteger("frame-count", -1)
        videoDecoder =
            MediaCodec.createDecoderByType(inputVideoFormat!!.getString(MediaFormat.KEY_MIME)!!)
        videoDecoder!!.configure(inputVideoFormat, null, null, 0)
        videoDecoder!!.start()
        if (transcodeAudio) {
            audioDecoder =
                MediaCodec.createDecoderByType(inputAudioFormat!!.getString(MediaFormat.KEY_MIME)!!)
            audioDecoder!!.configure(inputAudioFormat, null, null, 0)
            audioDecoder!!.start()
        }
    }

    private fun configureEncoders() {
        // We will need this when we apply the image resize
        decodedFrameWidth = videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_WIDTH)
        if (videoDecoderOutputFormat!!.containsKey(MediaFormat.KEY_CROP_LEFT)
            && videoDecoderOutputFormat!!.containsKey(MediaFormat.KEY_CROP_RIGHT)
        ) {
            decodedFrameWidth = (videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_CROP_RIGHT) + 1
                    - videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_CROP_LEFT))
        }
        decodedFrameHeight = videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_HEIGHT)
        if (videoDecoderOutputFormat!!.containsKey(MediaFormat.KEY_CROP_TOP)
            && videoDecoderOutputFormat!!.containsKey(MediaFormat.KEY_CROP_BOTTOM)
        ) {
            decodedFrameHeight = (videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_CROP_BOTTOM) + 1
                    - videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_CROP_TOP))
        }
        originalSizeYuv = Nv12Buffer.allocate(
            videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_WIDTH),
            videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_HEIGHT))

        outputVideoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetWidth, targetedHeight);
        // Copy all color details from decoder output format to avoid color conversion
        outputVideoFormat!!.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            COLOR_FormatYUV420SemiPlanar)
//        outputVideoFormat!!.setInteger(MediaFormat.KEY_COLOR_FORMAT,
//            videoDecoderOutputFormat!!.getInteger(MediaFormat.KEY_COLOR_FORMAT))

        outputVideoFormat!!.setInteger(MediaFormat.KEY_ROTATION, inputVideoFormat!!.getInteger(MediaFormat.KEY_ROTATION))
        outputVideoFormat!!.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            30
        )
        outputVideoFormat!!.setInteger("slice-height", targetedHeight + targetedHeight/2);
        outputVideoFormat!!.setInteger("stride", targetWidth);
        outputVideoFormat!!.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 30);
        outputVideoFormat!!.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        outputVideoFormat!!.setInteger(MediaFormat.KEY_BIT_RATE, 2516370)
        // configure output audio format, if input format is already AAC, then just do copy
        transcodeAudio = !inputAudioFormat!!.getString(MediaFormat.KEY_MIME)?.contains(MediaFormat.MIMETYPE_AUDIO_AAC)!!
        if (transcodeAudio) {
            outputAudioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            outputAudioFormat!!.setString(MediaFormat.KEY_AAC_PROFILE, "2")
            outputAudioFormat!!.setString(MediaFormat.KEY_PROFILE, "2")
        } else {
            outputAudioFormat = inputAudioFormat
        }

        val encoders = getHWCapableEncoders("avc")
        for (encoder in encoders) {
            try {
                if (encoder.name.contains("OMX")) {
                    // TODO use this as a backup, it is struggeling to provide a high profile compression
                    continue;
                }
                val codecCap = encoder.getCapabilitiesForType("video/avc")
                for  (profile in codecCap.profileLevels ) {
                    if (profile.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh ) {
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_PROFILE, profile.profile);
                        outputVideoFormat!!.setInteger(MediaFormat.KEY_LEVEL, profile.level)
                        break
                    }
                }
                outputVideoFormat!!.setInteger(MediaFormat.KEY_QUALITY, codecCap.encoderCapabilities.qualityRange.upper)
                videoEncoder = MediaCodec.createByCodecName(encoder.name)
                videoEncoder!!.configure(outputVideoFormat,null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                break;
            } catch (err:java.lang.Exception) {
                err.printStackTrace();
            }
        }
        videoEncoder!!.start()
        if (transcodeAudio) {
            audioEncoder =
                MediaCodec.createEncoderByType(outputAudioFormat!!.getString(MediaFormat.KEY_MIME)!!)
            audioEncoder!!.configure(outputAudioFormat, null, null, 0)
            audioEncoder!!.start()
        }
    }

    private fun configureMuxer() {
        outputVideoTrackIndex = muxer!!.addTrack(outputVideoFormat!!)
        outputAudioTrackIndex = muxer!!.addTrack(outputAudioFormat!!)
        muxer!!.start()
    }

    fun start() {
        var frameNumber:Int = 0;
        var videoPtsUs:Long = 0;
        var audioPtsUs:Long = 0;
        val cw = ContextWrapper(appContext)
        val directory = cw.getExternalFilesDir(Environment.DIRECTORY_DCIM)
        val testFile = File(directory, "output.h264")
        val output = testFile.outputStream()
        val started = System.currentTimeMillis()
        try {
            while (!finished) {
                val videoFrames = getVideoFrames()
                for (frame in videoFrames) {
                    Log.i("Muxer", "Writing h264 frame, size: " + frame.info.size)
                    output.write(
                        frame.buff.array(),
                        frame.info.offset,
                        frame.info.size
                    )
                    frame.release()
                }
//            if (videoPtsUs <= audioPtsUs) {
//                // Mux next video frame
//                val videoFrame = getNextVideoFrame();
//                if (decodedVideoFrame != null) {
//                    videoPtsUs = decodedVideoFrame.info.presentationTimeUs;
//                }
//            } else {
//                // Mux next audio frame
//                val audioFrame = getNextAudioFrame();
//            }
            }
        } catch (err:Exception) {
            err.printStackTrace()
        }
        val duration = System.currentTimeMillis() - started
        Log.e("Muxer", "Transcoding duration time: " + duration)
        muxer!!.stop()
        muxer!!.release()
    }

    private fun getVideoFrames(): ArrayList<AVFrame> {
        val decoded = decodeNextVideoFrame()
        var result:ArrayList<AVFrame> = ArrayList<AVFrame>()
        if (decoded != null) {
            result = encodeVideoFrame(decoded);
            decoded.release()
        }
        return result;
    }

    private fun getNextAudioFrame(): AVFrame? {
        return null;
    }

    private fun encodeVideoFrame(rawInput:AVFrame): ArrayList<AVFrame> {
        val result:ArrayList<AVFrame> = ArrayList<AVFrame>();
        val inIndex: Int = videoEncoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            // Scale input to match output
            originalSizeYuv!!.asBuffer().put(rawInput.buff)
            originalSizeYuv!!.scale(scaledSizeYuv, FilterMode.BILINEAR)
            Log.i("Encoder", "Y size: " + scaledSizeYuv.planeY.rowStride
                    + ", width: " + scaledSizeYuv.width + ", heigth: " + scaledSizeYuv.height
                    + "UV stride: " + scaledSizeYuv.planeUV.rowStride)
            val buffer: ByteBuffer = videoEncoder!!.getInputBuffer(inIndex)!!;
            buffer.clear()
            scaledSizeYuv.write(buffer)

            val outputFormat = videoEncoder!!.inputFormat

//            val cw = ContextWrapper(appContext)
//            val directory = cw.getExternalFilesDir(Environment.DIRECTORY_DCIM)
//            val testFile = File(directory, "scaled" + numberOfDecodedFrames + ".raw")
//            val output = testFile.outputStream()
//            scaledSizeYuv.write(output)
            videoEncoder!!.queueInputBuffer(inIndex, 0,
                buffer.capacity(), rawInput.info.presentationTimeUs, 0)
        }

        var info = BufferInfo()
        var outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        var outputBuffer:ByteBuffer?
        while (outIndex >= 0) {
            outputBuffer = videoEncoder!!.getOutputBuffer(outIndex)
            val outData = ByteArray(info.size)
            outputBuffer!!.get(outData, info.offset, info.size)
            info.offset = 0
            numberOfEncodedFrames++
            result.add(AVFrame(outIndex, ByteBuffer.wrap(outData), info, videoEncoder!!, false))
            videoEncoder!!.releaseOutputBuffer(outIndex, false)
            info = BufferInfo()
            outIndex = videoEncoder!!.dequeueOutputBuffer(info, dequeueTimeout)
        }
        return result;
    }

    private fun encodeAudioFrame() {

    }

    private fun decodeNextVideoFrame():AVFrame? {
        extractor.selectTrack(videoTrackIndex);
        val inIndex: Int = videoDecoder!!.dequeueInputBuffer(dequeueTimeout)
        if (inIndex >= 0) {
            val buffer: ByteBuffer = videoDecoder!!.getInputBuffer(inIndex)!!;
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                // We have reached the end of video
                finished = true;
                return null
            } else {
                videoDecoder!!.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }
        }
        val info = BufferInfo()
        var outputBuffer:ByteBuffer? = null
        val outIndex:Int = videoDecoder!!.dequeueOutputBuffer(info, dequeueTimeout);
        if (outIndex >= 0) {
            outputBuffer = videoDecoder!!.getOutputBuffer(outIndex);
        }
        when (outIndex) {
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                // This give us real image height, to avoid corruptions in video
                videoDecoderOutputFormat = videoDecoder!!.outputFormat;
                configureEncoders()
                configureMuxer()
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                // Timedout also not good
            }
        }
        return if (outputBuffer == null) {
            null;
        } else {
            numberOfDecodedFrames++;
            AVFrame(outIndex, outputBuffer, info, videoDecoder!!, true)
        }
    }

//    class EncodedFrame constructor(index:Int, buff:ByteBuffer, info:BufferInfo, codec:MediaCodec)
//        : AVFrame(index, buff, info, codec) {
//
//    }

    open class AVFrame constructor(index:Int, buff:ByteBuffer, info:BufferInfo, codec:MediaCodec, shouldRelease:Boolean){
        val index = index
        val buff = buff
        val info = info
        val codec = codec;
        var shouldRelease = shouldRelease;

        fun release() {
            if (shouldRelease) {
                codec.releaseOutputBuffer(index, false);
            }
        }
    }
}