# Mux's Android Upload SDK

This SDK makes it easy to upload videos to Mux from an Android app. It handles large files by
breaking them into chunks and uploads each chunk individually. Each file that gets uploaded will get
sent to
an [upload URL created by a backend server](https://docs.mux.com/guides/video/upload-files-directly)
. **Do not include credentials to create an upload URL from an app.**

## Usage

To use this SDK, you must first add it as a dependency. The Mux Android Swift Upload SDK is
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
implememntation "com.mux.video:upload:0.1.0"
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
}
```

#### Try it out

An example use case can be found in our [Example App](app/).

A full, secure uploading mechanism requires a backend, but the Example App fakes this for brevity.
To use the Example App, you must create
an [Access Token](https://dashboard.mux.com/settings/access-tokens)
in our Mux service, and put those credentials in the example
app's [FakeBackend class](app/src/main/java/com/mux/video/vod/demo/backend/ImaginaryBackend.kt)
