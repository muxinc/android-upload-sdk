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
      "https://storage.googleapis.com/video-storage-gcp-us-east4-vop1-uploads/FmwbeBoTP9O6OrGEs1SKdC?Expires=1675711296&GoogleAccessId=uploads-gcp-us-east1-vop1%40mux-video-production.iam.gserviceaccount.com&Signature=Pj4gR1LwW4U%2FVhh599j6HBYuEEzRIABWSAOd7nivOIzjct3jpFAxsXjB9TstzGMU5l6pz9oLZMhJJiayczNMXpdGI584%2FuujrR6wO0la%2BU0a%2FtOqZITIm0op%2B6aRfmxxl%2FRgbDku3SPUND8aGDuUwFYuCLmhxRynMqLZ7pi3UWUeE4gwJohcYnmRTukLgxxDJixgVv%2FqcXGxlttIf1ajk0wHFz%2FoZWi%2B7DdaTOFWkQvOCE1KikfqUuIYypKOsQtw%2Bh%2FD8KQR4DuzRi70oQ8sp0h7BedF351EkBJKpFT7MGeh0oF%2BmvGg8ghqrMoA%2FdyVlm4uPWjCrJ4f%2FzLKJBaKvQ%3D%3D&upload_id=ADPycduqGEr-Q-0YqYJa1dJT5VR1caXEYcURIYX4AWKoWHbZ_kiEktpjSD3xgJ387eem4m410uepvnSh2_YB5hJEhgRheqI-C4Nq"
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