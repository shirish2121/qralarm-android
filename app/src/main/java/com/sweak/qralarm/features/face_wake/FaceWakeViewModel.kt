package com.sweak.qralarm.features.face_wake

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.sweak.qralarm.alarm.service.AlarmService
import com.sweak.qralarm.core.domain.alarm.Alarm
import com.sweak.qralarm.core.domain.alarm.AlarmsRepository
import com.sweak.qralarm.core.domain.alarm.DisableAlarm
import com.sweak.qralarm.core.domain.alarm.SetAlarm
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.time.Duration.Companion.seconds

@HiltViewModel(assistedFactory = FaceWakeViewModel.Factory::class)
class FaceWakeViewModel @AssistedInject constructor(
    @Assisted private val idOfAlarm: Long,
    private val alarmsRepository: AlarmsRepository,
    private val setAlarm: SetAlarm,
    private val disableAlarm: DisableAlarm
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(idOfAlarm: Long): FaceWakeViewModel
    }

    private lateinit var alarm: Alarm
    private var appContext: Context? = null
    private var presenceJob: Job? = null
    private var secondsPresent = 0
    private var lastBroadcastFacePresent: Boolean? = null
    private var shouldAnalyze = true

    private val poseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private var _state = MutableStateFlow(FaceWakeScreenState())
    val state = _state.asStateFlow()

    private val backendEventsChannel = Channel<FaceWakeScreenBackendEvent>()
    val backendEvents = backendEventsChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            alarmsRepository.getAlarm(alarmId = idOfAlarm)?.let {
                alarm = it
                secondsPresent = AlarmService
                    .getFaceWakeProgressSeconds(idOfAlarm)
                    .coerceAtMost(it.faceWakeDurationInSeconds)
                _state.update { currentState ->
                    currentState.copy(
                        remainingSeconds =
                        (it.faceWakeDurationInSeconds - secondsPresent).coerceAtLeast(0),
                        requiredSeconds = it.faceWakeDurationInSeconds
                    )
                }
            }
        }
    }

    fun onEvent(event: FaceWakeScreenUserEvent) {
        when (event) {
            is FaceWakeScreenUserEvent.InitializeCamera -> viewModelScope.launch {
                appContext = event.appContext.applicationContext

                try {
                    ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig())
                } catch (_: IllegalStateException) { /* no-op */ }

                val processCameraProvider =
                    ProcessCameraProvider.awaitInstance(event.appContext).also { it.unbindAll() }

                var attempt = 0
                while (true) {
                    val imageAnalysisUseCase = getImageAnalysisUseCase()
                    val cameraExecutor = Executors.newSingleThreadExecutor()
                    imageAnalysisUseCase.setAnalyzer(cameraExecutor, getSittingPoseAnalyzer())

                    try {
                        processCameraProvider.bindToLifecycle(
                            event.lifecycleOwner,
                            DEFAULT_FRONT_CAMERA,
                            getCameraPreviewUseCase(),
                            imageAnalysisUseCase
                        )

                        try {
                            awaitCancellation()
                        } finally {
                            cleanUpCameraResources(
                                imageAnalysisUseCase,
                                processCameraProvider,
                                cameraExecutor
                            )
                        }
                    } catch (exception: Exception) {
                        if (exception is CancellationException) {
                            throw exception
                        }

                        if (exception !is IllegalStateException &&
                            exception !is IllegalArgumentException &&
                            exception !is UnsupportedOperationException &&
                            exception !is CameraInfoUnavailableException
                        ) {
                            throw exception
                        }

                        cleanUpCameraResources(
                            imageAnalysisUseCase,
                            processCameraProvider,
                            cameraExecutor
                        )

                        attempt += 1
                        if (attempt >= CAMERA_INITIALIZATION_ATTEMPTS) {
                            backendEventsChannel.send(
                                FaceWakeScreenBackendEvent.CameraInitializationError
                            )
                            return@launch
                        }

                        delay(CAMERA_INITIALIZATION_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun getSittingPoseAnalyzer() = ImageAnalysis.Analyzer { imageProxy ->
        if (!shouldAnalyze) {
            imageProxy.close()
            return@Analyzer
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return@Analyzer
        }

        shouldAnalyze = false
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                onFacePresenceChanged(isSittingPoseUsable(pose, imageProxy))
            }
            .addOnFailureListener { exception ->
                Log.e("FaceWakePose", exception.toString())
                onFacePresenceChanged(false)
            }
            .addOnCompleteListener {
                imageProxy.close()
                shouldAnalyze = true
            }
    }

    private fun isSittingPoseUsable(pose: Pose, imageProxy: ImageProxy): Boolean {
        val nose = pose.requiredLandmark(PoseLandmark.NOSE) ?: return false
        val leftShoulder = pose.requiredLandmark(PoseLandmark.LEFT_SHOULDER) ?: return false
        val rightShoulder = pose.requiredLandmark(PoseLandmark.RIGHT_SHOULDER) ?: return false
        val imageWidth = imageProxy.width.toFloat()
        val imageHeight = imageProxy.height.toFloat()
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        val bodyCenterX = shoulderCenter.x / imageWidth
        val headToShoulderHeight = shoulderCenter.y - nose.position.y

        if (shoulderWidth < imageWidth * MIN_SHOULDER_WIDTH_RATIO ||
            headToShoulderHeight < imageHeight * MIN_HEAD_TO_SHOULDER_HEIGHT_RATIO ||
            nose.position.y >= shoulderCenter.y ||
            bodyCenterX !in MIN_BODY_CENTER_X_RATIO..MAX_BODY_CENTER_X_RATIO ||
            abs(leftShoulder.position.y - rightShoulder.position.y) >
            shoulderWidth * MAX_SHOULDER_TILT_RATIO
        ) {
            return false
        }

        val leftHip = pose.optionalLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.optionalLandmark(PoseLandmark.RIGHT_HIP)
        val leftKnee = pose.optionalLandmark(PoseLandmark.LEFT_KNEE)
        val rightKnee = pose.optionalLandmark(PoseLandmark.RIGHT_KNEE)
        val leftAnkle = pose.optionalLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.optionalLandmark(PoseLandmark.RIGHT_ANKLE)

        if (leftHip != null &&
            rightHip != null &&
            leftKnee != null &&
            rightKnee != null &&
            leftAnkle != null &&
            rightAnkle != null
        ) {
            return isFullBodySittingPose(
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                nose = nose,
                leftShoulder = leftShoulder,
                rightShoulder = rightShoulder,
                leftHip = leftHip,
                rightHip = rightHip,
                leftKnee = leftKnee,
                rightKnee = rightKnee,
                leftAnkle = leftAnkle,
                rightAnkle = rightAnkle
            )
        }

        val leftElbow = pose.optionalLandmark(PoseLandmark.LEFT_ELBOW)
        val rightElbow = pose.optionalLandmark(PoseLandmark.RIGHT_ELBOW)
        val leftWrist = pose.optionalLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.optionalLandmark(PoseLandmark.RIGHT_WRIST)

        return isDeskSittingPose(
            imageHeight = imageHeight,
            shoulderCenter = shoulderCenter,
            shoulderWidth = shoulderWidth,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftHip = leftHip,
            rightHip = rightHip,
            leftElbow = leftElbow,
            rightElbow = rightElbow,
            leftWrist = leftWrist,
            rightWrist = rightWrist
        )
    }

    private fun isFullBodySittingPose(
        imageWidth: Float,
        imageHeight: Float,
        nose: PoseLandmark,
        leftShoulder: PoseLandmark,
        rightShoulder: PoseLandmark,
        leftHip: PoseLandmark,
        rightHip: PoseLandmark,
        leftKnee: PoseLandmark,
        rightKnee: PoseLandmark,
        leftAnkle: PoseLandmark,
        rightAnkle: PoseLandmark
    ): Boolean {
        val shoulderCenter = midpoint(leftShoulder, rightShoulder)
        val hipCenter = midpoint(leftHip, rightHip)
        val kneeCenter = midpoint(leftKnee, rightKnee)
        val ankleCenter = midpoint(leftAnkle, rightAnkle)
        val shoulderWidth = distance(leftShoulder, rightShoulder)
        val hipWidth = distance(leftHip, rightHip)
        val torsoHeight = hipCenter.y - shoulderCenter.y
        val kneeDrop = kneeCenter.y - hipCenter.y
        val ankleDrop = ankleCenter.y - kneeCenter.y
        val headToHipHeight = hipCenter.y - nose.position.y
        val bodyCenterX = (shoulderCenter.x + hipCenter.x) / 2f / imageWidth

        val leftKneeAngle = angle(leftHip, leftKnee, leftAnkle)
        val rightKneeAngle = angle(rightHip, rightKnee, rightAnkle)
        val hasBentLegs =
            leftKneeAngle in MIN_SITTING_KNEE_ANGLE..MAX_SITTING_KNEE_ANGLE ||
                rightKneeAngle in MIN_SITTING_KNEE_ANGLE..MAX_SITTING_KNEE_ANGLE

        return shoulderWidth >= imageWidth * MIN_SHOULDER_WIDTH_RATIO &&
            hipWidth >= imageWidth * MIN_HIP_WIDTH_RATIO &&
            headToHipHeight >= imageHeight * MIN_HEAD_TO_HIP_HEIGHT_RATIO &&
            torsoHeight >= imageHeight * MIN_TORSO_HEIGHT_RATIO &&
            kneeDrop >= imageHeight * MIN_KNEE_DROP_RATIO &&
            ankleDrop >= imageHeight * MIN_ANKLE_DROP_RATIO &&
            nose.position.y < shoulderCenter.y &&
            shoulderCenter.y < hipCenter.y &&
            hipCenter.y < kneeCenter.y &&
            kneeCenter.y < ankleCenter.y &&
            bodyCenterX in MIN_BODY_CENTER_X_RATIO..MAX_BODY_CENTER_X_RATIO &&
            abs(shoulderCenter.x - hipCenter.x) <= shoulderWidth * MAX_TORSO_LEAN_RATIO &&
            hasBentLegs
    }

    private fun isDeskSittingPose(
        imageHeight: Float,
        shoulderCenter: Point,
        shoulderWidth: Float,
        leftShoulder: PoseLandmark,
        rightShoulder: PoseLandmark,
        leftHip: PoseLandmark?,
        rightHip: PoseLandmark?,
        leftElbow: PoseLandmark?,
        rightElbow: PoseLandmark?,
        leftWrist: PoseLandmark?,
        rightWrist: PoseLandmark?
    ): Boolean {
        val hasUprightTorso = if (leftHip != null && rightHip != null) {
            val hipCenter = midpoint(leftHip, rightHip)
            val torsoHeight = hipCenter.y - shoulderCenter.y
            torsoHeight >= imageHeight * MIN_VISIBLE_TORSO_HEIGHT_RATIO &&
                shoulderCenter.y < hipCenter.y &&
                abs(shoulderCenter.x - hipCenter.x) <= shoulderWidth * MAX_DESK_TORSO_LEAN_RATIO
        } else {
            true
        }

        val hasLeftDeskArm = isDeskArmVisible(leftShoulder, leftElbow, leftWrist, shoulderWidth)
        val hasRightDeskArm = isDeskArmVisible(rightShoulder, rightElbow, rightWrist, shoulderWidth)
        val visibleDeskArmCount = listOf(hasLeftDeskArm, hasRightDeskArm).count { it }

        return hasUprightTorso && visibleDeskArmCount >= MIN_VISIBLE_DESK_ARMS
    }

    private fun isDeskArmVisible(
        shoulder: PoseLandmark,
        elbow: PoseLandmark?,
        wrist: PoseLandmark?,
        shoulderWidth: Float
    ): Boolean {
        if (elbow == null || wrist == null) {
            return false
        }

        val elbowDrop = elbow.position.y - shoulder.position.y
        val wristDrop = wrist.position.y - shoulder.position.y
        val forearmLength = distance(elbow, wrist)
        return elbowDrop >= shoulderWidth * MIN_ELBOW_DROP_RATIO &&
            wristDrop >= shoulderWidth * MIN_WRIST_DROP_RATIO &&
            forearmLength >= shoulderWidth * MIN_FOREARM_LENGTH_RATIO
    }

    private fun Pose.requiredLandmark(type: Int): PoseLandmark? {
        val landmark = getPoseLandmark(type) ?: return null
        return landmark.takeIf { it.inFrameLikelihood >= MIN_LANDMARK_LIKELIHOOD }
    }

    private fun Pose.optionalLandmark(type: Int): PoseLandmark? {
        val landmark = getPoseLandmark(type) ?: return null
        return landmark.takeIf { it.inFrameLikelihood >= MIN_OPTIONAL_LANDMARK_LIKELIHOOD }
    }

    private fun midpoint(first: PoseLandmark, second: PoseLandmark): Point {
        return Point(
            x = (first.position.x + second.position.x) / 2f,
            y = (first.position.y + second.position.y) / 2f
        )
    }

    private fun distance(first: PoseLandmark, second: PoseLandmark): Float {
        return hypot(
            first.position.x - second.position.x,
            first.position.y - second.position.y
        )
    }

    private fun angle(first: PoseLandmark, middle: PoseLandmark, last: PoseLandmark): Float {
        val firstVectorX = first.position.x - middle.position.x
        val firstVectorY = first.position.y - middle.position.y
        val lastVectorX = last.position.x - middle.position.x
        val lastVectorY = last.position.y - middle.position.y
        val dotProduct = firstVectorX * lastVectorX + firstVectorY * lastVectorY
        val firstMagnitude = hypot(firstVectorX, firstVectorY)
        val lastMagnitude = hypot(lastVectorX, lastVectorY)

        if (firstMagnitude == 0f || lastMagnitude == 0f) {
            return 180f
        }

        val cosine = (dotProduct / (firstMagnitude * lastMagnitude)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    private fun onFacePresenceChanged(isPresent: Boolean) {
        broadcastFacePresence(isPresent)

        if (isPresent) {
            if (presenceJob == null) {
                presenceJob = viewModelScope.launch {
                    while (secondsPresent < state.value.requiredSeconds) {
                        delay(1.seconds)
                        secondsPresent += 1
                        AlarmService.setFaceWakeProgressSeconds(idOfAlarm, secondsPresent)
                        _state.update { currentState ->
                            currentState.copy(
                                isFacePresent = true,
                                remainingSeconds =
                                (currentState.requiredSeconds - secondsPresent).coerceAtLeast(0)
                            )
                        }
                    }
                    completeAlarm()
                }
            }
        } else {
            presenceJob?.cancel()
            presenceJob = null
            _state.update { currentState ->
                currentState.copy(
                    isFacePresent = false,
                    remainingSeconds =
                    (currentState.requiredSeconds - secondsPresent).coerceAtLeast(0)
                )
            }
        }
    }

    private fun broadcastFacePresence(isPresent: Boolean) {
        if (lastBroadcastFacePresent == isPresent) {
            return
        }

        lastBroadcastFacePresent = isPresent
        appContext?.sendBroadcast(
            Intent(AlarmService.ACTION_FACE_WAKE_ALARM_MUTE).apply {
                setPackage(appContext?.packageName)
                putExtra(AlarmService.EXTRA_FACE_WAKE_FACE_PRESENT, isPresent)
            }
        )
    }

    private suspend fun completeAlarm() {
        if (!::alarm.isInitialized) {
            return
        }

        alarmsRepository.setAlarmSnoozed(
            alarmId = idOfAlarm,
            snoozed = false
        )

        if (alarm.repeatingMode is Alarm.RepeatingMode.Once) {
            disableAlarm(alarmId = alarm.alarmId)
        } else if (alarm.repeatingMode is Alarm.RepeatingMode.Days) {
            setAlarm(
                alarmId = alarm.alarmId,
                isReschedulingMissedAlarm = false
            )
        }

        AlarmService.clearFaceWakeProgress(idOfAlarm)
        backendEventsChannel.send(FaceWakeScreenBackendEvent.AlarmDisabled)
    }

    private fun getCameraPreviewUseCase() =
        Preview.Builder()
            .setResolutionSelector(getCameraResolutionSelector())
            .build()
            .apply {
            setSurfaceProvider { newSurfaceRequest ->
                _state.update { currentState ->
                    currentState.copy(surfaceRequest = newSurfaceRequest)
                }
            }
        }

    private fun getImageAnalysisUseCase() =
        ImageAnalysis.Builder().apply {
            setResolutionSelector(getCameraResolutionSelector())
            setOutputImageRotationEnabled(true)
        }.build()

    private fun getCameraResolutionSelector() =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

    private fun cleanUpCameraResources(
        imageAnalysisUseCase: ImageAnalysis,
        processCameraProvider: ProcessCameraProvider,
        cameraExecutor: ExecutorService?
    ) {
        broadcastFacePresence(false)
        imageAnalysisUseCase.clearAnalyzer()
        processCameraProvider.unbindAll()
        cameraExecutor?.let { if (!it.isShutdown) it.shutdownNow() }
    }

    override fun onCleared() {
        super.onCleared()
        poseDetector.close()
        broadcastFacePresence(false)
    }

    private data class Point(
        val x: Float,
        val y: Float
    )

    companion object {
        private const val MIN_LANDMARK_LIKELIHOOD = 0.70f
        private const val MIN_OPTIONAL_LANDMARK_LIKELIHOOD = 0.45f
        private const val MIN_SHOULDER_WIDTH_RATIO = 0.16f
        private const val MIN_HEAD_TO_SHOULDER_HEIGHT_RATIO = 0.08f
        private const val MIN_HIP_WIDTH_RATIO = 0.10f
        private const val MIN_HEAD_TO_HIP_HEIGHT_RATIO = 0.24f
        private const val MIN_TORSO_HEIGHT_RATIO = 0.12f
        private const val MIN_VISIBLE_TORSO_HEIGHT_RATIO = 0.08f
        private const val MIN_KNEE_DROP_RATIO = 0.08f
        private const val MIN_ANKLE_DROP_RATIO = 0.05f
        private const val MIN_BODY_CENTER_X_RATIO = 0.25f
        private const val MAX_BODY_CENTER_X_RATIO = 0.75f
        private const val MAX_TORSO_LEAN_RATIO = 0.55f
        private const val MAX_DESK_TORSO_LEAN_RATIO = 0.85f
        private const val MAX_SHOULDER_TILT_RATIO = 0.45f
        private const val MIN_ELBOW_DROP_RATIO = 0.35f
        private const val MIN_WRIST_DROP_RATIO = 0.45f
        private const val MIN_FOREARM_LENGTH_RATIO = 0.25f
        private const val MIN_VISIBLE_DESK_ARMS = 1
        private const val MIN_SITTING_KNEE_ANGLE = 55f
        private const val MAX_SITTING_KNEE_ANGLE = 145f
        private const val CAMERA_INITIALIZATION_ATTEMPTS = 6
        private const val CAMERA_INITIALIZATION_RETRY_DELAY_MS = 350L
    }
}
