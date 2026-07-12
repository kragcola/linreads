package dev.readflow.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AccessibleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    label: String,
    valueDescription: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val currentValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = label
                stateDescription = valueDescription
                progressBarRangeInfo = ProgressBarRangeInfo(currentValue, valueRange, steps)
                setProgress { requestedValue ->
                    onValueChange(requestedValue.snapToSliderStep(valueRange, steps))
                    onValueChangeFinished?.invoke()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = currentValue,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {},
        )
    }
}

private fun Float.snapToSliderStep(
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    val coerced = coerceIn(valueRange.start, valueRange.endInclusive)
    if (steps <= 0 || valueRange.start == valueRange.endInclusive) return coerced

    val intervals = steps + 1
    val fraction = (coerced - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    return valueRange.start +
        (fraction * intervals).roundToInt().toFloat() / intervals *
        (valueRange.endInclusive - valueRange.start)
}
