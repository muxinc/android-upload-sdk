# Mux's Android Upload SDK

This SDK makes it easy to upload videos to Mux from an Android app. It handles large files by
breaking them into chunks and uploads each chunk individually. Each file that gets uploaded will get
sent to
an [upload URL created by a backend server](https://docs.mux.com/guides/video/upload-files-directly)
. **Do not include credentials to create an upload URL from an app.**

## Build requirements

Android API 23+, Kotlin 2.1+, AGP 8.10+, and `compileSdk` 36+.

## Usage

To use this SDK, you must first add it as a dependency. The Mux Android Upload SDK is
available via gradle

### Add the SDK to your app

#### Add the Mux maven repository

Add Mux's maven repository to your project. Depending on your project configuration you may do this
in either `settings.gradle` or `build.gradle`.

```groovy
repositories {
  maven {
    url "https://muxinc.jfrog.io/artifactory/default-maven-release-local"
  }
}
```

### Add a dependency on our SDK

```groovy
implementation "com.mux.video:upload:1.1.0"
```

### Server-Side: Create a Direct Upload

To start an upload, you must first create
an [upload URL](https://docs.mux.com/guides/video/upload-files-directly). Then, pass return that
direct-upload PUT URL to your app, so the app can begin the upload process

### App-Side: Use the SDK

#### Initialize the Upload SDK

The Upload SDK must be initialized at least once from your app in order to work properly. If you try
to start or manage uploads without initializing the SDK, it will crash. The SDK does not hold a
long-lived reference to the context you pass in, so any Context should be suitable.

```kotlin
// In, eg, a custom Application.onCreate()
MuxUploadSdk.initialize(appContext = this)
```

#### Start your Upload

Once you've initialized the SDK and created
your [upload URL](https://docs.mux.com/guides/video/upload-files-directly), you can create
a `MuxUpload` for your video file, that uploads to that URL. After that you just call start()

Uploads created this way can be paused or resumed at will, even after the app is killed by the
system

```kotlin
fun beginUpload(myUploadUrl: String) {
  val upl = MuxUpload.Builder(myUploadUrl, myVideoFile).build()
  upl.addProgressListener { innerUploads.postValue(uploadList) }
  upl.addResultListener {
    if (it.isSuccess) {
      notifyUploadSuccess()
    } else {
      notifyUploadFail()
    }
  }
  upl.start()
}
```

### Input standardization

Standardization is enabled by default, with a 1080p maximum generated output and eligible HDR preserved. Compliant H.264 and HEVC inputs upload without re-encoding; supported conversions preserve the codec family and never upscale.

Choose 1440p or 2160p explicitly:

```kotlin
import com.mux.video.upload.api.HdrHandling
import com.mux.video.upload.internal.MaximumResolution

val upload = MuxUpload.Builder(uploadUrl, videoFile)
  .standardizationRequested(true, MaximumResolution.Preset3840x2160)
  .hdrHandling(HdrHandling.Preserve)
  .build()
```

Use `Preset2560x1440` for 1440p. Configure the matching `new_asset_settings.max_resolution_tier` (`"1440p"` or `"2160p"`) separately when creating the Direct Upload on your server.

To request HDR-to-SDR tone mapping, use `HdrHandling.ToneMapToSDR`. Support depends on the input and device; HDR preservation does not guarantee HDR playback. Unsupported or failed preparation uploads the original unless canceled. Files with multiple audio tracks also upload unchanged.

To see why a file was converted or uploaded unchanged, enable SDK logging with `MuxUploadSdk.useLogger(MuxUploadSdk.logcatLogger())`. Decisions are logged under the `MuxUploadStandardization` tag, for example `standard_input event=plan_selected plan=convert ...`.

#### Try it out

An example use case can be found in our [Example App](app/).

A full, secure uploading mechanism requires a backend, but the Example App fakes this for brevity.
To use the Example App, you must create
an [Access Token](https://dashboard.mux.com/settings/access-tokens)
in our Mux service, and put those credentials in the example
app's [FakeBackend class](app/src/main/java/com/mux/video/vod/demo/backend/ImaginaryBackend.kt)
