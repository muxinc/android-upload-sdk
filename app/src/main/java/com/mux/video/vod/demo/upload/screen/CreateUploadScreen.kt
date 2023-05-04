package com.mux.video.vod.demo.upload.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.CreateUploadCta
import com.mux.video.vod.demo.upload.MuxAppBar
import com.mux.video.vod.demo.upload.THUMBNAIL_SIZE
import com.mux.video.vod.demo.upload.ui.theme.*
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CreateUploadScreen() {
  val context = LocalContext.current
  val activity = context as? Activity

  RequestPermissionsEffect(context)
  GetContentEffect(requestContent = true, hasPermission = hasPermissions(context))

  val viewModel: CreateUploadViewModel = viewModel()
  val state = viewModel.videoState.observeAsState(
    CreateUploadViewModel.State(CreateUploadViewModel.PrepareState.NONE, null)
  )
  ScreenContent(closeThisScreen = { activity?.finish() }, screenState = state.value)
}

@Composable
private fun RequestPermissionsEffect(context: Context) {
  val permissionsState = remember { mutableStateOf(hasPermissions(context)) }
  val launcher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantedPermissions: Map<String, Boolean>? ->
      grantedPermissions!! // The system shouldn't ever give us null here
      permissionsState.value =
        grantedPermissions.values.reduce { hasAll, hasThisOne -> hasThisOne && hasAll }
    }
  if (!permissionsState.value) {
    LaunchedEffect(key1 = Object()) {
      MainScope().launch {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          launcher.launch(
            arrayOf(
              Manifest.permission.READ_EXTERNAL_STORAGE,
              Manifest.permission.READ_MEDIA_VIDEO
            )
          )
        } else {
          launcher.launch(
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
          )
        }
      } // MainScope().launch {
    } // LaunchedEffect
  } // if (!permissionState)
}

@Composable
private fun GetContentEffect(requestContent: Boolean?, hasPermission: Boolean = true) {
  val viewModel: CreateUploadViewModel = viewModel()
  val contentUri = remember { mutableStateOf<Uri?>(null) }
  val getContent =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      contentUri.value = uri
      uri?.let { viewModel.prepareForUpload(it) }
    }
  LaunchedEffect(key1 = Object()) {
    if (contentUri.value == null && requestContent == true && hasPermission) {
      MainScope().launch { getContent.launch(arrayOf("video/*")) }
    }
  }
}

@Composable
private fun ScreenContent(
  closeThisScreen: () -> Unit = {},
  screenState: CreateUploadViewModel.State
) {
  return Scaffold(
    topBar = {
      ScreenAppBar(closeThisScreen, screenState.chosenFile)
    },
  ) { contentPadding ->
    BodyContent(
      screenState,
      Modifier.padding(contentPadding),
      closeThisScreen
    )
  }
}

@Composable
private fun BodyContent(state: CreateUploadViewModel.State, modifier: Modifier = Modifier, closeThisScreen: () -> Unit) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .wrapContentSize(Alignment.TopStart)
      .padding(horizontal = 20.dp)
  ) {
    if (state.thumbnail != null) {
      ChosenThumbnail(thumbnail = state.thumbnail, modifier = Modifier.padding(top = 64.dp))
    } else {
      ThumbnailPlaceHolder(state = state, modifier = Modifier.padding(top = 64.dp))
    } // Box
  }
}

@Composable
private fun ThumbnailPlaceHolder(state: CreateUploadViewModel.State, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .wrapContentSize(Alignment.Center)
      .fillMaxWidth()
      .height(THUMBNAIL_SIZE)
  ) {
    when (state.prepareState) {
      CreateUploadViewModel.PrepareState.ERROR -> {
        ErrorPlaceHolder()
      }
      CreateUploadViewModel.PrepareState.PREPARING -> {
        ProgressPlaceHolder()
      }
      else -> {
        CtaPlaceHolder()
      } // else ->
    } // when (state.prepareState)
  }
}

@Composable
private fun CtaPlaceHolder() {
  val viewModel: CreateUploadViewModel = viewModel()
  val requestContent = remember { mutableStateOf(false) }
  GetContentEffect(requestContent.value)
  CreateUploadCta() {
    requestContent.value = viewModel.videoState.value?.chosenFile == null
  }
}

@Composable
private fun ProgressPlaceHolder() {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(color = Gray90, shape = RoundedCornerShape(12.dp))
      .border(
        width = 1.dp,
        color = Gray70,
        shape = RoundedCornerShape(12.dp),
      )
  ) {
    CircularProgressIndicator(
      modifier = Modifier
        .align(Alignment.Center)
        .size(48.dp),
      color = Gray30
    )
  }
}

@Composable
private fun ErrorPlaceHolder() {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .fillMaxSize()
      .background(color = Gray90, shape = RoundedCornerShape(12.dp))
      .border(
        width = 1.dp,
        color = Gray70,
        shape = RoundedCornerShape(12.dp),
      )
  ) {
    Icon(
      Icons.Outlined.Error,
      contentDescription = "",
      tint = Gray30,
      modifier = Modifier
        .size(48.dp),
    )
    Spacer(modifier = Modifier.size(8.dp))
    Text(
      text = "An error occurred while processing your file. Please try another",
      fontWeight = FontWeight.W700,
      textAlign = TextAlign.Center,
      color = White,
      modifier = Modifier.padding(12.dp)
    )
  }
}

@Composable
private fun ChosenThumbnail(modifier: Modifier = Modifier, thumbnail: Bitmap) {
  val imageBitmap = thumbnail.asImageBitmap()
  Image(
    bitmap = imageBitmap,
    contentDescription = "Preview of the video thumbnail",
    contentScale = ContentScale.Crop,
    modifier = modifier
      .height(THUMBNAIL_SIZE)
      .clip(RoundedCornerShape(12.dp)),
  )
}

@Composable
private fun ScreenAppBar(closeThisScreen: () -> Unit, videoFile: File?) {
  MuxAppBar(
    startContent = {
      IconButton(
        onClick = { closeThisScreen() },
        modifier = Modifier.size(20.dp)
      ) {
        Icon(painter = painterResource(id = R.drawable.ic_close), contentDescription = "go back")
      }
    },
    centerContent = {
      Text(
        "Create a New Upload",
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.W700,
        color = MaterialTheme.colors.onPrimary
      )
    }
  )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
  MuxUploadSDKForAndroidTheme {
    ScreenContent(
      screenState = CreateUploadViewModel.State(
        prepareState = CreateUploadViewModel.PrepareState.NONE,
        thumbnail = null
      ),
    )
  }
}

private fun hasPermissions(context: Context): Boolean {
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED
  } else {
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
  }
}
