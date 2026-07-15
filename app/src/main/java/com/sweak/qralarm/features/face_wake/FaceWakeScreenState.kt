package com.sweak.qralarm.features.face_wake

import androidx.camera.core.SurfaceRequest

data class FaceWakeScreenState(
    val surfaceRequest: SurfaceRequest? = null,
    val isFacePresent: Boolean = false,
    val remainingSeconds: Int = 0,
    val requiredSeconds: Int = 0
)
