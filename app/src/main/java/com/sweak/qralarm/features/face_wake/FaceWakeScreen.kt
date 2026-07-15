package com.sweak.qralarm.features.face_wake

import android.widget.Toast
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sweak.qralarm.R
import com.sweak.qralarm.core.designsystem.theme.BlueZodiac
import com.sweak.qralarm.core.designsystem.theme.Jacarta
import com.sweak.qralarm.core.designsystem.theme.space
import com.sweak.qralarm.core.ui.compose_util.ObserveAsEvents

@Composable
fun FaceWakeScreen(
    idOfAlarm: Long,
    onAlarmDisabled: () -> Unit,
    onCameraInitializationError: () -> Unit
) {
    val faceWakeViewModel =
        hiltViewModel<FaceWakeViewModel, FaceWakeViewModel.Factory> { factory ->
            factory.create(idOfAlarm = idOfAlarm)
        }
    val state by faceWakeViewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current

    ObserveAsEvents(
        flow = faceWakeViewModel.backendEvents,
        onEvent = { event ->
            when (event) {
                is FaceWakeScreenBackendEvent.AlarmDisabled -> onAlarmDisabled()
                is FaceWakeScreenBackendEvent.CameraInitializationError -> {
                    Toast.makeText(
                        context,
                        R.string.failed_to_initialize_camera,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        faceWakeViewModel.onEvent(
            FaceWakeScreenUserEvent.InitializeCamera(
                appContext = context,
                lifecycleOwner = lifecycleOwner
            )
        )
    }

    FaceWakeScreenContent(state = state)
}

@Composable
private fun FaceWakeScreenContent(state: FaceWakeScreenState) {
    Scaffold(containerColor = Color.Black) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            state.surfaceRequest?.let { request ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .aspectRatio(FRONT_CAMERA_PREVIEW_ASPECT_RATIO)
                ) {
                    CameraXViewfinder(
                        implementationMode = ImplementationMode.EMBEDDED,
                        surfaceRequest = request,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Jacarta.copy(alpha = 0.85f)),
                            startY = 200f
                        )
                    )
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.space.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(MaterialTheme.space.xLarge)
            ) {
                Text(
                    text = stringResource(R.string.stay_in_front_of_camera),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )
                Text(
                    text = stringResource(
                        if (state.isFacePresent) {
                            R.string.face_detected_alarm_muted
                        } else {
                            R.string.face_not_detected_alarm_ringing
                        }
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.isFacePresent) BlueZodiac else Color.White
                )
                Text(
                    text = stringResource(
                        R.string.alarm_will_stop_after,
                        formatRemainingSeconds(state.remainingSeconds)
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
        }
    }
}

private const val FRONT_CAMERA_PREVIEW_ASPECT_RATIO = 3f / 4f

private fun formatRemainingSeconds(seconds: Int): String {
    val minutesPart = seconds / 60
    val secondsPart = seconds % 60
    return "%02d:%02d".format(minutesPart, secondsPart)
}
