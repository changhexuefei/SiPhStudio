package org.jason.siph.ui.positioner


import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jason.siph.domain.positioner.OpticalDelta

@Composable
fun JogControlPanel(
    linearStepUm: Double,
    angleStepDeg: Double,
    enabled: Boolean,
    onLinearStepChange: (Double) -> Unit,
    onAngleStepChange: (Double) -> Unit,
    onJog: (OpticalDelta) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Jog Control",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NumberField(
                    label = "Linear Step um",
                    value = linearStepUm,
                    onValueChange = onLinearStepChange,
                    modifier = Modifier.weight(1f)
                )

                NumberField(
                    label = "Angle Step deg",
                    value = angleStepDeg,
                    onValueChange = onAngleStepChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                JogAxisCard(
                    axisName = "X",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(dxUm = -linearStepUm)) },
                    onPlus = { onJog(OpticalDelta(dxUm = linearStepUm)) },
                    modifier = Modifier.weight(1f)
                )

                JogAxisCard(
                    axisName = "Y",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(dyUm = -linearStepUm)) },
                    onPlus = { onJog(OpticalDelta(dyUm = linearStepUm)) },
                    modifier = Modifier.weight(1f)
                )

                JogAxisCard(
                    axisName = "Z",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(dzUm = -linearStepUm)) },
                    onPlus = { onJog(OpticalDelta(dzUm = linearStepUm)) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                JogAxisCard(
                    axisName = "U",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(duDeg = -angleStepDeg)) },
                    onPlus = { onJog(OpticalDelta(duDeg = angleStepDeg)) },
                    modifier = Modifier.weight(1f)
                )

                JogAxisCard(
                    axisName = "V",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(dvDeg = -angleStepDeg)) },
                    onPlus = { onJog(OpticalDelta(dvDeg = angleStepDeg)) },
                    modifier = Modifier.weight(1f)
                )

                JogAxisCard(
                    axisName = "W",
                    enabled = enabled,
                    onMinus = { onJog(OpticalDelta(dwDeg = -angleStepDeg)) },
                    onPlus = { onJog(OpticalDelta(dwDeg = angleStepDeg)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun JogAxisCard(
    axisName: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = axisName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HoldJogButton(
                    text = "-",
                    enabled = enabled,
                    onJog = onMinus,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                )

                HoldJogButton(
                    text = "+",
                    enabled = enabled,
                    onJog = onPlus,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                )
            }
        }
    }
}

@Composable
private fun HoldJogButton(
    text: String,
    enabled: Boolean,
    onJog: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp)
) {
    val currentOnJog by rememberUpdatedState(onJog)

    OutlinedButton(
        onClick = {},
        enabled = enabled,
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) {
                return@pointerInput
            }

            while (true) {
                awaitPointerEventScope {
                    awaitFirstDown(requireUnconsumed = false)
                }

                currentOnJog()

                var pressed = true

                coroutineScope {
                    val repeatJob = launch {
                        delay(280L)

                        while (pressed) {
                            currentOnJog()
                            delay(90L)
                        }
                    }

                    awaitPointerEventScope {
                        while (pressed) {
                            val event = awaitPointerEvent()
                            pressed = event.changes.any { it.pressed }
                        }
                    }

                    repeatJob.cancel()
                }
            }
        },
        contentPadding = contentPadding
    ) {
        Text(text)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text ->
            text.toDoubleOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}
