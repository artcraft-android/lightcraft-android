package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.CropFrame
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch
import org.json.JSONObject

// ------------------------------------------------------------------------------------ crop math

/**
 * A point of the uncropped photo ([nx], [ny], 0–1) in the straightened frame the crop rectangle
 * lives in: turned by [angle] degrees about the middle, in pixels of a [w] × [h] photo (the engine's
 * `to_straight`).
 */
fun toStraight(nx: Double, ny: Double, angle: Double, w: Double, h: Double): Pair<Double, Double> {
    val a = Math.toRadians(angle)
    val dx = nx * w - w / 2
    val dy = ny * h - h / 2
    return (w / 2 + dx * cos(a) - dy * sin(a)) / w to (h / 2 + dx * sin(a) + dy * cos(a)) / h
}

/** The corners (top-left, top-right, bottom-right, bottom-left) of crop [rect] in the uncropped photo (the engine's `crop_quad_norm`). */
fun cropCorners(rect: DoubleArray, angle: Double, w: Double, h: Double): List<Pair<Double, Double>> {
    val a = Math.toRadians(-angle)
    return listOf(rect[0] to rect[1], rect[2] to rect[1], rect[2] to rect[3], rect[0] to rect[3]).map { (x, y) ->
        val dx = x * w - w / 2
        val dy = y * h - h / 2
        (w / 2 + dx * cos(a) - dy * sin(a)) / w to (h / 2 + dx * sin(a) + dy * cos(a)) / h
    }
}

/** What a drag holds: a corner (0–3: top-left clockwise), an edge (4–7: top, right, bottom, left) or the whole crop (8). */
const val CROP_MOVE = 8

/**
 * Crop rectangle [start] (`x0, y0, x1, y1` in the straightened frame) after dragging [handle] by
 * [dx], [dy] (straightened, 0–1). With an aspect locked ([aspectW] × [aspectH]) a corner keeps it.
 * Moving keeps the crop inside the photo; null when it would be too small (under 2 %).
 */
fun draggedCrop(start: DoubleArray, handle: Int, dx: Double, dy: Double, aspectW: Int, aspectH: Int, w: Double, h: Double): DoubleArray? {
    var (x0, y0, x1, y1) = start.toList()
    when (handle) {
        0 -> { x0 += dx; y0 += dy }
        1 -> { x1 += dx; y0 += dy }
        2 -> { x1 += dx; y1 += dy }
        3 -> { x0 += dx; y1 += dy }
        4 -> y0 += dy
        5 -> x1 += dx
        6 -> y1 += dy
        7 -> x0 += dx
        else -> {
            // the whole crop, kept inside
            val mx = dx.coerceIn(-start[0], 1.0 - start[2])
            val my = dy.coerceIn(-start[1], 1.0 - start[3])
            return doubleArrayOf(start[0] + mx, start[1] + my, start[2] + mx, start[3] + my)
        }
    }
    x0 = x0.coerceIn(0.0, 1.0)
    x1 = x1.coerceIn(0.0, 1.0)
    y0 = y0.coerceIn(0.0, 1.0)
    y1 = y1.coerceIn(0.0, 1.0)
    if (aspectW > 0 && aspectH > 0 && handle < 4 && w > 0 && h > 0) {
        // the locked shape in the photo's orientation, height from width (as the desktop)
        val a = if ((w >= h) == (aspectW >= aspectH)) aspectW.toDouble() / aspectH else aspectH.toDouble() / aspectW
        val hn = abs(x1 - x0) * w / a / h
        if (handle == 0 || handle == 1) y0 = y1 - hn else y1 = y0 + hn
        if (y0 < 0.0 || y1 > 1.0) return null
    }
    val r = doubleArrayOf(min(x0, x1), min(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    return if (r[2] - r[0] > 0.02 && r[3] - r[1] > 0.02) r else null
}

/** Where the [image]-sized photo sits in a [view] it is fitted to (centred). */
fun fittedRect(view: IntSize, image: IntSize): Rect {
    if (view.width <= 0 || view.height <= 0 || image.width <= 0 || image.height <= 0) return Rect.Zero
    val s = min(view.width / image.width.toFloat(), view.height / image.height.toFloat())
    val w = image.width * s
    val h = image.height * s
    val left = (view.width - w) / 2
    val top = (view.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

// ---------------------------------------------------------------------------------- the stage

/**
 * The crop's room on the photo's view, as the reference leaves it (dp): 38 at the sides, the top bar
 * over it, the dial and buttons under it; sideways a little more over and under it (60.6 and 99.2).
 */
private const val SIDE = 38f
private const val TOP = 56f
private const val FOOT = 94f
private const val TOP_SIDEWAYS = 60.6f
private const val FOOT_SIDEWAYS = 99.2f

/** How the straightened photo shows: its crop fitted into the room, [scale] screen px a photo px, the crop's middle at [centre]. */
private data class CropView(val scale: Float, val centre: Offset, val cropCentre: Offset)

/** The crop fitted into [room] for a [w] × [h] photo. */
private fun cropView(rect: DoubleArray, w: Double, h: Double, room: Rect): CropView {
    val cw = ((rect[2] - rect[0]) * w).toFloat().coerceAtLeast(1f)
    val ch = ((rect[3] - rect[1]) * h).toFloat().coerceAtLeast(1f)
    val s = min(room.width / cw, room.height / ch)
    return CropView(s, room.center, Offset(((rect[0] + rect[2]) / 2 * w).toFloat(), ((rect[1] + rect[3]) / 2 * h).toFloat()))
}

/**
 * Crop, as the reference's: the crop stays level and as large as the room allows, and the photo
 * (all of it, the part outside the crop dimmed) turns and moves under it. Drag a corner or an edge
 * (30 dp reach) to resize, inside to move the photo under the crop, outside it to turn the photo;
 * the straighten dial under the crop turns it too. Thirds show while a finger is down, a finer grid
 * while turning. A double-tap sets the crop back. Each drag is one step; the view fits the new crop
 * once the finger lifts. The aspect's name is in the top bar; its four round buttons sit over the
 * photo's foot: straighten automatically, lock the aspect, turn a quarter, more.
 */
@Composable
fun CropStage(vm: AppViewModel, ed: EditorState, frame: CropFrame, image: ImageBitmap, modifier: Modifier) {
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    val top by rememberUpdatedState(if (sideways) TOP_SIDEWAYS else TOP)
    val foot by rememberUpdatedState(if (sideways) FOOT_SIDEWAYS else FOOT)
    val w = frame.width
    val h = frame.height
    // what a finger is doing to the crop before the engine has it back
    var liveRect by remember(frame) { mutableStateOf<DoubleArray?>(null) }
    var liveAngle by remember(frame) { mutableStateOf<Double?>(null) }
    // the gesture below is started once per photo, so it writes through these: a new frame makes new live
    // state, and a gesture holding the first frame's would draw nothing after the first drag (issue 8)
    val setLiveRect by rememberUpdatedState<(DoubleArray?) -> Unit> { liveRect = it }
    val setLiveAngle by rememberUpdatedState<(Double?) -> Unit> { liveAngle = it }
    // the view held still while a handle resizes the crop (it fits again on lifting)
    var held by remember { mutableStateOf<CropView?>(null) }
    var touching by remember { mutableStateOf(false) }
    var turning by remember { mutableStateOf(false) }
    val rect = liveRect ?: frame.rect.toDoubleArray()
    val angle = liveAngle ?: frame.angle
    val current by rememberUpdatedState(frame)
    val text = rememberTextMeasurer()
    val fit = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf<CropView?>(null) }
    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                // keyed on the photo, not the frame: turning sends the frame back on every move, and a
                // restarted gesture would drop the finger after the first one ([current] is read instead)
                .pointerInput(ed.photoId) {
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val f = current
                        val start = f.rect.toDoubleArray()
                        val room = Rect(SIDE.dp.toPx(), top.dp.toPx(), size.width - SIDE.dp.toPx(), size.height - foot.dp.toPx())
                        val v = cropView(start, f.width, f.height, room)
                        val half = Size(((start[2] - start[0]) * f.width).toFloat() * v.scale / 2, ((start[3] - start[1]) * f.height).toFloat() * v.scale / 2)
                        val box = Rect(v.centre.x - half.width, v.centre.y - half.height, v.centre.x + half.width, v.centre.y + half.height)
                        val handles = listOf(box.topLeft, box.topRight, box.bottomRight, box.bottomLeft, box.topCenter, box.centerRight, box.bottomCenter, box.centerLeft)
                        val reach = 30.dp.toPx()
                        val near = handles.withIndex().minByOrNull { (_, p) -> (p - down.position).getDistance() }?.takeIf { (it.value - down.position).getDistance() < reach }?.index
                        val handle = when {
                            near != null -> near
                            box.contains(down.position) -> CROP_MOVE
                            else -> -1
                        }
                        touching = true
                        var moved = false
                        val slop = viewConfiguration.touchSlop
                        val a0 = atan2(down.position.y - v.centre.y, down.position.x - v.centre.x)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val d = change.position - down.position
                            if (!moved) {
                                if (d.getDistance() < slop) continue
                                moved = true
                                if (handle >= 0) {
                                    if (handle != CROP_MOVE) held = v
                                    vm.beginCropDrag()
                                } else {
                                    turning = true
                                }
                            }
                            change.consume()
                            if (handle >= 0) {
                                // screen px to the straightened photo's 0–1; moving the photo moves the crop the other way
                                val sx = d.x / v.scale / f.width
                                val sy = d.y / v.scale / f.height
                                val next = if (handle == CROP_MOVE) {
                                    draggedCrop(start, CROP_MOVE, -sx, -sy, 0, 0, f.width, f.height)
                                } else {
                                    draggedCrop(start, handle, sx, sy, f.aspectW.toInt(), f.aspectH.toInt(), f.width, f.height)
                                }
                                next?.let {
                                    setLiveRect(it)
                                    vm.dragCrop(it)
                                }
                            } else {
                                val a1 = atan2(change.position.y - v.centre.y, change.position.x - v.centre.x)
                                val turn = Math.toDegrees((a1 - a0).toDouble())
                                val value = (f.angle + turn).coerceIn(-45.0, 45.0)
                                setLiveAngle(value)
                                ed.control("crop.angle")?.let { vm.onControlChange(it, value) }
                            }
                        }
                        touching = false
                        if (moved) {
                            if (handle >= 0) vm.endCropDrag() else vm.onControlChangeFinished()
                            turning = false
                            // ease from the held view to the new fit
                            if (held != null) {
                                from = held
                                held = null
                                scope.launch {
                                    fit.snapTo(0f)
                                    fit.animateTo(1f, tween(350, easing = AccelerateDecelerate))
                                    from = null
                                }
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastTap < 300) vm.cropCommand("crop.reset") else lastTap = now
                        }
                    }
                },
        ) {
            val room = Rect(SIDE.dp.toPx(), top.dp.toPx(), size.width - SIDE.dp.toPx(), size.height - foot.dp.toPx())
            if (room.width <= 0f || room.height <= 0f) return@Canvas
            val target = held ?: cropView(rect, w, h, room)
            val v = from?.let { a ->
                val t = fit.value
                CropView(a.scale + (target.scale - a.scale) * t, a.centre + (target.centre - a.centre) * t, a.cropCentre + (target.cropCentre - a.cropCentre) * t)
            } ?: target
            // the photo: straightened about its middle, its crop's middle on the room's
            val k = (w / image.width).toFloat()
            withTransform({
                translate(v.centre.x, v.centre.y)
                scale(v.scale, v.scale, Offset.Zero)
                translate((w / 2).toFloat() - v.cropCentre.x, (h / 2).toFloat() - v.cropCentre.y)
                rotate(angle.toFloat(), Offset.Zero)
                translate(-(w / 2).toFloat(), -(h / 2).toFloat())
                scale(k, k, Offset.Zero)
            }) {
                drawImage(image, filterQuality = FilterQuality.High)
            }
            // the crop on screen (while a handle resizes it, in the held view)
            val cw = ((rect[2] - rect[0]) * w).toFloat() * v.scale
            val ch = ((rect[3] - rect[1]) * h).toFloat() * v.scale
            val mid = v.centre + (Offset(((rect[0] + rect[2]) / 2 * w).toFloat(), ((rect[1] + rect[3]) / 2 * h).toFloat()) - v.cropCentre) * v.scale
            val box = Rect(mid.x - cw / 2, mid.y - ch / 2, mid.x + cw / 2, mid.y + ch / 2)
            // everything outside it at half strength
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRect(box)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.5f))
            val line = Color(0xFFEFEFEF)
            drawRect(line.copy(alpha = 0.65f), box.topLeft, box.size, style = Stroke(1.dp.toPx()))
            // thirds while a finger is down, a finer grid while turning
            if (touching || turning) {
                val n = if (turning) 9 else 3
                val grid = line.copy(alpha = if (turning) 0.35f else 0.5f)
                for (i in 1 until n) {
                    val fx = box.left + box.width * i / n
                    val fy = box.top + box.height * i / n
                    drawLine(grid, Offset(fx, box.top), Offset(fx, box.bottom), 1.dp.toPx())
                    drawLine(grid, Offset(box.left, fy), Offset(box.right, fy), 1.dp.toPx())
                }
            }
            // the handles: 18 x 3 dp, just outside the line
            val len = 18.dp.toPx()
            val t = 3.dp.toPx()
            val o = t / 2 + 0.5.dp.toPx()
            fun bar(a: Offset, b: Offset) = drawLine(line, a, b, t)
            bar(Offset(box.left - o, box.top - o - t / 2), Offset(box.left - o, box.top + len))
            bar(Offset(box.left - o - t / 2, box.top - o), Offset(box.left + len, box.top - o))
            bar(Offset(box.right + o, box.top - o - t / 2), Offset(box.right + o, box.top + len))
            bar(Offset(box.right + o + t / 2, box.top - o), Offset(box.right - len, box.top - o))
            bar(Offset(box.right + o, box.bottom + o + t / 2), Offset(box.right + o, box.bottom - len))
            bar(Offset(box.right + o + t / 2, box.bottom + o), Offset(box.right - len, box.bottom + o))
            bar(Offset(box.left - o, box.bottom + o + t / 2), Offset(box.left - o, box.bottom - len))
            bar(Offset(box.left - o - t / 2, box.bottom + o), Offset(box.left + len, box.bottom + o))
            bar(Offset(box.center.x - len / 2, box.top - o), Offset(box.center.x + len / 2, box.top - o))
            bar(Offset(box.center.x - len / 2, box.bottom + o), Offset(box.center.x + len / 2, box.bottom + o))
            bar(Offset(box.left - o, box.center.y - len / 2), Offset(box.left - o, box.center.y + len / 2))
            bar(Offset(box.right + o, box.center.y - len / 2), Offset(box.right + o, box.center.y + len / 2))
            // the straighten dial under the crop: marks round an arc that turns with the photo, the angle over its
            // middle; hidden while a handle resizes the crop, as the reference's is
            if (held == null) {
                val dialTop = box.bottom + 1.dp.toPx()
                val radius = 190.dp.toPx()
                val c = Offset(box.center.x, dialTop + 29.dp.toPx() - radius)
                val span = 32f
                var mark = -span
                while (mark <= span) {
                    val value = mark + angle.toFloat()
                    // a mark every 2 degrees of angle, a longer one every 10
                    val tick = Math.round(value / 2f) * 2f
                    val at = Math.toRadians((90f + tick - angle.toFloat()).toDouble())
                    val p = c + Offset(cos(at).toFloat(), sin(at).toFloat()) * radius
                    val big = Math.round(tick) % 10 == 0
                    if (big) {
                        val q = c + Offset(cos(at).toFloat(), sin(at).toFloat()) * (radius - 6.dp.toPx())
                        drawLine(Color.White, q, p, 1.5.dp.toPx())
                    } else {
                        drawCircle(Color.White, 1.2.dp.toPx(), p)
                    }
                    mark += 2f
                }
                drawLine(Color.White, Offset(c.x, dialTop + 33.dp.toPx()), Offset(c.x, dialTop + 43.dp.toPx()), 2.dp.toPx())
                val label = dialLabel(angle)
                val measured = text.measure(label, TextStyle(color = Color.White, fontFamily = Inter, fontSize = 12.sp))
                drawText(measured, topLeft = Offset(c.x - measured.size.width / 2f, dialTop + 4.dp.toPx()))
            }
        }
        CropButtons(vm, ed, frame)
    }
}

private operator fun Offset.times(f: Float) = Offset(x * f, y * f)

/** The dial's angle as the reference writes it: two decimals and a degree sign; never "-0.00°". */
internal fun dialLabel(angle: Double): String = String.format(Locale.US, "%.2f°", if (abs(angle) < 0.005) 0.0 else angle)

/**
 * The crop's four round buttons over the photo's foot (40 dp, 16 dp in, 8 dp apart), as the
 * reference's: the crop turned the other way round, its aspect locked or free; a quarter turn, and
 * More, whose choices stand over it as pills (Straighten, Flip V, Flip H) while it shows white.
 */
@Composable
private fun BoxScope.CropButtons(vm: AppViewModel, ed: EditorState, frame: CropFrame) {
    val locked = frame.aspectW > 0u && frame.aspectH > 0u
    var more by remember { mutableStateOf(false) }
    // Back closes the pills before it leaves Crop
    BackHandler(enabled = more) { more = false }
    if (more) {
        // a tap anywhere else closes the pills
        Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { more = false } })
    }
    // clear of the camera's cut-out sideways, as the top bar's Back is (the reference's)
    Row(
        Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start))
            .padding(start = 16.dp, bottom = 16.5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(LcIcon.SwapAspect, "Swap aspect") { vm.cropCommand("crop.rotateAspect") }
        RoundButton(if (locked) LcIcon.Lock else LcIcon.Unlock, if (locked) "Aspect locked" else "Aspect unlocked") {
            // lock the crop's present shape, where it is (the reference's): "original" would refit it
            vm.setCropAspect(if (locked) "free" else "current")
        }
    }
    Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.5.dp), horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            more,
            enter = fadeIn(tween(150)) + expandVertically(tween(200), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Bottom),
        ) {
            Column(Modifier.padding(bottom = 24.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CropPill(LcIcon.FixAngle, "Straighten") {
                    more = false
                    vm.cropCommand("crop.autoStraighten")
                }
                CropPill(LcIcon.FlipV, "Flip V") {
                    more = false
                    vm.cropCommand("photo.flipVertical", photo = true)
                }
                CropPill(LcIcon.FlipH, "Flip H") {
                    more = false
                    vm.cropCommand("photo.flipHorizontal", photo = true)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(MdIcon.RotateRight, "Rotate", iconSize = 26.dp) { vm.cropCommand("photo.rotateRight", photo = true) }
            RoundButton(if (more) MdIcon.MoreVert else MdIcon.MoreHoriz, "More crop options", iconSize = 29.dp, on = more) { more = !more }
        }
    }
}

/** A 40 dp round button over the photo: dark grey, a 1 dp lighter edge, a white icon; [on] (its pills out): white, the icon dark. */
@Composable
private fun RoundButton(icon: Glyph, description: String, iconSize: Dp = 22.dp, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (on) Lc.panelText else Lc.cropButton)
            .border(1.dp, Lc.panelButtonBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(icon, null, tint = if (on) Lc.cropButton else Color.White, size = iconSize)
    }
}

/** One of the crop's More pills, as the reference's: 42 dp and round, its grey with a 1.5 dp light edge; an icon and its name (15 sp). */
@Composable
private fun CropPill(icon: LcIcon, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(42.dp)
            .clip(CircleShape)
            .background(Lc.wbList)
            .border(1.5.dp, pillEdge, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LcIcon(icon, null, tint = Lc.panelTextSoft, size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, maxLines = 1)
    }
}

private val pillEdge = Color(0xFF8E8E8E)

// ------------------------------------------------------------------------------------ the sheet

/** Crop's ratios, as the reference's list has them: wide first, the square last (a portrait photo's turned upright). */
private val ratios = listOf(16.0 to 10.0, 16.0 to 9.0, 11.0 to 8.5, 7.0 to 5.0, 5.0 to 4.0, 4.0 to 3.0, 3.0 to 2.0, 2.0 to 1.0, 1.0 to 1.0)

private fun num(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

/** A ratio's id as the engine takes it: its wide form (`16x10`), which the engine turns to the photo's way round. */
private fun ratioId(r: Pair<Double, Double>) = "${num(r.first)}x${num(r.second)}"

/** A ratio's name, as the reference writes it, the photo's way round: `16 × 10`, upright `10 × 16`. */
private fun ratioName(r: Pair<Double, Double>, upright: Boolean) = if (upright) "${num(r.second)} × ${num(r.first)}" else "${num(r.first)} × ${num(r.second)}"

/** A social format of Crop's Aspect: its id, its name in its group's list, the pill's and its shape (its own way round). */
data class CropFormat(val id: String, val name: String, val pill: String, val w: Double, val h: Double)

/** Instagram's formats, as the reference lists them: a square, a story, a portrait, a landscape and the profile photo. */
val instagramFormats = listOf(
    CropFormat("ig.square", "Square", "Instagram square", 1.0, 1.0),
    CropFormat("ig.story", "Story", "Instagram story", 9.0, 16.0),
    CropFormat("ig.portrait", "Portrait", "Instagram portrait", 4.0, 5.0),
    CropFormat("ig.landscape", "Landscape", "Instagram landscape", 16.0, 9.0),
    CropFormat("ig.profile", "Profile photo", "Instagram profile photo", 1.0, 1.0),
)

/** TikTok's one format: an upright 9:16 video. */
val tiktokFormats = listOf(CropFormat("tiktok.video", "Video", "TikTok video", 9.0, 16.0))

/** The social format [picked] last, while [frame]'s lock still has its shape the same way round. */
fun cropFormat(frame: CropFrame, picked: String?): CropFormat? {
    val f = (instagramFormats + tiktokFormats).firstOrNull { it.id == picked } ?: return null
    val (w, h) = frame.aspectW.toDouble() to frame.aspectH.toDouble()
    if (w <= 0 || h <= 0) return null
    return f.takeIf { abs(w / h - it.w / it.h) < 0.005 }
}

/** Which aspect choice [frame]'s lock is (either way round), if any: `free`, `original`, a ratio's id or `custom`. */
fun aspectChoice(frame: CropFrame): String {
    val (w, h) = frame.aspectW.toDouble() to frame.aspectH.toDouble()
    if (w <= 0 || h <= 0) return "free"
    val r = w / h
    val photo = frame.width / frame.height
    if (abs(r - photo) < 0.005 || abs(1 / r - photo) < 0.005) return "original"
    return ratios.firstOrNull { (a, b) -> abs(r - a / b) < 0.005 || abs(r - b / a) < 0.005 }?.let { ratioId(it) } ?: "custom"
}

/** The crop is the whole photo, level: free of any lock it still has the photo's own shape. */
private fun CropFrame.whole(): Boolean =
    abs(angle) < 0.005 && rect.size == 4 && rect[0] < 0.0005 && rect[1] < 0.0005 && rect[2] > 0.9995 && rect[3] > 0.9995

/** The aspect the crop shows as, the reference's way: an untouched crop is the photo's Original, a free one changed is Custom; a social format picked, its id. */
fun shownAspect(frame: CropFrame, format: String? = null): String = cropFormat(frame, format)?.id ?: when (val id = aspectChoice(frame)) {
    "free" -> if (frame.whole()) "original" else "custom"
    else -> id
}

/** The name of the crop's aspect, for the top bar's pill. */
fun aspectName(frame: CropFrame, format: String? = null): String = cropFormat(frame, format)?.pill ?: when (val id = shownAspect(frame)) {
    "original" -> "Original"
    else -> ratios.firstOrNull { ratioId(it) == id }?.let { ratioName(it, frame.height > frame.width) } ?: "Custom"
}

/** Crop's panel: Aspect (the crop's shape) and Geometry (Upright, constrain and the manual transforms), tabs at 16 sp (the reference's 17 in its narrower face). */
@Composable
fun CropSheet(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    Column(modifier) {
        Tabs(listOf("aspect" to "Aspect", "geometry" to "Geometry"), ed.cropTab, bottomPadding = 0.dp, textSize = 16f, tabPad = 38.dp, linePad = 22.dp) { vm.setCropTab(it) }
        when (ed.cropTab) {
            "geometry" -> CropGeometry(vm, ed)
            else -> CropAspect(vm, ed)
        }
    }
}

/**
 * A tile of Crop's Aspect row, as the reference's: an icon over its name (11 sp); the crop's own
 * shape is a dark grey box with light content, the others plain.
 */
@Composable
private fun CropTile(label: String, selected: Boolean? = null, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val on = selected == true
    val color = if (on) Lc.panelText else Lc.panelTextSoft
    val press = if (selected != null) Modifier.selectable(on, role = Role.RadioButton, onClick = onClick) else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .height(69.6.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) Lc.sectionChosen else Color.Transparent)
            .then(press)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(color) }
        Spacer(Modifier.height(7.dp))
        Text(label, color = color, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
}

/** A ratio's icon, as the reference's: a dark rectangle of its shape (its long side 23.3 dp) with a light edge, in a 28 dp square. */
@Composable
private fun RatioShape(w: Double, h: Double) {
    Canvas(Modifier.size(28.dp)) {
        val long = 23.3.dp.toPx()
        val (rw, rh) = if (w >= h) long to (long * h / w).toFloat() else (long * w / h).toFloat() to long
        val edge = 1.25.dp.toPx()
        val at = Offset((size.width - rw) / 2, (size.height - rh) / 2)
        drawRect(ratioFill, at, Size(rw, rh))
        drawRect(Lc.panelTextSoft, at + Offset(edge / 2, edge / 2), Size(rw - edge, rh - edge), style = Stroke(edge))
    }
}

private val ratioFill = Color(0xFF2E2E2E)

/**
 * Aspect, as the reference's: Original, then (set apart) Ratios, Instagram and TikTok, each one's
 * list opening over it. The reference's generative expand is left out; the social formats have
 * LightCraft's own icons, not the brands'.
 */
@Composable
private fun CropAspect(vm: AppViewModel, ed: EditorState) {
    val frame = ed.crop
    val current = frame?.let { shownAspect(it, ed.cropFormat) }
    val upright = frame != null && frame.height > frame.width
    var open by remember { mutableStateOf<String?>(null) }
    val tiles: @Composable () -> Unit = {
        CropTile("Original", selected = current == "original", onClick = { vm.setCropAspect("original", level = true) }) { LcIcon(LcIcon.Original, null, tint = it, size = 22.dp) }
        Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 40.dp).background(Lc.toolDivider))
        Box {
            CropTile("Ratios", selected = open == "ratios" || ratios.any { ratioId(it) == current }, onClick = { open = "ratios" }) { LcIcon(LcIcon.Ratios, null, tint = it, size = 26.dp) }
            if (open == "ratios") {
                val items = ratios.map { r -> AspectItem(ratioId(r), ratioName(r, upright), if (upright) r.second else r.first, if (upright) r.first else r.second) }
                AspectList(items, current, onDismiss = { open = null }) { id ->
                    open = null
                    vm.setCropAspect(id)
                }
            }
        }
        for ((group, formats, icon) in listOf(Triple("Instagram", instagramFormats, MdIcon.PhoneIphone), Triple("TikTok", tiktokFormats, MdIcon.MusicNote))) {
            Box {
                CropTile(group, selected = open == group || formats.any { it.id == current }, onClick = { open = group }) { MdIcon(icon, null, tint = it, size = 24.dp) }
                if (open == group) {
                    AspectList(formats.map { AspectItem(it.id, it.name, it.w, it.h) }, current, onDismiss = { open = null }) { id ->
                        open = null
                        formats.firstOrNull { it.id == id }?.let(vm::setCropFormat)
                    }
                }
            }
        }
    }
    if (LocalSheetLimit.current == null) {
        // sideways: the panel is tall and narrow, so the tiles fill it in rows
        FlowRow(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { tiles() }
        return
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { tiles() }
}

/** A row of an aspect list: its id, its name and its shape. */
private class AspectItem(val id: String, val name: String, val w: Double, val h: Double)

/**
 * An aspect tile's list (Ratios, a social group's), as the reference's: centred over its tile, 8 dp
 * above it, its grey with a 1 dp edge; 44 dp rows, each the shape and its name, a tick on the crop's own.
 */
@Composable
private fun AspectList(items: List<AspectItem>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(popupPositionProvider = remember(gap) { AboveAnchor(gap) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(200.dp)
                .shadow(8.dp)
                .background(Lc.wbList)
                .border(1.dp, Lc.panelButtonBorder)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            items.forEach { r ->
                val on = r.id == current
                Row(
                    Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { onPick(r.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RatioShape(r.w, r.h)
                    Text(r.name, color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
                    if (on) MdIcon(MdIcon.Check, null, Modifier.padding(end = 8.dp), tint = Color.White, size = 20.dp)
                }
            }
        }
    }
}

/**
 * A popup centred over its anchor, [gap] above it; under it when there's no room above; and when
 * there's room for neither (sideways, as the reference's), [gap] to its left, as high as it fits.
 * Kept on the screen.
 */
private class AboveAnchor(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val above = anchorBounds.top - gap - popupContentSize.height
        val below = anchorBounds.bottom + gap
        val (x, y) = when {
            above >= 0 -> anchorBounds.center.x - popupContentSize.width / 2 to above
            below + popupContentSize.height <= windowSize.height -> anchorBounds.center.x - popupContentSize.width / 2 to below
            else -> anchorBounds.left - gap - popupContentSize.width to anchorBounds.center.y - popupContentSize.height / 2
        }
        return IntOffset(
            x.coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
            y.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
        )
    }
}

/**
 * Upright's 50 dp tiles, as the reference's: black with a 1 dp grey edge, blue-edged while on; the
 * name under each (11 sp). A longer name widens the tile, so neighbours' names never run together.
 */
@Composable
private fun SquareTile(icon: LcIcon, label: String, selected: Boolean? = null, onClick: () -> Unit) {
    val on = selected == true
    // a choice (Upright's) says whether it's on; the others are buttons
    val press = if (selected != null) Modifier.selectable(on, role = Role.RadioButton, onClick = onClick) else Modifier.clickable(role = Role.Button, onClick = onClick)
    Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) Color(0xFF1A1A1A) else Color.Black)
                .border(1.dp, if (on) Lc.primaryBlue else Lc.panelButtonBorder, RoundedCornerShape(6.dp))
                .then(press)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            LcIcon(icon, null, tint = Lc.panelTextSoft, size = 24.dp)
        }
        // the button already says its name
        Text(label, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 11.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {})
    }
}

/**
 * Geometry, as the reference's: Upright's tiles (tap the one on again to turn it off), Constrain
 * Crop, then Manual Transforms (distortion first). Straightening is the dial's, turning and
 * flipping the round buttons'.
 */
@Composable
private fun CropGeometry(vm: AppViewModel, ed: EditorState) {
    val upright = ed.state?.upright
    LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp)) {
        item(key = "upright") {
            // the reference's five: auto, level, vertical and full 7.8 dp apart, guided set apart after them
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.8.dp, Alignment.CenterHorizontally)) {
                listOf(Triple("auto", "Auto", LcIcon.SectionAuto), Triple("level", "Level", LcIcon.Level), Triple("vertical", "Vertical", LcIcon.Vertical), Triple("full", "Full", LcIcon.FullUpright))
                    .forEach { (id, label, icon) ->
                        SquareTile(icon, label, selected = upright == id) { vm.cropCommand("geometry.upright", JSONObject().put("mode", if (upright == id) "off" else id)) }
                    }
                // 24.3 dp from Full: the row's 7.8 dp each side of this
                Spacer(Modifier.width(8.7.dp))
                SquareTile(LcIcon.Guided, "Guided", selected = upright == "guided") { vm.setGuided(upright != "guided") }
            }
        }
        if (upright == "guided") {
            item(key = "guided") {
                Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Draw up to four guides on the photo along lines that should be straight up or level.",
                        color = Lc.panelTextSoft,
                        fontFamily = Inter,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (ed.guides.isNotEmpty()) PanelButton("Clear", LcIcon.Trash) { vm.clearGuides() }
                }
            }
        }
        item(key = "constrain") { SwitchRow("Constrain Crop", ed.state?.constrainCrop == true, inset = 32.dp, textSize = 15f) { vm.setConstrainCrop(it) } }
        item(key = "heading") {
            Text("Manual Transforms", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.5.sp, modifier = Modifier.padding(start = 32.dp, top = 6.dp, bottom = 14.dp))
        }
        items(listOf("optics.distortion", "geometry.vertical", "geometry.horizontal", "geometry.rotate", "geometry.aspect", "geometry.scale", "geometry.offsetX", "geometry.offsetY"), key = { it }) { id ->
            ed.control(id)?.let { c ->
                DevelopSlider(
                    c,
                    onChange = { vm.onControlChange(c, it) },
                    onFinished = vm::onControlChangeFinished,
                    onReset = { vm.resetControl(c) },
                    label = if (id == "optics.distortion") "Distortion" else c.label,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Guided Upright's guides: amber lines with a dot at each end, the one being drawn too. */
private val guideColor = Color(0xFFFFC428)

/**
 * Guided Upright, over the photo as the crop tool shows it (no crop or straightening): the guides,
 * and a finger drawn across the photo to add one (the fifth drops the oldest).
 */
@Composable
fun GuidesOverlay(vm: AppViewModel, ed: EditorState, imageSize: IntSize, modifier: Modifier) {
    var drawing by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Canvas(
        modifier.pointerInput(ed.photoId, imageSize) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var last = down.position
                drawing = down.position to last
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    last = c.position
                    drawing = down.position to last
                    c.consume()
                }
                drawing = null
                val r = fittedRect(size, imageSize)
                fun norm(o: Offset) = ((o.x - r.left) / r.width).toDouble() to ((o.y - r.top) / r.height).toDouble()
                if ((last - down.position).getDistance() > 24.dp.toPx()) vm.addGuide(norm(down.position), norm(last))
            }
        },
    ) {
        val r = fittedRect(IntSize(size.width.toInt(), size.height.toInt()), imageSize)
        fun at(x: Double, y: Double) = Offset(r.left + (x * r.width).toFloat(), r.top + (y * r.height).toFloat())
        fun guide(a: Offset, b: Offset) {
            drawLine(Color.Black.copy(alpha = 0.47f), a, b, 3.dp.toPx())
            drawLine(guideColor, a, b, 1.5.dp.toPx())
            for (q in listOf(a, b)) {
                drawCircle(guideColor, 4.dp.toPx(), q)
                drawCircle(Color.Black, 4.dp.toPx(), q, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
        ed.guides.chunked(4).filter { it.size == 4 }.forEach { g -> guide(at(g[0], g[1]), at(g[2], g[3])) }
        drawing?.let { (a, b) -> guide(a, b) }
    }
}
