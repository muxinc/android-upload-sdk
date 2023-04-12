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

class UploadListActivity : AppCompatActivity() {

  companion object {
    // For now, you have to paste this from the direct-upload response
    const val PUT_URL =
      "https://storage.googleapis.com/video-storage-gcp-us-east1-vop1-uploads/ivU3KeuN6aBRasvUQflUWG?Expires=1680730767&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=x4H5Ce8517S1D5oWoGJ2Bf6xlpwtyUpdF6LiLpqyKlpVBiXPKB5ImlA3fti0CUWJJSsO1g5G8jpyeA%2FwFv7hEInDmWa9gyO3oGRKCynv5WfDtndsedRvUCrQQBJXsRWex5VLdK5imLwo1kwEjATGZSwIZ7oK3ciH2gNPG%2BTymmGlyrrCzWloQ0jYhhpQLjNFZL3iYm%2BMGalZdXkndSqTeX%2F1j8rMHBrZ3HLJxqt8RYn5xCMTLAPET2kp7LEW4Vrj8Cn0DgkBnY46hQzc3KjbvK4orAUMSacmqADRtT6w3jm9XcOZFXnqwfJphrNcEs%2FUmGgtMFi6z3oJUT6b9Pg6yg%3D%3D&upload_id=ADPycdvjKQvrueNuLUqPXj3uo_bLjJ9pHBgDxYfAllAT_GjiO2_4a35u8dtKNXd6ynqZpMplbHtzRd9TcjCcIPUfsaSoxBelRqNX"
  }

  private lateinit var binding: ActivityVideoListBinding
  private lateinit var listAdapter: UploadListAdapter
  private val viewModel by viewModels<UploadListViewModel>()
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
    listAdapter = UploadListAdapter(list, viewModel)
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