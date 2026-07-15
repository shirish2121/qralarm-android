package com.sweak.qralarm.features.add_edit_alarm.destinations.advanced

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sweak.qralarm.R
import com.sweak.qralarm.core.designsystem.icon.QRAlarmIcons
import com.sweak.qralarm.core.designsystem.theme.BlueZodiac
import com.sweak.qralarm.core.designsystem.theme.Jacarta
import com.sweak.qralarm.core.designsystem.theme.QRAlarmTheme
import com.sweak.qralarm.core.designsystem.theme.isQRAlarmTheme
import com.sweak.qralarm.core.designsystem.theme.space
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmFlowState
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmFlowUserEvent.AdvancedAlarmSettingsScreenUserEvent
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmViewModel
import com.sweak.qralarm.features.add_edit_alarm.components.ChoiceSetting
import com.sweak.qralarm.core.ui.components.ToggleSetting
import com.sweak.qralarm.features.add_edit_alarm.destinations.add_edit.getCancelLockDurationAbbreviatedString
import com.sweak.qralarm.features.add_edit_alarm.destinations.add_edit.getSecondsDurationString
import com.sweak.qralarm.features.add_edit_alarm.destinations.advanced.components.ChooseCancelLockDurationBottomSheet
import com.sweak.qralarm.features.add_edit_alarm.destinations.advanced.components.ChooseFaceWakeDurationBottomSheet
import com.sweak.qralarm.features.add_edit_alarm.destinations.advanced.components.ChooseGentleWakeUpDurationBottomSheet
import com.sweak.qralarm.features.add_edit_alarm.destinations.advanced.components.ChooseTemporaryMuteDurationBottomSheet

@Composable
fun AdvancedAlarmSettingsScreen(
    addEditAlarmViewModel: AddEditAlarmViewModel,
    onCancelClicked: () -> Unit
) {
    val addEditAlarmScreenState by addEditAlarmViewModel.state.collectAsStateWithLifecycle()

    AdvancedAlarmSettingsScreenContent(
        state = addEditAlarmScreenState,
        onEvent = { event ->
            when (event) {
                is AdvancedAlarmSettingsScreenUserEvent.OnCancelClicked -> onCancelClicked()
                else -> addEditAlarmViewModel.onEvent(event)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedAlarmSettingsScreenContent(
    state: AddEditAlarmFlowState,
    onEvent: (AdvancedAlarmSettingsScreenUserEvent) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.advanced_settings),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { onEvent(AdvancedAlarmSettingsScreenUserEvent.OnCancelClicked) }
                    ) {
                        Icon(
                            imageVector = QRAlarmIcons.BackArrow,
                            contentDescription =
                                stringResource(R.string.content_description_back_arrow_icon)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (MaterialTheme.isQRAlarmTheme)
                        Modifier.background(
                            brush = Brush.verticalGradient(listOf(Jacarta, BlueZodiac))
                        )
                    else Modifier
                )
                .verticalScroll(rememberScrollState())
        ) {
            Column(modifier = Modifier.padding(paddingValues)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.space.medium,
                            vertical = MaterialTheme.space.mediumLarge
                        )
                ) {
                    ChoiceSetting(
                        onClick = {
                            onEvent(
                                AdvancedAlarmSettingsScreenUserEvent
                                    .ChooseGentleWakeUpDurationDialogVisible(isVisible = true)
                            )
                        },
                        title = stringResource(R.string.gentle_wake_up),
                        description = stringResource(R.string.gentle_wake_up_description),
                        choiceName = getSecondsDurationString(
                            state.gentleWakeupDurationInSeconds
                        )
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                    )

                    ChoiceSetting(
                        onClick = {
                            onEvent(
                                AdvancedAlarmSettingsScreenUserEvent
                                    .ChooseTemporaryMuteDurationDialogVisible(isVisible = true)
                            )
                        },
                        title = stringResource(R.string.temporary_mute),
                        description = stringResource(R.string.temporary_mute_description),
                        choiceName = getSecondsDurationString(
                            state.temporaryMuteDurationInSeconds
                        )
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                    )

                    ToggleSetting(
                        isChecked = state.isFaceWakeEnabled,
                        onCheckedChange = {
                            onEvent(
                                AdvancedAlarmSettingsScreenUserEvent
                                    .FaceWakeEnabledChanged(isEnabled = it)
                            )
                        },
                        title = stringResource(R.string.face_wake),
                        description = stringResource(R.string.face_wake_description)
                    )

                    if (state.isFaceWakeEnabled) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = LocalContentColor.current,
                            modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                        )

                        ChoiceSetting(
                            onClick = {
                                onEvent(
                                    AdvancedAlarmSettingsScreenUserEvent
                                        .ChooseFaceWakeDurationDialogVisible(isVisible = true)
                                )
                            },
                            title = stringResource(R.string.face_wake_duration),
                            description = stringResource(R.string.face_wake_duration_description),
                            choiceName = getFaceWakeDurationString(
                                state.faceWakeDurationInSeconds
                            )
                        )
                    }
                }

                if (state.isCodeEnabled) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = MaterialTheme.space.medium,
                                end = MaterialTheme.space.medium,
                                bottom = MaterialTheme.space.mediumLarge
                            )
                    ) {
                        ChoiceSetting(
                            onClick = {
                                onEvent(
                                    AdvancedAlarmSettingsScreenUserEvent
                                        .ChooseCancelLockDurationDialogVisible(isVisible = true)
                                )
                            },
                            title = stringResource(R.string.cancellation_lock),
                            description = stringResource(R.string.cancellation_lock_description),
                            choiceName = getCancelLockDurationAbbreviatedString(
                                state.cancelLockDurationInMinutes
                            )
                        )

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = LocalContentColor.current,
                            modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                        )

                        ToggleSetting(
                            isChecked = state.isEmergencyTaskEnabled,
                            onCheckedChange = {
                                onEvent(
                                    AdvancedAlarmSettingsScreenUserEvent
                                        .EmergencyTaskEnabledChanged(isEnabled = it)
                                )
                            },
                            title = stringResource(R.string.emergency),
                            description = stringResource(
                                R.string.emergency_task_setting_description
                            )
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = LocalContentColor.current,
                                modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                            )

                            ToggleSetting(
                                isChecked = state.isOpenCodeLinkEnabled,
                                onCheckedChange = {
                                    onEvent(
                                        AdvancedAlarmSettingsScreenUserEvent
                                            .OpenCodeLinkEnabledChanged(isEnabled = it)
                                    )
                                },
                                title = stringResource(R.string.open_code_link),
                                description = stringResource(R.string.open_code_link_description)
                            )
                        }
                    }
                }
            }
        }
    }

    if (state.isChooseGentleWakeUpDurationDialogVisible) {
        ChooseGentleWakeUpDurationBottomSheet(
            initialGentleWakeUpDurationInSeconds = state.gentleWakeupDurationInSeconds,
            availableGentleWakeUpDurationsInSeconds = state.availableGentleWakeUpDurationsInSeconds,
            onDismissRequest = { newGentleWakeUpDurationInSeconds ->
                onEvent(
                    AdvancedAlarmSettingsScreenUserEvent.GentleWakeUpDurationSelected(
                        newGentleWakeUpDurationInSeconds = newGentleWakeUpDurationInSeconds
                    )
                )
            }
        )
    }

    if (state.isChooseTemporaryMuteDurationDialogVisible) {
        ChooseTemporaryMuteDurationBottomSheet(
            initialTemporaryMuteDurationInSeconds = state.temporaryMuteDurationInSeconds,
            availableTemporaryMuteDurationsInSeconds =
                state.availableTemporaryMuteDurationsInSeconds,
            onDismissRequest = { newTemporaryMuteDurationInSeconds ->
                onEvent(
                    AdvancedAlarmSettingsScreenUserEvent.TemporaryMuteDurationSelected(
                        newTemporaryMuteDurationInSeconds = newTemporaryMuteDurationInSeconds
                    )
                )
            }
        )
    }

    if (state.isChooseFaceWakeDurationDialogVisible) {
        ChooseFaceWakeDurationBottomSheet(
            initialFaceWakeDurationInSeconds = state.faceWakeDurationInSeconds,
            availableFaceWakeDurationsInSeconds = state.availableFaceWakeDurationsInSeconds,
            onDismissRequest = { newFaceWakeDurationInSeconds ->
                onEvent(
                    AdvancedAlarmSettingsScreenUserEvent.FaceWakeDurationSelected(
                        newFaceWakeDurationInSeconds = newFaceWakeDurationInSeconds
                    )
                )
            }
        )
    }

    if (state.isChooseCancelLockDurationDialogVisible) {
        ChooseCancelLockDurationBottomSheet(
            initialCancelLockDurationInMinutes = state.cancelLockDurationInMinutes,
            availableCancelLockDurationsInMinutes = state.availableCancelLockDurationsInMinutes,
            onDismissRequest = { newCancelLockDurationInMinutes ->
                onEvent(
                    AdvancedAlarmSettingsScreenUserEvent.CancelLockDurationSelected(
                        newCancelLockDurationInMinutes = newCancelLockDurationInMinutes
                    )
                )
            }
        )
    }
}

@Composable
private fun getFaceWakeDurationString(durationInSeconds: Int): String {
    return when {
        durationInSeconds >= 3600 && durationInSeconds % 3600 == 0 -> {
            val hours = durationInSeconds / 3600
            pluralStringResource(R.plurals.hours, hours, hours)
        }
        durationInSeconds >= 60 && durationInSeconds % 60 == 0 -> {
            val minutes = durationInSeconds / 60
            pluralStringResource(R.plurals.minutes, minutes, minutes)
        }
        else -> getSecondsDurationString(durationInSeconds)
    }
}

@Preview
@Composable
private fun AdvancedAlarmSettingsScreenContentPreview() {
    QRAlarmTheme {
        AdvancedAlarmSettingsScreenContent(
            state = AddEditAlarmFlowState(),
            onEvent = {}
        )
    }
}
