package com.ameen.photoscan.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import kotlin.math.cos
import kotlin.math.sin

/** Small monospaced status pill. */
@Composable
fun StatusChip(text: String, color: Color = Ink.Signal, dim: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.Base.copy(alpha = 0.62f))
            .border(BorderStroke(1.dp, color.copy(alpha = if (dim) 0.25f else 0.5f)), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color) }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (dim) Ink.TextDim else Ink.Text,
            modifier = Modifier.padding(start = 7.dp)
        )
    }
}

/**
 * The capture dial.
 *
 * The outer ticks are the 36 directions the subject has been seen from, so a gap in the ring is a
 * gap in the model. The inner arc fills as the camera moves and completes exactly when the next shot
 * fires, which is what makes pose-triggered capture feel deliberate rather than random.
 */
@Composable
fun CaptureDial(
    shots: Int,
    armProgress: Float,
    coverage: BooleanArray,
    coverageVersion: Int,
    tracking: Boolean,
    tooFast: Boolean,
    firedToggle: Boolean,
    modifier: Modifier = Modifier
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(firedToggle) {
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(420))
    }
    val arc by animateFloatAsState(
        targetValue = armProgress.coerceIn(0f, 1f),
        animationSpec = tween(90),
        label = "arc"
    )

    val accent = when {
        !tracking -> Ink.Coral
        tooFast -> Ink.Amber
        else -> Ink.Signal
    }

    Box(modifier.size(148.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(148.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)

            drawCircle(Ink.Base.copy(alpha = 0.5f), radius = r * 0.80f, center = c)
            drawCircle(Ink.Line.copy(alpha = 0.7f), radius = r * 0.80f, center = c, style = Stroke(1.dp.toPx()))

            // Coverage ticks
            @Suppress("UNUSED_EXPRESSION") coverageVersion
            for (i in 0 until 36) {
                val a = (i * 10f - 90f) * (Math.PI / 180.0).toFloat()
                val seen = coverage.getOrElse(i) { false }
                val inner = r * 0.87f
                val outer = if (seen) r * 0.99f else r * 0.93f
                drawLine(
                    color = if (seen) Ink.Signal.copy(alpha = 0.9f) else Ink.Line,
                    start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                    end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                    strokeWidth = if (seen) 3.dp.toPx() else 1.5.dp.toPx()
                )
            }

            // Arming arc
            val stroke = 5.dp.toPx()
            val ringR = r * 0.72f
            drawArc(
                color = Ink.Line.copy(alpha = 0.55f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(c.x - ringR, c.y - ringR),
                size = Size(ringR * 2, ringR * 2),
                style = Stroke(stroke)
            )
            if (arc > 0.001f) {
                drawArc(
                    color = accent,
                    startAngle = -90f, sweepAngle = 360f * arc, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR),
                    size = Size(ringR * 2, ringR * 2),
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }

            // Shot flash
            if (pulse.value > 0.001f) {
                drawCircle(
                    color = Ink.Signal.copy(alpha = 0.55f * pulse.value),
                    radius = r * (0.72f + 0.30f * (1f - pulse.value)),
                    center = c,
                    style = Stroke(3.dp.toPx())
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = shots.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = Ink.Text
            )
            Text(
                text = "SHOTS",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextDim
            )
        }
    }
}

@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Ink.Signal,
    onClick: () -> Unit
) {
    val bg = if (filled) accent else Ink.Base.copy(alpha = 0.6f)
    val fg = when {
        !enabled -> Ink.TextDim
        filled -> Ink.Base
        else -> Ink.Text
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else Ink.SurfaceHi)
            .border(
                BorderStroke(1.dp, if (filled) Color.Transparent else Ink.Line),
                RoundedCornerShape(50)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, textAlign = TextAlign.Center)
    }
}

@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Ink.Base.copy(alpha = 0.65f))
            .border(BorderStroke(1.dp, Ink.Line), RoundedCornerShape(50))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) Ink.Signal else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sel) Ink.Base else Ink.TextDim
                )
            }
        }
    }
}

/** Thin determinate bar used on the processing screen. */
fun DrawScope.progressBar(fraction: Float, height: Float) {
    val w = size.width
    drawRoundRect(
        color = Ink.Line,
        size = Size(w, height),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2)
    )
    if (fraction > 0f) {
        drawRoundRect(
            color = Ink.Signal,
            size = Size(w * fraction.coerceIn(0f, 1f), height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2)
        )
    }
}
