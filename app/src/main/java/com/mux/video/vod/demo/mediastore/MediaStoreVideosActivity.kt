package com.mux.video.vod.demo.mediastore

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding
import com.mux.video.vod.demo.mediastore.model.UploadingVideo

class MediaStoreVideosActivity : AppCompatActivity() {

  companion object {
    // For now, you have to paste this from the direct-upload response
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east1-vop1-uploads/BGZiJYDbKW8E8NZvEkPCpB?Expires=1680305628&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=vonTUgLu8aIIXpgNf7CYodbi7uhnC2g06JXOUpSKcDPsZP17VGUh08ffcoTNi1vW0mgekXUvNZexdZnMxzey0B8l%2FIqYkL4IyVsxVJI%2FzJROGM4uhmDCsOD1oHbNStqWQj6KtRhwpA3tBMMk%2FJpTeeOfuwK%2FEb2zuFq21t5gDjW1pEbtVr%2B4W%2F%2FzxZZfVuL%2FCHNtBPX%2B0%2BA9%2BtCR36SZwLWk2nHE4hulzQ%2BIw4E6RPHs0yOe342SRKfeyDWNTrAS7T6JaK9Autj2Z%2BpckEhPAzIOABaDwWo3M6kKQW9nqQzpxCQMh%2FHU%2BDcC0DpExGeeX33YbFOawjH3vAhZymDW2g%3D%3D&upload_id=ADPycdvqKDTmC8ZIj8OI5xyoXYBTW9_Mt0Jk_6LFvG0Pa3WqKoXeBEzT9hvJOTzg0c-e8tfYy6djg6eRAR5EcLFtuG8TB_4_qTb6"
  }

  private lateinit var binding: ActivityVideoListBinding
  private lateinit var listAdapter: MediaStoreVideosAdapter
  private val viewModel by viewModels<MediaStoreVideosViewModel>()
  private val requestPermissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
    { grantedPermissions ->
      if (!grantedPermissions.containsKey(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        || grantedPermissions.containsKey(android.Manifest.permission.READ_MEDIA_VIDEO)
      ) {
        maybeRequestPermissionsApi33()
      }
    }
  private val openDocument =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { docUri ->
      Log.d(javaClass.simpleName, "Got doc with URI $docUri")
      viewModel.beginUpload(docUri!!)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityVideoListBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.videoListList.includeRecyclerView.layoutManager = LinearLayoutManager(this)
    viewModel.uploads.observe(this) { handleListUpdate(it) }

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      openDocument.launch(arrayOf("video/*"))
    }

    maybeRequestPermissions()
  }

  private fun handleListUpdate(list: List<MuxUpload>) {
    // TODO: Use AsyncListDiffer to make this look nice
    listAdapter = MediaStoreVideosAdapter(list)
    binding.videoListList.includeRecyclerView.adapter = listAdapter
  }

  private fun maybeRequestPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      maybeRequestPermissionsApi33()
    } else {
      maybeRequestPermissionsOld()
    }
  }

  private fun maybeRequestPermissionsOld() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadStorage) {
      requestPermissions.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    }
  }

  @TargetApi(Build.VERSION_CODES.TIRAMISU)
  private fun maybeRequestPermissionsApi33() {
    val hasReadStorage =
      ActivityCompat.checkSelfPermission(
        this,
        android.Manifest.permission.READ_EXTERNAL_STORAGE
      ) == PackageManager.PERMISSION_GRANTED
    val hasReadVideo = ActivityCompat.checkSelfPermission(
      this,
      android.Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasReadVideo || !hasReadStorage) {
      requestPermissions.launch(
        arrayOf(
          android.Manifest.permission.READ_EXTERNAL_STORAGE,
          android.Manifest.permission.READ_MEDIA_VIDEO
        )
      )
    }
  }

}