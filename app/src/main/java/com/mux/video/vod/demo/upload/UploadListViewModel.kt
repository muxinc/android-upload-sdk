package com.mux.video.vod.demo.upload

import android.app.Application
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.MediaStore.Video.VideoColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.upload.model.UploadingVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Queries the device's content provider for saved videos to upload
 */
class UploadListViewModel(private val app: Application) : AndroidViewModel(app) {

  val uploads: LiveData<List<MuxUpload>> by this::innerUploads
  private val innerUploads = MutableLiveData<List<MuxUpload>>(listOf())
  private val uploadList: MutableList<MuxUpload> = mutableListOf()

  fun beginUpload(contentUri: Uri) {
    viewModelScope.launch {
      Log.d(javaClass.simpleName, "Beginning upload of uri $contentUri")
      val copiedFile = copyIntoTempFile(contentUri)
      Log.d(javaClass.simpleName, "Copied file to $copiedFile")

      val upl = MuxUpload.Builder(UploadListActivity.PUT_URL, copiedFile).build()
      upl.addProgressListener {
        //Log.v(javaClass.simpleName, "Upload progress: ${it.bytesUploaded} / ${it.totalBytes}")
        innerUploads.postValue(uploadList)
      }
      upl.addResultListener {
        if (it.isSuccess) {
          Log.w(javaClass.simpleName, "YAY! Uploaded the file: $contentUri")
          Log.i(javaClass.simpleName, "final state is $it")
          innerUploads.postValue(uploadList)
        } else {
          innerUploads.postValue(uploadList)
        }
      }
      uploadList += upl
      innerUploads.postValue(uploadList)

      upl.start()
    }
  }

  /**
   * In order to upload a file from the device's media store, the file must be copied into the app's
   * temp directory. (Technically we could stream it from the source, but this prevents the other
   * app from modifying the file if we pause the upload for a long time or whatever)
   * TODO<em> Is this something that should go in the SDK? This is a common workflow
   */
  @Throws
  private suspend fun copyIntoTempFile(contentUri: Uri): File {
    // Create a unique name for our temp file. There are a ton of ways to do this, but this one is
    //  pretty easy to implement and protects from unsafe characters
    val basename = android.util.Base64.encode(
      contentUri.pathSegments.joinToString(separator = "-").encodeToByteArray(),
      0
    ).decodeToString()

    val cacheDir = File(app.cacheDir, "mux-upload")
    cacheDir.mkdirs()
    val destFile = File(cacheDir, basename)

    withContext(Dispatchers.IO) {
      val output = FileOutputStream(destFile).channel
      val fileDescriptor = app.contentResolver.openFileDescriptor(contentUri, "r")
      val input = FileInputStream(fileDescriptor!!.fileDescriptor).channel

      try {
        val fileSize = input.size()
        var read = 0L
        do {
          read += input.transferTo(read, 10 * 1024, output)
        } while (read < fileSize)
      } finally {
        input.close()
        fileDescriptor.close()
        output.close()
      }
    }

    return destFile
  }

  // Might need something like this
  private suspend fun fetchVideos(): List<UploadingVideo> {
    fun ownerPackageName(cursor: Cursor): String {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        cursor.getString(VideoColumns.OWNER_PACKAGE_NAME) ?: "??"
      } else {
        "??"
      }
    }

    fun columns(): Array<String> {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
          VideoColumns.DISPLAY_NAME,
          VideoColumns.DATA,
          VideoColumns.OWNER_PACKAGE_NAME,
          VideoColumns.DATE_ADDED,
          VideoColumns.DATE_TAKEN
        )
      } else {
        arrayOf(
          VideoColumns.DISPLAY_NAME,
          VideoColumns.DATA,
          VideoColumns.DATE_ADDED,
          VideoColumns.DATE_TAKEN
        )
      }
    }

    withContext(Dispatchers.IO) {
      app.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        columns(),
        null,
        null,
        null,
      )!!
    }.use { cursor ->
      if (cursor.count <= 0) {
        Log.w(javaClass.simpleName, "No videos found")
        return listOf()
      }

      val videos = mutableListOf<UploadingVideo>()
      cursor.moveToFirst()
      do {
        val title = cursor.getString(VideoColumns.DISPLAY_NAME) ?: "[no name]"
        val file = cursor.getString(VideoColumns.DATA) ?: continue
        val fromApp = ownerPackageName(cursor)
        val dateMillis = cursor.getLong(VideoColumns.DATE_ADDED)
        val dateTime =
          DateTime.now().withMillis(dateMillis * 1000)
            .withZoneRetainFields(DateTimeZone.getDefault())

        val vid = UploadingVideo(
          title = title,
          file = File(file),
          fromApp = fromApp,
          date = dateTime.toString()
        )
        videos += vid
      } while (cursor.moveToNext())

      return videos
    }
  }

  private fun Cursor.getLong(columnName: String): Long {
    val colIdx = getColumnIndexOrThrow(columnName)
    return getLong(colIdx)
  }

  private fun Cursor.getString(columnName: String): String? {
    val colIdx = getColumnIndexOrThrow(columnName)
    return getString(colIdx)
  }
}
