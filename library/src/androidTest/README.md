# SDR conversion fixtures

`SdrConversionFixtureTests` runs the real Media3 adapter and shared output validator. Media stays outside Git and the test APK. With no staged directory, the suite is skipped; a staged directory with a missing fixture fails. Use a dedicated device/emulator and specify its serial on every adb command.

Generate the following fixtures with FFmpeg built with libx264, libx265, AAC, and libmp3lame. These recipes reproduce the scenario contracts, not byte-identical encodes across FFmpeg versions. Record the FFmpeg version, source SHA-256s, API/device/encoder, test output, and any captured generated media in the external evidence directory. The multi-audio first track is stereo, non-default and 3 seconds; the default secondary is mono and 6 seconds. HEVC success remains device-dependent and must pass reinspection.

```sh
SDR_FIXTURES=/absolute/path/to/external/sdr-fixtures
mkdir -p "$SDR_FIXTURES"
ffmpeg -y -f lavfi -i testsrc2=size=1920x1080:rate=30:duration=3 \
  -vf setparams=color_trc=bt709:color_primaries=bt709:colorspace=bt709 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -bf 0 \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$SDR_FIXTURES/no-audio.mp4"
ffmpeg -y -i "$SDR_FIXTURES/no-audio.mp4" \
  -f lavfi -i sine=frequency=440:sample_rate=48000:duration=3 \
  -f lavfi -i sine=frequency=880:sample_rate=48000:duration=6 \
  -filter_complex '[1:a]aformat=channel_layouts=stereo[first]' \
  -map 0:v -map '[first]' -map 2:a -c:v copy -c:a aac \
  -disposition:a:0 0 -disposition:a:1 default "$SDR_FIXTURES/multi-audio.mp4"
ffmpeg -y -i "$SDR_FIXTURES/no-audio.mp4" -c copy \
  -metadata:s:v:0 rotate=90 "$SDR_FIXTURES/rotated.mp4"
ffmpeg -y -i "$SDR_FIXTURES/no-audio.mp4" \
  -f lavfi -i sine=frequency=440:sample_rate=48000:duration=3 \
  -map 0:v -map 1:a -c:v copy -c:a libmp3lame -ac 2 "$SDR_FIXTURES/mp3-stereo.mp4"
ffmpeg -y -f lavfi -i testsrc2=size=512x288:rate=240:duration=2 \
  -vf setparams=color_trc=bt709:color_primaries=bt709:colorspace=bt709 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -bf 0 \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$SDR_FIXTURES/high-rate.mp4"
ffmpeg -y -f lavfi -i testsrc2=size=512x288:rate=60:duration=30 \
  -vf "select='if(lt(t,7.5),1,not(mod(n,3)))',setparams=color_trc=bt709:color_primaries=bt709:colorspace=bt709" -fps_mode vfr \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -bf 0 -g 10000 -keyint_min 10000 -sc_threshold 0 -crf 18 \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$SDR_FIXTURES/vfr.mp4"
ffmpeg -y -i "$SDR_FIXTURES/no-audio.mp4" -c:v libx264 -profile:v baseline -pix_fmt yuv420p -bf 0 \
  -vf setparams=color_trc=linear:color_primaries=bt709:colorspace=bt709 \
  -color_primaries bt709 -color_trc linear -colorspace bt709 "$SDR_FIXTURES/linear.mp4"
for SDR_DURATION in 3 12; do
  if [ "$SDR_DURATION" = 3 ]; then SDR_HEVC_NAME=hevc-small-open-gop; else SDR_HEVC_NAME=hevc-long-open-gop; fi
  ffmpeg -y -f lavfi -i "testsrc2=size=512x288:rate=30:duration=$SDR_DURATION" \
    -vf setparams=color_trc=bt709:color_primaries=bt709:colorspace=bt709 \
    -c:v libx265 -pix_fmt yuv420p -x265-params 'open-gop=1:keyint=30:min-keyint=30:scenecut=0:bframes=0' \
    -color_primaries bt709 -color_trc bt709 -colorspace bt709 "$SDR_FIXTURES/$SDR_HEVC_NAME.mp4"
done
```

Build/install the test APK, then stage fixtures. Choose the serial of your dedicated test device; the commands below do not select an arbitrary connected device.

```sh
SDR_DEVICE=emulator-5556
./gradlew :library:assembleDebugAndroidTest
adb -s "$SDR_DEVICE" install -r library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk
adb -s "$SDR_DEVICE" push "$SDR_FIXTURES/." /data/local/tmp/nat547-fixtures/
adb -s "$SDR_DEVICE" shell run-as com.mux.video.upload.test mkdir -p files/sdr-fixtures
adb -s "$SDR_DEVICE" shell run-as com.mux.video.upload.test cp -r /data/local/tmp/nat547-fixtures/. files/sdr-fixtures/
adb -s "$SDR_DEVICE" shell am instrument -w -r \
  -e class com.mux.video.upload.internal.standardization.SdrConversionFixtureTests \
  com.mux.video.upload.test/androidx.test.runner.AndroidJUnitRunner
```

The H.264 matrix requires accepted, independently reinspected output, including preserved VFR timestamps and first stereo audio selection. LINEAR must fail before export. HEVC may succeed or fail conservatively on an unproven device; a safe failure is not evidence of working HEVC conversion. The cancellation test checks source bytes, tracked output cleanup, and one terminal result. API 23 and physical-device HEVC, quality/performance, and Mux ingest remain separate acceptance gates.
