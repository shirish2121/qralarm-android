package com.sweak.qralarm.features.add_edit_alarm.destinations.advanced.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.sweak.qralarm.R
import com.sweak.qralarm.core.designsystem.component.QRAlarmRadioButton
import com.sweak.qralarm.core.designsystem.theme.QRAlarmTheme
import com.sweak.qralarm.core.designsystem.theme.space

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseFaceWakeDurationBottomSheet(
    initialFaceWakeDurationInSeconds: Int,
    availableFaceWakeDurationsInSeconds: List<Int>,
    onDismissRequest: (newFaceWakeDurationInSeconds: Int) -> Unit
) {
    val modalBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedFaceWakeDurationInSeconds by remember {
        mutableIntStateOf(initialFaceWakeDurationInSeconds)
    }

    ModalBottomSheet(
        onDismissRequest = { onDismissRequest(selectedFaceWakeDurationInSeconds) },
        sheetState = modalBottomSheetState
    ) {
        Column(
            modifier = Modifier
                .padding(
                    start = MaterialTheme.space.mediumLarge,
                    end = MaterialTheme.space.mediumLarge,
                    bottom = MaterialTheme.space.xLarge
                )
        ) {
            Text(
                text = stringResource(R.string.face_wake_duration),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(bottom = MaterialTheme.space.mediumLarge)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.space.mediumLarge),
                modifier = Modifier.selectableGroup()
            ) {
                availableFaceWakeDurationsInSeconds.forEach { duration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedFaceWakeDurationInSeconds == duration,
                                onClick = { selectedFaceWakeDurationInSeconds = duration },
                                role = Role.RadioButton
                            )
                    ) {
                        QRAlarmRadioButton(
                            selected = selectedFaceWakeDurationInSeconds == duration,
                            onClick = null
                        )

                        Text(
                            text = getFaceWakeDurationLabel(duration),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(start = MaterialTheme.space.medium)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getFaceWakeDurationLabel(durationInSeconds: Int): String {
    return if (durationInSeconds >= 3600 && durationInSeconds % 3600 == 0) {
        val hours = durationInSeconds / 3600
        pluralStringResource(R.plurals.hours, hours, hours)
    } else {
        val minutes = durationInSeconds / 60
        pluralStringResource(R.plurals.minutes, minutes, minutes)
    }
}

@Preview
@Composable
private fun ChooseFaceWakeDurationBottomSheetPreview() {
    QRAlarmTheme {
        ChooseFaceWakeDurationBottomSheet(
            initialFaceWakeDurationInSeconds = 300,
            availableFaceWakeDurationsInSeconds = listOf(
                10_800,
                7_200,
                3_600,
                600,
                300,
                180,
                120,
                60
            ),
            onDismissRequest = {}
        )
    }
}
