package com.mux.video.vod.demo.mediastore

import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener
import com.mux.video.upload.api.MuxUpload
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.databinding.ActivityVideoListBinding
import com.mux.video.vod.demo.mediastore.model.MediaStoreVideo

class MediaStoreVideosActivity : AppCompatActivity() {

  companion object {
    // For now, you have to PUT to this
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east4-vop1-uploads/ELboCathi7E59QlZ5fKcwH?Expires=1675385857&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=XQGApo6mLOYNbgMlsherRRKwcj1CqWKMu9Nx6sJ6lwOQEMhpdT3YcL%2BJZ1NSv%2FF0eGB%2F9111ADofT%2FOGlm3q21tkmEblIdCtEPFQRk6nNANjfjT%2B7iknK4JCV1HogorUL5zoRYspaavaLHIQI2rmC9lo%2Feha856WRcLFbn2HDntGhTskr7VAufnZh3oW%2BpgmNJVTQX5KJtEiG5WgQv8C7drLSWI9hZSTEuvxCTZ1HnHMZtDjEFGCo02XbPfYYoz5aJ%2FbwS2u0dq5IM%2FdyphsqzyGrEyplG4CZ%2BzKwtFmCqdg0JW2VjGOxddwIt4e%2BrJEOdnEHl0jkWK7YLJnzJ50GQ%3D%3D&upload_id=ADPycduVPH2zLvVPjMA_tTvRV8zmcpLFCpoVp-zt3L2J-0JmoM4lrP65H8Hjdh4hBOAwPUK8Sxlul9Jw6gkrqc_7j6o3n45O6ws5"
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
      } else {
        viewModel.refresh()
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

    setSupportActionBar(findViewById(R.id.toolbar))
    binding.toolbarLayout.title = title
    binding.fab.setOnClickListener { view ->
      openDocument.launch(arrayOf("video/*"))
    }

    maybeRequestPermissions()
    viewModel.refresh()
    //viewModel.videoList.observe(this) { handleListUpdate(it) }
  }


  private fun handleListUpdate(list: List<MediaStoreVideo>) {
    // TODO: Use AsyncListDiffer to make this look nice
    listAdapter = MediaStoreVideosAdapter(list) { selectedVideo ->
      val videoUpload = MuxUpload.Builder(PUT_URL, selectedVideo.file).build()
      videoUpload.start()
    }
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