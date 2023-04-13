package com.mux.video.vod.demo.upload

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.mux.video.vod.demo.R
import com.mux.video.vod.demo.upload.ui.theme.MuxUploadSDKForAndroidTheme
import com.mux.video.vod.demo.upload.viewmodel.CreateUploadViewModel

class CreateUploadActivity : ComponentActivity() {

  private val viewModel by viewModels<CreateUploadViewModel>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MuxUploadSDKForAndroidTheme {
        // A surface container using the 'background' color from the theme
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
          val state = viewModel.videoState.observeAsState(
            CreateUploadViewModel.ScreenState(CreateUploadViewModel.PrepareState.NONE, null)
          )

          ScreenContent(closeThisScreen = { finish() }, screenState = state.value)
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
      AppBar(
        closeThisScreen,
        fileAvailable = screenState.prepareState == CreateUploadViewModel.PrepareState.READY
      )
    },
  ) { contentPadding ->
    BodyContent(screenState, Modifier.padding(contentPadding))
  }
}

@Composable
fun BodyContent(state: CreateUploadViewModel.ScreenState, modifier: Modifier = Modifier) {
  Text("asflkjh", modifier)
}

@Composable
fun AppBar(closeThisScreen: () -> Unit, fileAvailable: Boolean) {
  TopAppBar(
    title = { Text(text = stringResource(R.string.title_activity_create_upload)) },
    navigationIcon = {
      IconButton(
        onClick = {
          closeThisScreen()
        },
        enabled = fileAvailable
      ) {
        Icon(
          Icons.Filled.Close,
          contentDescription = stringResource(id = android.R.string.cancel)
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

private fun handleCreateUpload() {
  // TODO: Make the ViewModel start the MuxUpload and then close the screen
}
