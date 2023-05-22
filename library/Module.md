# Module Mux Upload SDK

The Mux Upload SDK processes and uploads video files to [Mux Video](https://www.mux.com/) from a
user's local
device. It is part of a full-stack flow described in our
guide, [Upload Files Directly](https://docs.mux.com/guides/video/upload-files-directly).

Once you have your direct upload URL, you can use it to upload a file using this SDK.

## Initializing the SDK

This SDK must be initialized once with a `Context` before it can be used

```kotlin
// from your custom Application class, Activity, etc. The context isn't saved
MuxUploadSdk.initialize(appContext = this)
```

## Starting a new upload

The `MuxUpload` class can be used to start a video upload and observe its progress.

```kotlin
  // Start a new upload
val upload = MuxUpload.Builder(myUploadUrl, myInputFile).build()
upload.setResultListener { /*...*/ }
upload.setProgressListener { /*...*/}
upload.start()
```

### Handling errors

The upload SDK handles transient errors according to a customizable retry policy. Fatal errors are
reported by `MuxUpload.setResultListener`. 

```kotlin
upload.setResultListener { result ->
  if (!result.isSuccess) {
    notifyError()
  } else {
    /*...*/
  }
}
```

## Resuming uploads after process death

Uploads managed by this SDK can be resumed after process death, or if network connectivity caused 
them to fail at some time in the past.
```kotlin
 MuxUploadManager.resumeAllCachedJobs()
 val upload = MuxUploadManager.findUploadByFile(myVideoFile)
 upload.setResultListener { /*...*/ }
```
