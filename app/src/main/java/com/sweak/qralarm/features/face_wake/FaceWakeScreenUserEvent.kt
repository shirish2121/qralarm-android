package com.sweak.qralarm.features.face_wake

import android.content.Context
import androidx.lifecycle.LifecycleOwner

sealed class FaceWakeScreenUserEvent {
    data class InitializeCamera(
        val appContext: Context,
        val lifecycleOwner: LifecycleOwner
    ) : FaceWakeScreenUserEvent()
}
