package com.mux.video.vod.demo.upload

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

class CreateUploadActivity : ComponentActivity() {

  private val viewModel by viewModels<CreateUploadViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MuxUploadSDKForAndroidTheme {
        RequestPermissionsEffect(this)

        val state = viewModel.videoState.observeAsState(
          CreateUploadViewModel.ScreenState(CreateUploadViewModel.PrepareState.NONE, null)
        )
        ScreenContent(closeThisScreen = { finish() }, screenState = state.value)
      }
    }
  }
}

@Composable
fun RequestPermissionsEffect(context: Context) {
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
              android.Manifest.permission.READ_EXTERNAL_STORAGE,
              android.Manifest.permission.READ_MEDIA_VIDEO
            )
          )
        } else {
          launcher.launch(
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
          )
        }
      }
    }
  }
}

@Composable
fun ScreenContent(
  closeThisScreen: () -> Unit = {},
  screenState: CreateUploadViewModel.ScreenState
) {
  return Scaffold(
    topBar = {
      AppBar(closeThisScreen, screenState.chosenFile)
    },
  ) { contentPadding ->
    BodyContent(screenState, Modifier.padding(contentPadding).padding(16.dp))
  }
}

@Composable
fun BodyContent(state: CreateUploadViewModel.ScreenState, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .wrapContentSize(Alignment.TopStart)
  ) {
    Row {
      Box(
        modifier = Modifier
          .clip(CircleShape)
          .background(Color.Gray)
          .size(96.dp)
      )
      Spacer(modifier = Modifier.size(16.dp))
      val stateTxtModifier = Modifier
        .fillMaxWidth()
        .align(CenterVertically)
      Box(
        modifier = stateTxtModifier
      ) {
        if (state.chosenFile != null) {
          Text(
            text = "Will upload video file: ${state.chosenFile.name}",
            modifier = stateTxtModifier,
            fontSize = 24.sp
          )
        } else if (state.prepareState == CreateUploadViewModel.PrepareState.ERROR) {
          Text(
            text = "Error preparing this video for upload",
            modifier = stateTxtModifier,
            fontSize = 24.sp
          )
        } else {
          Text(
            text = "Click to choose a video to upload",
            modifier = stateTxtModifier,
            fontSize = 24.sp
          )
        }
      }
    } // Box
    Spacer(modifier = Modifier.size(16.dp))
    if (state.thumbnail != null) {
      val imageBitmap = state.thumbnail.asImageBitmap()
      Box(
        modifier = Modifier.paint(painter = BitmapPainter(image = imageBitmap))
      )
    } else {
      Box(
        modifier = Modifier
          .wrapContentSize(Alignment.Center)
          .fillMaxWidth()
          .height(256.dp)
          .border(
            width = 1.dp,
            color = Color.LightGray,
            shape = RoundedCornerShape(12.dp),
          )
      ) {
        val emoji = if (state.prepareState == CreateUploadViewModel.PrepareState.ERROR) {
          "❌"
        } else {
          "❓"
        }
        Text(
          emoji,
          fontSize = 36.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.align(Alignment.Center)
        )
      }
    }
  }
}

@Composable
fun AppBar(closeThisScreen: () -> Unit, videoFile: File?) {
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_activity_create_upload)) },
    navigationIcon = {
      IconButton(
        onClick = {
          closeThisScreen()
        },
        enabled = videoFile != null
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(id = android.R.string.cancel),
        )
      }
    },
    actions = {
      TextButton(onClick = {
        handleCreateUpload()
        closeThisScreen()
      }) {
        Text(
          text = stringResource(id = R.string.action_create_upload),
          style = TextStyle(
            color = MaterialTheme.colors.onPrimary
          )
        )
      }
    },
  )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
  MuxUploadSDKForAndroidTheme {
    ScreenContent(
      screenState = CreateUploadViewModel.ScreenState(
        prepareState = CreateUploadViewModel.PrepareState.NONE,
        thumbnail = null
      ),
    )
  }
}

private fun hasPermissions(context: Context): Boolean {
  val hasVideo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(
      context,
      android.Manifest.permission.READ_MEDIA_VIDEO
    ) == PackageManager.PERMISSION_GRANTED
  } else {
    true
  }
  val hasExternalStorage = ContextCompat.checkSelfPermission(
    context,
    android.Manifest.permission.READ_EXTERNAL_STORAGE
  ) == PackageManager.PERMISSION_DENIED
  return hasVideo && hasExternalStorage
}

private fun handleCreateUpload() {
  // TODO: Make the ViewModel start the MuxUpload and then close the screen
}
