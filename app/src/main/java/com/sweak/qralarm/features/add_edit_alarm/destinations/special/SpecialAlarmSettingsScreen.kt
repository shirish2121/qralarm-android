package com.sweak.qralarm.features.add_edit_alarm.destinations.special

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sweak.qralarm.BuildConfig
import com.sweak.qralarm.R
import com.sweak.qralarm.alarm.accessibility.QRAlarmAccessibilityService
import com.sweak.qralarm.core.designsystem.icon.QRAlarmIcons
import com.sweak.qralarm.core.designsystem.theme.BlueZodiac
import com.sweak.qralarm.core.designsystem.theme.Jacarta
import com.sweak.qralarm.core.designsystem.theme.QRAlarmTheme
import com.sweak.qralarm.core.designsystem.theme.isQRAlarmTheme
import com.sweak.qralarm.core.designsystem.theme.space
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmFlowState
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmFlowUserEvent.SpecialAlarmSettingsScreenUserEvent
import com.sweak.qralarm.features.add_edit_alarm.AddEditAlarmViewModel
import com.sweak.qralarm.features.add_edit_alarm.components.SimpleSetting
import com.sweak.qralarm.core.ui.components.ToggleSetting

@Composable
fun SpecialAlarmSettingsScreen(
    addEditAlarmViewModel: AddEditAlarmViewModel,
    onCancelClicked: () -> Unit,
    onRedirectToQRAlarmPro: () -> Unit
) {
    val addEditAlarmScreenState by addEditAlarmViewModel.state.collectAsStateWithLifecycle()

    SpecialAlarmSettingsScreenContent(
        state = addEditAlarmScreenState,
        onEvent = { event ->
            when (event) {
                is SpecialAlarmSettingsScreenUserEvent.OnCancelClicked -> {
                    onCancelClicked()
                }
                is SpecialAlarmSettingsScreenUserEvent.TryUseSpecialAlarmSettings -> {
                    onRedirectToQRAlarmPro()
                }
                else -> {
                    addEditAlarmViewModel.onEvent(event)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialAlarmSettingsScreenContent(
    state: AddEditAlarmFlowState,
    onEvent: (SpecialAlarmSettingsScreenUserEvent) -> Unit
) {
    val isDeveloperProMode = BuildConfig.DEBUG
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAccessibilityServiceEnabled by remember {
        mutableStateOf(QRAlarmAccessibilityService.isEnabled(context))
    }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityServiceEnabled = QRAlarmAccessibilityService.isEnabled(context)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.special_settings),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { onEvent(SpecialAlarmSettingsScreenUserEvent.OnCancelClicked) }
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
                if (isDeveloperProMode) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = MaterialTheme.space.medium,
                                top = MaterialTheme.space.mediumLarge,
                                end = MaterialTheme.space.medium,
                                bottom = MaterialTheme.space.small
                            )
                    ) {
                        SimpleSetting(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            },
                            title = stringResource(
                                if (isAccessibilityServiceEnabled) {
                                    R.string.accessibility_service_enabled
                                } else {
                                    R.string.enable_accessibility_service
                                }
                            ),
                            description = stringResource(
                                R.string.accessibility_service_required_description
                            ),
                            icon = QRAlarmIcons.AppSettings,
                            iconContentDescription = null
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.space.medium,
                            vertical = MaterialTheme.space.mediumLarge
                        )
                ) {
                    ToggleSetting(
                        isChecked = state.isDoNotLeaveAlarmEnabled,
                        onCheckedChange = {
                            onEvent(
                                if (isDeveloperProMode) {
                                    SpecialAlarmSettingsScreenUserEvent
                                        .DoNotLeaveAlarmEnabledChanged(it)
                                } else {
                                    SpecialAlarmSettingsScreenUserEvent.TryUseSpecialAlarmSettings
                                }
                            )
                        },
                        title = stringResource(R.string.do_not_leave_alarm),
                        description = stringResource(R.string.do_not_leave_alarm_description)
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                    )

                    ToggleSetting(
                        isChecked = state.isPowerOffGuardEnabled,
                        onCheckedChange = {
                            onEvent(
                                if (isDeveloperProMode) {
                                    SpecialAlarmSettingsScreenUserEvent
                                        .PowerOffGuardEnabledChanged(it)
                                } else {
                                    SpecialAlarmSettingsScreenUserEvent.TryUseSpecialAlarmSettings
                                }
                            )
                        },
                        title = stringResource(R.string.power_off_guard),
                        description = stringResource(R.string.power_off_guard_description)
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                    )

                    ToggleSetting(
                        isChecked = state.isBlockVolumeDownEnabled,
                        onCheckedChange = {
                            onEvent(
                                if (isDeveloperProMode) {
                                    SpecialAlarmSettingsScreenUserEvent
                                        .BlockVolumeDownEnabledChanged(it)
                                } else {
                                    SpecialAlarmSettingsScreenUserEvent.TryUseSpecialAlarmSettings
                                }
                            )
                        },
                        title = stringResource(R.string.block_volume_down),
                        description = stringResource(R.string.block_volume_down_description)
                    )

                    HorizontalDivider(
                        thickness = 1.dp,
                        color = LocalContentColor.current,
                        modifier = Modifier.padding(horizontal = MaterialTheme.space.medium)
                    )

                    ToggleSetting(
                        isChecked = state.isKeepRingerOnEnabled,
                        onCheckedChange = {
                            onEvent(
                                if (isDeveloperProMode) {
                                    SpecialAlarmSettingsScreenUserEvent
                                        .KeepRingerOnEnabledChanged(it)
                                } else {
                                    SpecialAlarmSettingsScreenUserEvent.TryUseSpecialAlarmSettings
                                }
                            )
                        },
                        title = stringResource(R.string.keep_ringer_on),
                        description = stringResource(R.string.keep_ringer_on_description)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SpecialAlarmSettingsScreenContentPreview() {
    QRAlarmTheme {
        SpecialAlarmSettingsScreenContent(
            state = AddEditAlarmFlowState(),
            onEvent = {}
        )
    }
}
