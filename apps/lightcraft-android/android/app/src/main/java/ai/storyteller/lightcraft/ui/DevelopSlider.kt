package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.SliderTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign

/**
 * Where along the track a value sits. Linear, except the white balance temperature in Kelvin, which
 * the reference spreads piecewise (2000–7900 K over 59 % of the track, then 7900–10000, 10000–17000
 * and 17000–50000 K) so the useful temperatures get most of it.
 */
private class SliderScale(private val min: Double, private val max: Double, private val kelvin: Boolean) {
    fun position(v: Double): Float {
        val p = if (kelvin) kelvinPosition(v.coerceIn(min, max)) else (v - min) / (max - min)
        return p.toFloat().coerceIn(0f, 1f)
    }

    fun value(position: Float, step: Double): Double {
        val p = position.toDouble().coerceIn(0.0, 1.0)
        if (kelvin) return kelvinAt(p).coerceIn(min, max).let { Math.round(it).toDouble() }
        val v = min + p * (max - min)
        val snapped = if (step > 0) min + ((v - min) / step).roundToLong() * step else v
        return snapped.coerceIn(min, max)
    }

    companion object {
        fun of(c: DevelopControl) = SliderScale(c.min, c.max, kelvin = c.id == "wb.temp" && c.min > 0)

        /** The reference's Kelvin breakpoints: (temperature, place on the track). */
        private val KELVIN = listOf(2000.0 to 0.0, 7900.0 to 0.59, 10000.0 to 0.695, 17000.0 to 0.835, 50000.0 to 1.0)

        private fun kelvinPosition(k: Double): Double {
            for (i in 1 until KELVIN.size) {
                val (k0, p0) = KELVIN[i - 1]
                val (k1, p1) = KELVIN[i]
                if (k <= k1) return p0 + (k - k0) / (k1 - k0) * (p1 - p0)
            }
            return 1.0
        }

        private fun kelvinAt(p: Double): Double {
            for (i in 1 until KELVIN.size) {
                val (k0, p0) = KELVIN[i - 1]
                val (k1, p1) = KELVIN[i]
                if (p <= p1) return k0 + (p - p0) / (p1 - p0) * (k1 - k0)
            }
            return KELVIN.last().first
        }
    }
}

private fun DevelopControl.magnitude(v: Double): Pair<Int, String> {
    val decimals = this.decimals.toInt()
    val shown = if (abs(v) < 0.5 * 10.0.pow(-decimals)) 0.0 else v
    return shown.sign.toInt() to String.format(Locale.US, "%.${decimals}f", abs(shown))
}

/** A brush's or an amount's slider: its value read plain (no sign), as the reference's own readouts for them. */
private val DevelopControl.plain: Boolean get() =
    id.startsWith("brush.") || id.startsWith("watermark.") || id == "profile.amount" || id == "slideshow.seconds" || id == "export.quality"

/**
 * The value as the reference's panels show it: a sign and a space before any value but 0 ("+ 25",
 * "− 25", "0"; even on a one-way slider: "+ 50"), its decimals always shown ("+ 0.35"), Kelvin as
 * a whole number with its unit.
 */
fun DevelopControl.format(v: Double): String {
    if (id == "wb.temp" && min > 0) return "${Math.round(v)}K"
    val (sign, m) = magnitude(v)
    return when {
        sign > 0 && !plain -> "+ $m"
        sign < 0 -> "− $m"
        else -> m
    }
}

val DevelopControl.isChanged: Boolean get() = abs(value - defaultValue) > max(step / 2, 1e-9)

/**
 * The slider being dragged, if any, and what the editor's pill says: while one moves, the editor
 * shows only it (its track and thumb) over the photo, its name and value in a pill on the top bar.
 */
@Stable
class SliderDrag {
    var id by mutableStateOf<String?>(null)
    var text by mutableStateOf("")
}

/** The editor's slider drag; none outside the editor's panels (a dragged slider elsewhere clears nothing). */
val LocalSliderDrag = staticCompositionLocalOf<SliderDrag?> { null }

/** Hidden, its room kept, while one of the editor's sliders is dragged. */
@Composable
fun Modifier.hiddenWhileDragging(): Modifier = if (LocalSliderDrag.current?.id != null) alpha(0f) else this

private val labelStyle = TextStyle(fontFamily = Inter, fontSize = 15.sp, lineHeight = 19.5.sp, color = Lc.sliderText)

/** Sliders whose fill starts at the track's left end rather than at their default (the reference's). */
private val fillFromStart = setOf(
    "grain.amount", "grain.size", "grain.roughness", "vignette.highlights",
    "detail.sharpenAmount", "detail.sharpenRadius", "detail.sharpenDetail", "detail.sharpenMasking",
    "detail.nrLuminance", "detail.nrDetail", "detail.nrContrast", "detail.nrColor", "detail.nrColorDetail", "detail.nrColorSmoothness",
    "enhance.denoise", "brush.size", "brush.feather", "brush.opacity", "mask.pickRefine",
)

/** The reference's slider geometry, in dp: the thumb's ring and the gap the track leaves around it. */
private const val RING_OUTER = 10f
private const val RING_STROKE = 2f
private const val RING_DRAGGED = 8f
private const val GAP = 14f
private const val EDGE = 16f

/** The row's height and where its track runs (dp from the row's top). */
private const val ROW = 52f
private const val TRACK_Y = 36.2f

/**
 * One develop slider, as the reference's (52 dp): its name and value (15 sp) over a thin track that runs
 * the row's width with a gap around a hollow ring thumb, a bar from the default to the thumb.
 * Drag anywhere along the row (bar or names, not its last 16 dp) sideways to move it by as much as
 * the finger moves (one track-width is the whole range); a tap beside the thumb nudges it a step
 * toward the tap (1 % of its range); a double-tap on the thumb resets it. A light tick when a drag
 * crosses or meets the default, and on a reset. Disabled, the whole row is faint.
 */
@Composable
fun DevelopSlider(
    control: DevelopControl,
    onChange: (Double) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = control.label,
    labelSize: TextUnit = 15.sp,
    edge: Float = EDGE,
    enabled: Boolean = true,
) {
    val scale = remember(control.id, control.min, control.max) { SliderScale.of(control) }
    val drag = LocalSliderDrag.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(control)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val reset by rememberUpdatedState(onReset)
    var dragging by remember { mutableStateOf(false) }
    // a double-tap within a second of a drag isn't a reset (the reference's)
    var lastDragEnd by remember { mutableStateOf(0L) }
    // the thumb's place, animated only when a double-tap resets it from afar
    val shown = remember { Animatable(scale.position(control.value)) }
    var animateNext by remember { mutableStateOf(false) }
    LaunchedEffect(control.value, scale) {
        val target = scale.position(control.value)
        if (animateNext) {
            animateNext = false
            shown.animateTo(target, tween(300))
        } else {
            shown.snapTo(target)
        }
    }
    val ringStroke by animateDpAsState(if (dragging) RING_DRAGGED.dp else RING_STROKE.dp, tween(200, easing = AccelerateDecelerate), label = "ring")
    val others = drag?.id != null && drag.id != control.id
    val steps = if (control.step > 0) (control.max - control.min) / control.step else 100.0
    fun commit(v: Double) {
        change(v)
        finished()
    }
    // the reference's pill: the name, a colon, the value as the panel shows it
    fun pill(v: Double) = "$label: ${control.format(v)}"
    Column(
        modifier
            .fillMaxWidth()
            .height(ROW.dp)
            .alpha(
                when {
                    others -> 0f
                    !enabled -> 0.3f
                    else -> 1f
                },
            )
            .pointerInput(control.id, enabled) {
                if (!enabled) return@pointerInput
                val edge = EDGE.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val travel = (size.width - 2 * edge - 2 * RING_OUTER.dp.toPx()).coerceAtLeast(1f)
                    var moving = false
                    var p0 = 0f
                    var last = 0.0
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        val d = pointer.position - down.position
                        if (!moving) {
                            if (abs(d.x) <= slop && abs(d.y) <= slop) continue
                            // up or down: the list scrolls
                            if (abs(d.x) <= abs(d.y)) break
                            // sideways but slow to start: nothing at all, not even a tap
                            if (pointer.uptimeMillis - down.uptimeMillis > 250) {
                                do {
                                    val rest = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    rest.consume()
                                } while (rest.pressed)
                                break
                            }
                            moving = true
                            dragging = true
                            p0 = scale.position(current.value)
                            last = current.value
                            drag?.id = current.id
                            drag?.text = pill(current.value)
                        }
                        pointer.consume()
                        val v = scale.value(p0 + d.x / travel, current.step)
                        if (v != last) {
                            val def = current.defaultValue
                            if (v == def || (last - def).sign * (v - def).sign < 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            last = v
                            drag?.text = pill(v)
                            change(v)
                        }
                    }
                    if (moving) {
                        dragging = false
                        if (drag?.id == current.id) drag.id = null
                        lastDragEnd = System.currentTimeMillis()
                        finished()
                    }
                }
            }
            .pointerInput(control.id, enabled) {
                if (!enabled) return@pointerInput
                val edge = EDGE.dp.toPx()
                val travel = { (size.width - 2 * edge - 2 * RING_OUTER.dp.toPx()).coerceAtLeast(1f) }
                fun thumbX() = edge + RING_OUTER.dp.toPx() + shown.value * travel()
                fun nudge(at: Offset) {
                    if (at.x < edge || at.x > size.width - edge) return
                    val dx = at.x - thumbX()
                    if (abs(dx) <= RING_OUTER.dp.toPx()) return
                    val c = current
                    val by = ceil(steps * 0.01) * (if (c.step > 0) c.step else (c.max - c.min) / 100)
                    commit((c.value + by * sign(dx)).coerceIn(c.min, c.max))
                }
                detectTapGestures(
                    onTap = { nudge(it) },
                    onDoubleTap = { at ->
                        val nearThumb = abs(at.x - thumbX()) <= 24.dp.toPx() && abs(at.y - TRACK_Y.dp.toPx()) <= 24.dp.toPx()
                        if (nearThumb && System.currentTimeMillis() - lastDragEnd > 1000) {
                            val c = current
                            animateNext = abs(c.value - c.defaultValue) >= 8 * (if (c.step > 0) c.step else 1.0)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            reset()
                        } else {
                            nudge(at)
                        }
                    },
                )
            }
            .padding(horizontal = edge.dp),
    ) {
        // the reference's row: 52 dp, name and value on a line 2 dp down, the bar (a 23.8 dp seek bar) centred 36.2 dp down
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp).height(19.5.dp).alpha(if (drag?.id == control.id) 0f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = if (labelSize == 15.sp) labelStyle else labelStyle.copy(fontSize = labelSize, lineHeight = labelSize * 1.3f), maxLines = 1, modifier = Modifier.weight(1f))
            Text(control.format(control.value), style = (if (labelSize == 15.sp) labelStyle else labelStyle.copy(fontSize = labelSize, lineHeight = labelSize * 1.3f)).copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
        val origin = when (control.id) {
            in fillFromStart -> 0f
            // the mask overlay's opacity: no bar at all
            "mask.overlayOpacity" -> scale.position(control.value)
            else -> scale.position(control.defaultValue)
        }
        val stops = remember(control.id, control.track) { trackStops(control) }
        // to accessibility services the bar is a seek bar named by its row: its value, range and step
        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 1.dp)
                .height(23.8.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = control.format(control.value)
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        control.value.toFloat().coerceIn(control.min.toFloat(), control.max.toFloat()),
                        control.min.toFloat()..control.max.toFloat(),
                        if (control.step > 0) (steps.toInt() - 1).coerceAtLeast(0) else 0,
                    )
                    if (enabled) {
                        setProgress { v ->
                            val c = current
                            val snapped = if (c.step > 0) c.min + ((v - c.min) / c.step).roundToLong() * c.step else v.toDouble()
                            commit(snapped.coerceIn(c.min, c.max))
                            true
                        }
                    } else {
                        disabled()
                    }
                },
        ) {
            bar(stops, shown.value, origin, ringStroke.toPx())
        }
    }
}

/** The track with its gap, the bar from [origin] to the thumb, and the thumb at [p] (0..1). */
private fun DrawScope.bar(stops: List<Pair<Float, Color>>, p: Float, origin: Float, ringStroke: Float) {
    val outer = RING_OUTER.dp.toPx()
    val gap = GAP.dp.toPx()
    val travel = (size.width - 2 * outer).coerceAtLeast(1f)
    val cx = outer + p * travel
    val cy = size.height / 2
    val thick = 1.6.dp.toPx()
    val left = 0f
    val right = size.width
    val brush = Brush.horizontalGradient(*stops.toTypedArray(), startX = left, endX = right)
    // a 1 dp dark edge either side of the track keeps it readable over the photo while the panel clears
    val edge = 1.dp.toPx()
    if (cx - gap > left) drawRect(Lc.sliderTrackEdge, Offset(left, cy - thick / 2 - edge), Size(cx - gap - left, thick + 2 * edge))
    if (cx + gap < right) drawRect(Lc.sliderTrackEdge, Offset(cx + gap, cy - thick / 2 - edge), Size(right - cx - gap, thick + 2 * edge))
    // the track: round at the row's ends, square at the gap
    if (cx - gap > left + thick) {
        drawLine(brush, Offset(left + thick / 2, cy), Offset(cx - gap - thick / 2, cy), thick, StrokeCap.Round)
        drawRect(brush, Offset(cx - gap - thick, cy - thick / 2), Size(thick, thick))
    }
    if (cx + gap < right - thick) {
        drawLine(brush, Offset(cx + gap + thick / 2, cy), Offset(right - thick / 2, cy), thick, StrokeCap.Round)
        drawRect(brush, Offset(cx + gap, cy - thick / 2), Size(thick, thick))
    }
    // the bar from the default (or the left end) to the thumb, stopping short of the gap
    val ox = outer + origin * travel
    if (abs(cx - ox) > gap) {
        val end = cx - gap * sign(cx - ox)
        drawRect(Lc.sliderFill, Offset(minOf(ox, end), cy - thick / 2), Size(abs(end - ox), thick))
    }
    // the thumb: a ring whose stroke thickens inward while it's dragged
    drawCircle(Lc.sliderThumb, outer - ringStroke / 2, Offset(cx, cy), style = Stroke(ringStroke))
}

private fun hex(s: String): Color = runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(Color.Gray)

private fun even(vararg colors: String): List<Pair<Float, Color>> = colors.mapIndexed { i, c -> i / (colors.size - 1f) to hex(c) }

/** The reference's colour mixer tracks, per band (red … magenta): hue, saturation, luminance, four even stops each. */
private val mixTracks = listOf(
    Triple(even("#A42AA4", "#A42981", "#A4332A", "#A4852A"), even("#444444", "#664546", "#6F4141", "#902526"), even("#1D0E0E", "#623333", "#724141", "#B49191")),
    Triple(even("#A42A29", "#A47C2A", "#9B8628", "#66A32B"), even("#444444", "#66455D", "#6D6242", "#907527"), even("#201C0F", "#625634", "#726541", "#B3A990")),
    Triple(even("#A2852B", "#6C9F2B", "#5FA32E", "#2AA348"), even("#444444", "#556546", "#576E42", "#5A8F27"), even("#161D0F", "#496132", "#597241", "#A3B491")),
    Triple(even("#65A32C", "#31A344", "#2AA352", "#2BA3A4"), even("#444444", "#66455D", "#426E4D", "#268F40"), even("#0F1F13", "#336240", "#41724E", "#93B69C")),
    Triple(even("#2AA349", "#2AA39B", "#2A99A4", "#2947A3"), even("#434444", "#456666", "#416D6D", "#278F8F"), even("#0F1E1E", "#336161", "#417272", "#90B4B4")),
    Triple(even("#2AA0A4", "#2951A3", "#3044A4", "#662AA3"), even("#444444", "#464E66", "#434C6E", "#254190"), even("#0E121D", "#323D60", "#414D71", "#8F99B3")),
    Triple(even("#2C46A3", "#5E2EA3", "#6C299F", "#A22985"), even("#434344", "#554566", "#58426D", "#5B2690"), even("#170F1F", "#493360", "#594271", "#A593B6")),
    Triple(even("#682AA2", "#9C2886", "#A42A7B", "#A42A29"), even("#444344", "#65455C", "#6E4263", "#8F2775"), even("#1F0F1B", "#623457", "#724266", "#B693AE")),
)

/** A track's colours as (place, colour) stops: the reference's gradients, or its plain grey ramp. */
private fun trackStops(c: DevelopControl): List<Pair<Float, Color>> {
    val plain = listOf(0f to Lc.sliderTrackFrom, 1f to Lc.sliderTrackTo)
    return when (val t = c.track) {
        SliderTrack.Temp -> even("#3637B3", "#B3B136")
        SliderTrack.Tint -> even("#33992E", "#992E99")
        is SliderTrack.Hue -> mixTracks[t.band.toInt().coerceIn(0, 7)].first
        is SliderTrack.Sat -> mixTracks[t.band.toInt().coerceIn(0, 7)].second
        is SliderTrack.Lum -> mixTracks[t.band.toInt().coerceIn(0, 7)].third
        // the whole hue circle at full colour
        SliderTrack.Rainbow -> even("#FF0000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF", "#FF0000")
        is SliderTrack.Gradient -> when (c.id) {
            "color.vibrance", "color.saturation" -> listOf(0f to hex("#494949"), 0.36f to hex("#939393"), 0.56f to hex("#469F49"), 0.77f to hex("#CFC91D"), 1f to hex("#B3282D"))
            "vignette.amount", "curve.refineSaturation" -> plain
            else -> listOf(0f to hex(t.from), 1f to hex(t.to))
        }
        SliderTrack.Plain, SliderTrack.Centered -> when (c.id) {
            "detail.sharpenAmount" -> listOf(0f to hex("#494949"), 0.68f to hex("#9D9D9E"), 0.95f to hex("#D8601C"), 1f to hex("#D8601C"))
            else -> plain
        }
    }
}
