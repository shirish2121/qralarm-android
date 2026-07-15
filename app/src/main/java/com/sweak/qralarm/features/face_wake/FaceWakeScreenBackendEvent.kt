package com.sweak.qralarm.features.face_wake

sealed class FaceWakeScreenBackendEvent {
    data object AlarmDisabled : FaceWakeScreenBackendEvent()
    data object CameraInitializationError : FaceWakeScreenBackendEvent()
}
