package com.riyaaz.tanpura.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyaaz.tanpura.audio.TanpuraEngine
import com.riyaaz.tanpura.ui.theme.TanpuraColors
import kotlin.math.sin

/** Snapshot of what the engine is doing, sampled once per display frame. */
data class InstrumentVisual(
    val activity: FloatArray = FloatArray(TanpuraEngine.MAX_STRINGS),
    val level: Float = 0f,
    val strum: Float = 0f,
    val stringCount: Int = 4,
    val phase: Float = 0f,
) {
    // Data class with an array member: identity comparison is fine here because a
    // fresh instance is produced every frame and it is never used as a map key.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Samples the engine's meters once per frame. Only runs while [animate] is true,
 * so a paused tanpura costs nothing.
 */
@Composable
fun rememberInstrumentVisual(engine: TanpuraEngine, animate: Boolean): State<InstrumentVisual> {
    val state = remember { mutableStateOf(InstrumentVisual()) }
    LaunchedEffect(engine, animate) {
        if (!animate) {
            state.value = InstrumentVisual(stringCount = engine.activeStringCount)
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var phase = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000.0).toFloat()
                lastNanos = now
                phase = (phase + dt * 9f) % 1f
                val activity = FloatArray(TanpuraEngine.MAX_STRINGS) { engine.stringActivity(it) }
                state.value = InstrumentVisual(
                    activity = activity,
                    level = engine.outputLevel,
                    strum = engine.strumPosition,
                    stringCount = engine.activeStringCount,
                    phase = phase,
                )
            }
        }
    }
    return state
}

/**
 * A front-on tanpura: pegbox and neck at the top, gourd at the bottom, strings
 * running the full length. Each string flexes when it is struck and the strum
 * marker sweeps across them in time with the sequencer, so you can see the cycle
 * you are hearing.
 */
@Composable
fun TanpuraGraphic(
    visual: InstrumentVisual,
    stringLabels: List<String>,
    modifier: Modifier = Modifier,
    heightDp: Int = 250,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = TanpuraColors.OnSurfaceMuted,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
    ) {
        drawInstrument(visual, stringLabels, measurer, labelStyle)
    }
}

private fun DrawScope.drawInstrument(
    visual: InstrumentVisual,
    labels: List<String>,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f

    val topY = h * 0.16f          // where the strings leave the pegbox
    val bridgeY = h * 0.775f      // the jawari bridge
    val bottomY = h * 0.86f       // the strings' anchor on the gourd
    val neckHalf = (w * 0.075f).coerceAtLeast(16f)
    val gourdRx = (w * 0.185f).coerceAtLeast(38f)
    val gourdRy = h * 0.155f

    // --- resonance glow, driven by output level ---
    val glow = visual.level.coerceIn(0f, 1f)
    if (glow > 0.01f) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    TanpuraColors.Brass.copy(alpha = 0.16f * glow),
                    Color.Transparent,
                ),
                center = Offset(cx, bridgeY - gourdRy * 0.2f),
                radius = gourdRx * 3.2f,
            ),
            radius = gourdRx * 3.2f,
            center = Offset(cx, bridgeY - gourdRy * 0.2f),
        )
    }

    // --- gourd (tumba) ---
    val gourdTop = bridgeY - gourdRy * 0.55f
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(TanpuraColors.Wood, TanpuraColors.WoodDark),
            startY = gourdTop,
            endY = gourdTop + gourdRy * 2f,
        ),
        topLeft = Offset(cx - gourdRx, gourdTop),
        size = Size(gourdRx * 2f, gourdRy * 2f),
    )
    // soundboard (tabli)
    drawOval(
        color = Color(0xFF2A1C0F),
        topLeft = Offset(cx - gourdRx * 0.72f, gourdTop + gourdRy * 0.12f),
        size = Size(gourdRx * 1.44f, gourdRy * 0.95f),
    )
    // rim highlight
    drawOval(
        color = TanpuraColors.Brass.copy(alpha = 0.22f),
        topLeft = Offset(cx - gourdRx, gourdTop),
        size = Size(gourdRx * 2f, gourdRy * 2f),
        style = Stroke(width = 1.5f),
    )

    // --- neck (dandi) ---
    drawRoundRectCompat(
        left = cx - neckHalf,
        top = h * 0.075f,
        right = cx + neckHalf,
        bottom = bridgeY,
        radius = neckHalf * 0.35f,
        brush = Brush.horizontalGradient(
            colors = listOf(TanpuraColors.WoodDark, TanpuraColors.Wood, TanpuraColors.WoodDark),
            startX = cx - neckHalf,
            endX = cx + neckHalf,
        ),
    )

    // --- pegbox and tuning pegs ---
    drawRoundRectCompat(
        left = cx - neckHalf * 1.25f,
        top = h * 0.03f,
        right = cx + neckHalf * 1.25f,
        bottom = h * 0.10f,
        radius = 4f,
        brush = Brush.verticalGradient(
            colors = listOf(TanpuraColors.Wood, TanpuraColors.WoodDark),
            startY = h * 0.03f,
            endY = h * 0.10f,
        ),
    )
    val pegCount = visual.stringCount.coerceIn(1, TanpuraEngine.MAX_STRINGS)
    for (i in 0 until pegCount) {
        val side = if (i % 2 == 0) -1f else 1f
        val py = h * (0.045f + 0.028f * (i / 2))
        drawRoundRectCompat(
            left = cx + side * neckHalf * 1.25f - (if (side < 0) neckHalf * 0.6f else 0f),
            top = py,
            right = cx + side * neckHalf * 1.25f + (if (side > 0) neckHalf * 0.6f else 0f),
            bottom = py + h * 0.014f,
            radius = 3f,
            color = TanpuraColors.BrassDim,
        )
    }

    // --- strings ---
    val count = pegCount
    val spread = neckHalf * 1.35f
    val step = if (count > 1) (spread * 2f) / (count - 1) else 0f
    val strumX = cx - spread + (visual.strum.coerceIn(0f, 1f) / 0.72f).coerceAtMost(1f) * spread * 2f

    for (i in 0 until count) {
        val x = if (count > 1) cx - spread + step * i else cx
        val act = visual.activity.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        val amp = act * (w * 0.016f + 3f)
        // Thicker, darker wire for the low brass string (always the last one).
        val isBrass = i == count - 1
        val thickness = if (isBrass) 2.6f else 1.5f
        val color = if (isBrass) TanpuraColors.Brass else TanpuraColors.String

        val path = Path()
        val segments = 26
        for (s in 0..segments) {
            val t = s.toFloat() / segments
            val y = topY + (bottomY - topY) * t
            // Fixed at both ends, maximum displacement near the middle.
            val envelope = sin(Math.PI * t).toFloat()
            val wiggle = sin((t * 3f + visual.phase) * 2f * Math.PI).toFloat()
            val px = x + amp * envelope * wiggle
            if (s == 0) path.moveTo(px, y) else path.lineTo(px, y)
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 0.55f + 0.45f * act),
            style = Stroke(width = thickness, cap = StrokeCap.Round),
        )

        // Swara label above the pegbox.
        val label = labels.getOrNull(i)
        if (label != null) {
            val layout = measurer.measure(
                AnnotatedString(label),
                style = labelStyle.copy(
                    color = if (act > 0.15f) TanpuraColors.Brass else TanpuraColors.OnSurfaceMuted,
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x - layout.size.width / 2f, h * 0.115f),
            )
        }
    }

    // --- bridge (jawari) ---
    drawRoundRectCompat(
        left = cx - spread - 8f,
        top = bridgeY - 3f,
        right = cx + spread + 8f,
        bottom = bridgeY + 4f,
        radius = 2f,
        color = TanpuraColors.String.copy(alpha = 0.85f),
    )

    // --- strum marker ---
    if (visual.level > 0.005f) {
        drawCircle(
            color = TanpuraColors.Brass.copy(alpha = 0.75f),
            radius = 3.5f,
            center = Offset(strumX, bridgeY - h * 0.06f),
        )
    }
}

/** Small helper because [drawRoundRect] takes a size, not two corners. */
private fun DrawScope.drawRoundRectCompat(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    radius: Float,
    color: Color? = null,
    brush: Brush? = null,
) {
    val cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
    val topLeft = Offset(minOf(left, right), top)
    val rectSize = Size(kotlin.math.abs(right - left), bottom - top)
    if (brush != null) {
        drawRoundRect(brush = brush, topLeft = topLeft, size = rectSize, cornerRadius = cornerRadius)
    } else if (color != null) {
        drawRoundRect(color = color, topLeft = topLeft, size = rectSize, cornerRadius = cornerRadius)
    }
}
