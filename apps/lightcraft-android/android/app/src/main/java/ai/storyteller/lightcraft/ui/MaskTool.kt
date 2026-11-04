package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.MaskInfo
import ai.storyteller.lightcraft.core.MaskPart
import ai.storyteller.lightcraft.core.SliderTrack
import ai.storyteller.lightcraft.core.ViewGeometry
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.json.JSONObject

// ------------------------------------------------------------------------------------- sliders

/** A local adjustment slider (the desktop's `masking.rs` → `LOCAL`), keyed by its `mask.adjust` name. */
class LocalSpec(val key: String, val label: String, val min: Double, val max: Double, val step: Double, val decimals: Int, val track: SliderTrack)

private val localSpecs = listOf(
    LocalSpec("temp", "Temp", -100.0, 100.0, 1.0, 0, SliderTrack.Temp),
    LocalSpec("tint", "Tint", -100.0, 100.0, 1.0, 0, SliderTrack.Tint),
    LocalSpec("exposure", "Exposure", -4.0, 4.0, 0.01, 2, SliderTrack.Centered),
    LocalSpec("contrast", "Contrast", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("highlights", "Highlights", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("shadows", "Shadows", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("whites", "Whites", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("blacks", "Blacks", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("texture", "Texture", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("clarity", "Clarity", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("dehaze", "Dehaze", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("hue", "Hue", -100.0, 100.0, 1.0, 0, SliderTrack.Rainbow),
    LocalSpec("saturation", "Saturation", -100.0, 100.0, 1.0, 0, SliderTrack.Gradient("#7a7a7a", "#e04a3a")),
    LocalSpec("sharpness", "Sharpness", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("noise", "Noise", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("moire", "Moiré", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
    LocalSpec("defringe", "Defringe", -100.0, 100.0, 1.0, 0, SliderTrack.Centered),
).associateBy { it.key }

/** The reference app's sections in Masking (its local adjustments), with their sliders. */
enum class MaskSection(val label: String, val icon: Glyph, val keys: List<String>) {
    LIGHT("Light", MdIcon.LightMode, listOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks")),
    COLOR("Color", MdIcon.Thermostat, listOf("temp", "tint", "saturation", "hue")),
    EFFECTS("Effects", MdIcon.Vignette, listOf("texture", "clarity", "dehaze")),
    // as the reference's: Detail is noise and sharpness, Optics moiré and defringe
    DETAIL("Detail", LcIcon.SectionDetail, listOf("noise", "sharpness")),
    OPTICS("Optics", LcIcon.SectionOptics, listOf("moire", "defringe")),
}

/** The kinds of mask the phone makes (the AI ones need a model the phone doesn't have). */
val maskKinds = listOf(
    Triple("brush", "Brush", LcIcon.Pencil),
    Triple("linear", "Linear Gradient", LcIcon.Segments),
    Triple("radial", "Radial Gradient", LcIcon.Radial),
    Triple("colorRange", "Color Range", LcIcon.Picker),
    Triple("luminanceRange", "Luminance Range", LcIcon.Sliders),
)

fun maskIcon(kind: String): LcIcon = maskKinds.firstOrNull { it.first == kind }?.third ?: LcIcon.Effects

/**
 * A mask kind's picture for the new mask sheet: a brush; a square fading from top to bottom; a
 * disc fading out from its middle; a dropper over a colour wheel; a dropper over a grey ramp.
 */
@Composable
fun MaskKindIcon(kind: String, modifier: Modifier, mono: Boolean = false) {
    when (kind) {
        "linear" -> Canvas(modifier) {
            val s = size.minDimension * 0.78f
            val tl = Offset((size.width - s) / 2, (size.height - s) / 2)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Color(0xFFB8B8B8)), tl.y, tl.y + s), tl, Size(s, s))
            drawRect(Color(0xFFB8B8B8), tl, Size(s, s), style = Stroke(1.5.dp.toPx()))
        }
        "radial" -> Canvas(modifier) {
            val r = size.minDimension * 0.42f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2A2A), Color(0xFF9A9A9A)), center, r), r, center)
            drawCircle(Color(0xFFB8B8B8), r, center, style = Stroke(1.5.dp.toPx()))
        }
        "colorRange" -> Box(modifier) {
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension * 0.34f
                val c = Offset(size.width * 0.4f, size.height * 0.6f)
                // in a part's square the wheel is greys, as the reference's
                val wheel = if (mono) {
                    listOf(Color(0xFFE0E0E0), Color(0xFF9A9A9A), Color(0xFF6A6A6A), Color(0xFFB8B8B8), Color(0xFF808080), Color(0xFFE0E0E0))
                } else {
                    listOf(Color(0xFFB04848), Color(0xFFB0A048), Color(0xFF48A060), Color(0xFF4878B0), Color(0xFF9A48B0), Color(0xFFB04848))
                }
                drawCircle(Brush.sweepGradient(wheel, c), r, c)
                if (mono) drawCircle(Color.White, r, c, style = Stroke(1.2.dp.toPx()))
            }
            MdIcon(MdIcon.Colorize, null, tint = if (mono) Color.White else Color(0xFFD0D0D0), size = 16.dp, modifier = Modifier.align(Alignment.TopEnd))
        }
        "luminanceRange" -> if (mono) {
            Box(modifier) {
                Canvas(Modifier.matchParentSize()) {
                    val c = Offset(size.width * 0.42f, size.height * 0.58f)
                    val r = size.minDimension * 0.2f
                    val w = 1.3.dp.toPx()
                    drawCircle(Color.White, r, c, style = Stroke(w))
                    for (k in 0 until 8) {
                        val a = Math.toRadians(k * 45.0)
                        val (ca, sa) = cos(a).toFloat() to sin(a).toFloat()
                        drawLine(Color.White, Offset(c.x + ca * r * 1.45f, c.y + sa * r * 1.45f), Offset(c.x + ca * r * 1.9f, c.y + sa * r * 1.9f), w)
                    }
                }
                MdIcon(MdIcon.Colorize, null, tint = Color.White, size = 16.dp, modifier = Modifier.align(Alignment.TopEnd))
            }
        } else Box(modifier) {
            Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension * 0.34f
                val c = Offset(size.width * 0.4f, size.height * 0.6f)
                drawCircle(Brush.horizontalGradient(listOf(Color(0xFF202020), Color(0xFFE0E0E0)), c.x - r, c.x + r), r, c)
            }
            MdIcon(MdIcon.Colorize, null, tint = Color(0xFFD0D0D0), size = 16.dp, modifier = Modifier.align(Alignment.TopEnd))
        }
        else -> MdIcon(MdIcon.Brush, null, tint = Color(0xFFB8B8B8), size = 22.dp, modifier = modifier.padding(1.dp))
    }
}

private fun localControl(spec: LocalSpec, value: Double) =
    DevelopControl("mask.${spec.key}", spec.label, "", "", spec.min, spec.max, 0.0, spec.step, spec.decimals.toUByte(), value, spec.track)

private fun adjustOf(m: MaskInfo): JSONObject = runCatching { JSONObject(m.adjust) }.getOrDefault(JSONObject())

fun shapeOf(m: MaskInfo): JSONObject = runCatching { JSONObject(m.shape) }.getOrDefault(JSONObject())

/** Part [i] of [m] (its first when there's no such part): what it does, its kind and its shape. */
fun partOf(m: MaskInfo, i: Int): MaskPart? = m.components.getOrNull(i) ?: m.components.firstOrNull()

fun partKind(m: MaskInfo, i: Int): String = partOf(m, i)?.kind ?: m.kind

fun partShape(m: MaskInfo, i: Int): JSONObject = partOf(m, i)?.let { runCatching { JSONObject(it.shape) }.getOrNull() } ?: shapeOf(m)

/** A kind of mask's name (`Radial gradient`…). */
fun kindLabel(kind: String): String = maskKinds.firstOrNull { it.first == kind }?.second ?: kind.replaceFirstChar { it.uppercase() }

// ------------------------------------------------------------------------------ masks button

/** Part [i] of [m]'s name: the one it was given, else its kind and its number among the mask's parts of that kind ("Color Range 1"). */
fun partName(m: MaskInfo, i: Int): String {
    val p = m.components.getOrNull(i) ?: return m.name
    p.name?.let { return it }
    return "${kindLabel(p.kind)} ${m.components.take(i + 1).count { it.kind == p.kind }}"
}

/** The selected mask's frame: blue while the mask itself is edited, light while one of its parts is (the reference's). */
private val partSelectedFrame = Color(0xFF1473E6)
private val groupOfPartFrame = Color(0xFFCCCDCE)
private val maskCard = Color.Black
private val nestedArrow = Color(0xFFA2A2A2)

/**
 * Masking's column against the right edge, as the reference's: 76 dp of its grey (8 dp corners at
 * the left) holding the blue + (a new mask) over a 50 dp picture of each mask, the newest first;
 * the handle at its left (or a sideways drag) opens it to 232 dp, each mask's name and its menu
 * beside its picture (add to it, subtract from it, hide or show it, rename, duplicate, invert,
 * duplicate and invert, delete). The edited mask stands on a black card with its parts under it
 * (the newest first, the first marked ↳; tap one to edit it): each a 40 dp square of its kind,
 * framed blue while edited, a − on it while it subtracts, an eye struck through while hidden; open,
 * with its name and its menu (hide, rename, duplicate, convert to subtract or add, delete); then
 * a button to add a part to the mask or subtract one (open: a row for each).
 */
@Composable
fun MaskColumn(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) 232.dp else 76.dp, tween(250, easing = AccelerateDecelerate), label = "masks")
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        if (ed.masks.isNotEmpty()) {
            // the reference's handle: a 2 x 42 dp line in a 44 x 60 dp grip just outside the column
            Box(
                Modifier
                    .size(44.dp, 60.dp)
                    .pointerInput(Unit) {
                        // the whole drag counts, however slow: left opens, right closes
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (dragged < -8.dp.toPx()) open = true else if (dragged > 8.dp.toPx()) open = false },
                        ) { change, drag ->
                            change.consume()
                            dragged += drag
                        }
                    }
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { contentDescription = if (open) "Collapse the masks" else "Expand the masks" },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.padding(end = 6.8.dp).size(2.dp, 42.dp).background(Lc.panelText))
            }
        }
        Column(
            Modifier
                .width(width)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                .background(Lc.masksColumn)
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                // with no mask under it the + is the whole column: equal gaps above and below it
                // keep it on the column's centre instead of 7.8 dp low
                .padding(top = 15.6.dp, bottom = if (ed.masks.isEmpty()) 15.6.dp else 7.8.dp),
        ) {
            Row(Modifier.padding(start = 16.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Lc.primary)
                        .clickable(role = Role.Button) { ed.maskPicker = "new" }
                        .semantics { contentDescription = "Create new Mask" },
                    contentAlignment = Alignment.Center,
                ) {
                    MdIcon(MdIcon.Add, null, tint = Color.White, size = 30.dp)
                }
                if (open) Text("Create new Mask", color = Lc.panelText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
            }
            // the newest mask first, as the reference's
            ed.masks.asReversed().forEach { m ->
                if (m.id == ed.selectedMask) {
                    Column(Modifier.padding(start = 5.9.dp, end = 5.9.dp, top = 7.8.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(maskCard).padding(vertical = 7.8.dp)) {
                        MaskRow(vm, ed, m, open)
                        MaskPartRows(vm, ed, m, open)
                    }
                } else {
                    MaskRow(vm, ed, m, open, Modifier.padding(top = 7.8.dp))
                }
            }
            // 15.6 dp under the + or a mask's picture, 7.8 under the edited mask's card (the reference's)
            if (ed.masks.firstOrNull()?.id != ed.selectedMask) Spacer(Modifier.height(7.8.dp))
        }
    }
}

/** A mask's picture (open: its name and its menu too); the edited mask's framed. */
@Composable
private fun MaskRow(vm: AppViewModel, ed: EditorState, m: MaskInfo, open: Boolean, modifier: Modifier = Modifier) {
    val selected = m.id == ed.selectedMask
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val shown = if (m.visible) 1f else 0.4f
    // in the card the picture sits 7 dp in (closed) or 10 dp (open); outside it 13 or 16 dp from the column's edge
    val start = when {
        selected && open -> 10.2.dp
        selected -> 7.dp
        open -> 16.dp
        else -> 13.dp
    }
    Row(
        modifier.fillMaxWidth().height(50.dp).clickable(role = Role.Button) { vm.selectMask(m.id) }.padding(start = start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val frame = when {
            !selected -> null
            ed.maskPart < 0 -> Color(0xFF378EF0)
            else -> groupOfPartFrame
        }
        MaskThumb(m, ed.info.width.toFloat(), ed.info.height.toFloat(), frame, ed.maskThumbs[m.id], Modifier.size(50.dp).alpha(shown))
        if (open) {
            Text(
                m.name,
                color = if (selected) Lc.panelText else Lc.panelTextSoft,
                fontFamily = Inter,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f).padding(start = 17.dp).alpha(shown),
            )
            Box(if (selected) Modifier.offset(x = 5.9.dp) else Modifier) {
                BarButton(MdIcon.MoreVert, "${m.name} more menu", size = 40.dp, tint = Lc.panelText) {
                    vm.selectMask(m.id)
                    menu = true
                }
                CalloutMenu(expanded = menu, onDismiss = { menu = false }) {
                    CalloutMenuItem("Add to \"${m.name}\"", onClick = {
                        menu = false
                        ed.maskPicker = "add"
                    }) { drawMaskOpIcon(false, it) }
                    CalloutMenuItem("Subtract from \"${m.name}\"", onClick = {
                        menu = false
                        ed.maskPicker = "subtract"
                    }) { drawMaskOpIcon(true, it) }
                    CalloutDivider()
                    CalloutMenuItem(if (m.visible) "Hide" else "Show", onClick = {
                        menu = false
                        vm.toggleMaskVisible()
                    }) { drawEye(it, struck = m.visible) }
                    CalloutMenuItem("Rename", onClick = {
                        menu = false
                        renaming = true
                    }) { drawRenameIcon(it) }
                    CalloutMenuItem("Duplicate", onClick = {
                        menu = false
                        vm.maskCommand("mask.duplicate")
                    }) { drawDuplicateIcon(it) }
                    CalloutDivider()
                    CalloutMenuItem("Invert Mask", onClick = {
                        menu = false
                        vm.maskCommand("mask.invert")
                    }) { drawInvertIcon(it) }
                    CalloutMenuItem("Duplicate & Invert Mask", onClick = {
                        menu = false
                        vm.duplicateInvertedMask()
                    }) { drawDuplicateInvertIcon(it) }
                    CalloutDivider()
                    CalloutMenuItem("Delete", onClick = {
                        menu = false
                        vm.maskCommand("mask.delete")
                    }) { drawTrashIcon(it) }
                }
            }
        }
    }
    if (renaming) {
        RenameMaskDialog(m.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            vm.renameMask(name)
        }
    }
}

/**
 * The edited mask's parts under its picture, the newest first (the first marked ↳), then adding to
 * it or subtracting from it: closed, a button that offers both; open, a row for each.
 */
@Composable
private fun MaskPartRows(vm: AppViewModel, ed: EditorState, m: MaskInfo, open: Boolean) {
    m.components.indices.reversed().forEachIndexed { row, i ->
        val part = m.components[i]
        val selected = ed.maskPart == i
        var menu by remember(m.id, i) { mutableStateOf(false) }
        var renaming by remember(m.id, i) { mutableStateOf(false) }
        val name = partName(m, i)
        Row(
            Modifier
                .padding(top = if (row == 0) 3.9.dp else 0.dp)
                .fillMaxWidth()
                .height(if (open) 49.6.dp else 47.7.dp)
                .clickable(role = Role.Button) { vm.selectMaskPart(i) }
                .semantics { contentDescription = name },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // the ↳ beside the first, the square 21 dp in (closed) or 30 dp (open)
            Box(Modifier.width(if (open) 30.2.dp else 21.4.dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                if (row == 0) Text("↳", color = nestedArrow, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(end = 4.dp, bottom = 12.dp))
            }
            PartSquare(part.kind, part.op, part.hidden, selected)
            if (open) {
                Text(
                    name,
                    color = if (part.hidden) Lc.panelText.copy(alpha = 0.45f) else Lc.panelText,
                    fontFamily = Inter,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.8.dp),
                )
                // past the card's edge to the screen's, as the reference's
                Box(Modifier.offset(x = 5.9.dp)) {
                    BarButton(MdIcon.MoreVert, "$name more menu", size = 32.dp, iconSize = 22.dp, tint = Lc.panelText) {
                        vm.selectMaskPart(i)
                        menu = true
                    }
                    CalloutMenu(expanded = menu, onDismiss = { menu = false }) {
                        CalloutMenuItem(if (part.hidden) "Show" else "Hide", onClick = {
                            menu = false
                            vm.partCommand(i, "hide")
                        }) { drawEye(it, struck = !part.hidden) }
                        CalloutMenuItem("Rename", onClick = {
                            menu = false
                            renaming = true
                        }) { drawRenameIcon(it) }
                        CalloutMenuItem("Duplicate", onClick = {
                            menu = false
                            vm.duplicatePart(i, "$name Copy")
                        }) { drawDuplicateIcon(it) }
                        // the first part can't subtract (there'd be nothing to take from)
                        val subtracting = part.op == "subtract"
                        CalloutMenuItem(if (subtracting) "Convert to Add" else "Convert to Subtract", enabled = i > 0, onClick = {
                            menu = false
                            vm.partOp(i, if (subtracting) "add" else "subtract")
                        }) { drawMaskOpIcon(!subtracting, it) }
                        CalloutMenuItem("Delete", onClick = {
                            menu = false
                            vm.partCommand(i, "delete")
                        }) { drawTrashIcon(it) }
                    }
                }
            }
        }
        if (renaming) {
            RenameMaskDialog(name, onDismiss = { renaming = false }) { new ->
                renaming = false
                vm.renamePart(i, new)
            }
        }
    }
    if (open) {
        Spacer(Modifier.height(7.5.dp))
        AddSubtractRow(ed, m, subtract = false)
        AddSubtractRow(ed, m, subtract = true)
    } else {
        var choose by remember { mutableStateOf(false) }
        Box(Modifier.padding(start = 17.5.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { choose = true }
                    .semantics { contentDescription = "Add to or subtract from the mask" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(26.dp)) { drawAddSubtract() }
            }
            LcMenu(expanded = choose, onDismiss = { choose = false }) {
                listOf(false, true).forEach { subtract ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable(role = Role.Button) {
                                choose = false
                                ed.maskPicker = if (subtract) "subtract" else "add"
                            }
                            .padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Canvas(Modifier.size(22.dp)) { drawMaskOpIcon(subtract) } }
                        Text(if (subtract) "Subtract from \"${m.name}\"" else "Add to \"${m.name}\"", color = Lc.menuText, fontFamily = Inter, fontSize = 17.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The open column's Add to "Mask 1" or Subtract from "Mask 1": 37 dp, its icon 39 dp in. */
@Composable
private fun AddSubtractRow(ed: EditorState, m: MaskInfo, subtract: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(37.4.dp).clickable(role = Role.Button) { ed.maskPicker = if (subtract) "subtract" else "add" }.padding(start = 39.4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(19.dp)) { drawMaskOpIcon(subtract) }
        Text(
            if (subtract) "Subtract from \"${m.name}\"" else "Add to \"${m.name}\"",
            color = Lc.panelText,
            fontFamily = Inter,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 9.dp, end = 8.dp),
        )
    }
}

/** Add to (a + in a ring) or subtract from (a −) a mask: a square cut by the ring at its corner. */
private fun DrawScope.drawMaskOpIcon(subtract: Boolean, tint: Color = Lc.panelText) {
    val w = 1.5.dp.toPx()
    val s = size.minDimension
    val c = Offset(s * 0.68f, s * 0.68f)
    val r = s * 0.3f
    val square = Path().apply {
        moveTo(c.x - r, s * 0.62f)
        lineTo(s * 0.08f, s * 0.62f)
        lineTo(s * 0.08f, s * 0.08f)
        lineTo(s * 0.62f, s * 0.08f)
        lineTo(s * 0.62f, c.y - r)
    }
    drawPath(square, tint, style = Stroke(w))
    drawCircle(tint, r, c, style = Stroke(w))
    drawLine(tint, Offset(c.x - r * 0.5f, c.y), Offset(c.x + r * 0.5f, c.y), w)
    if (!subtract) drawLine(tint, Offset(c.x, c.y - r * 0.5f), Offset(c.x, c.y + r * 0.5f), w)
}

/** The closed column's add or subtract button, as the reference's: a white − disc, a white + disc above it to the right. */
private fun DrawScope.drawAddSubtract() {
    val w = 1.6.dp.toPx()
    val s = size.minDimension
    val r = s * 0.22f
    val minus = Offset(s * 0.3f, s * 0.66f)
    val plus = Offset(s * 0.68f, s * 0.34f)
    for ((c, sign) in listOf(minus to false, plus to true)) {
        drawCircle(Color.Black, r + 1.2.dp.toPx(), c)
        drawCircle(Lc.panelText, r, c)
        drawLine(Color.Black, Offset(c.x - r * 0.55f, c.y), Offset(c.x + r * 0.55f, c.y), w)
        if (sign) drawLine(Color.Black, Offset(c.x, c.y - r * 0.55f), Offset(c.x, c.y + r * 0.55f), w)
    }
}

/**
 * A part's 40 dp square, as the reference's: its kind's icon on dark grey (hidden: an eye struck
 * through, on black), framed blue while edited (with a small mark in its corner) or grey; a − at
 * its right edge while it subtracts.
 */
@Composable
private fun PartSquare(kind: String, op: String, hidden: Boolean, selected: Boolean) {
    Box(Modifier.size(width = 46.dp, height = 40.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (hidden) Color.Black else Color(0xFF303030))
                .border(if (selected) 2.dp else 1.dp, if (selected) partSelectedFrame else Lc.chevron, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (hidden) MdIcon(MdIcon.VisibilityOff, null, tint = Lc.panelText, size = 22.dp) else MaskKindIcon(kind, Modifier.size(22.dp), mono = true)
            if (selected && !hidden) {
                Canvas(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(6.dp)) {
                    drawPath(Path().apply {
                        moveTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }, Lc.panelTextSoft)
                }
            }
        }
        if (!hidden && (op == "subtract" || op == "intersect")) {
            Canvas(Modifier.align(Alignment.CenterEnd).size(13.dp)) {
                val r = size.minDimension / 2
                drawCircle(Color(0xFF2B2B2B), r)
                drawCircle(Lc.panelTextSoft, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                drawLine(Color.White, Offset(r - r * 0.5f, r), Offset(r + r * 0.5f, r), 1.6.dp.toPx())
            }
        }
    }
}

// ------------------------------------------------------------------------------------ the part's bar

/** The left bar's grey and its value boxes, as the reference's. */
private val barBox = Color.Black
private val barDivider = Color(0xFF6A6A6A)

/**
 * The edited part's tools, as the reference's bar against the left edge (56 dp of the column's grey,
 * 8 dp corners at its right, its foot level with the column's): a colour or luminance range's
 * Pick (its dropper filled with what it picked) and a colour range's Refine; a radial's Feather; a
 * brush's Erase, Size, Feather and Flow; then a line, Invert and Delete. A value's box opens a
 * slider beside the bar. The mask itself edited (no part): Delete.
 */
@Composable
fun MaskPartBar(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val mask = ed.masks.firstOrNull { it.id == ed.selectedMask } ?: return
    val i = ed.maskPart
    val part = mask.components.getOrNull(i)
    // which value's slider is open beside the bar
    var editing by remember(mask.id, i) { mutableStateOf<String?>(null) }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Column(
            Modifier
                .width(if (part == null) 63.7.dp else 56.dp)
                .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                .background(Lc.masksColumn)
                .padding(vertical = 7.8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (part == null) {
                BarIcon(LcIcon.Trash, "Delete") { vm.maskCommand("mask.delete") }
                return@Column
            }
            val shape = runCatching { JSONObject(part.shape) }.getOrDefault(JSONObject())
            var tools = true
            when (part.kind) {
                "colorRange", "luminanceRange" -> {
                    BarBox("Pick", on = false, onClick = vm::repickPart) {
                        Canvas(Modifier.size(30.dp)) { drawDropper(pickedColor(part.kind, shape) ?: Lc.panelText) }
                    }
                    if (part.kind == "colorRange") {
                        val v = ed.liveShape["refine"] ?: shape.optDouble("refine", 50.0)
                        BarGap()
                        BarValue("Refine", v, editing == "refine") { editing = if (editing == "refine") null else "refine" }
                    }
                }
                "radial" -> {
                    val v = ed.liveShape["feather"] ?: shape.optDouble("feather", 50.0)
                    BarValue("Feather", v, editing == "feather") { editing = if (editing == "feather") null else "feather" }
                }
                "brush" -> {
                    BarBox(if (ed.maskErase) "Paint" else "Erase", on = ed.maskErase, onClick = { ed.maskErase = !ed.maskErase }) {
                        LcIcon(if (ed.maskErase) LcIcon.PencilOff else LcIcon.Pencil, null, tint = Lc.panelText, size = 24.dp)
                    }
                    BarGap()
                    BarValue("Size", ed.maskBrushSize * 1000.0, editing == "size") { editing = if (editing == "size") null else "size" }
                    BarGap()
                    BarValue("Feather", ed.maskBrushFeather.toDouble(), editing == "brushFeather") { editing = if (editing == "brushFeather") null else "brushFeather" }
                    BarGap()
                    BarValue("Flow", ed.maskBrushFlow.toDouble(), editing == "flow") { editing = if (editing == "flow") null else "flow" }
                }
                else -> tools = false
            }
            // the reference's line between the part's own tools and Invert and Delete (which touch)
            if (tools) {
                BarGap()
                Box(Modifier.size(width = 22.dp, height = 1.dp).background(barDivider))
                BarGap()
            }
            Box(Modifier.size(48.dp).clickable(role = Role.Button) { vm.partCommand(i, "invert") }.semantics { contentDescription = "Invert" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.dp)) { drawInvertIcon(if (part.invert) Lc.panelText else Lc.chevron) }
            }
            BarIcon(LcIcon.Trash, "Delete") { vm.partCommand(i, "delete") }
        }
        val open = editing
        if (open != null && part != null) {
            val shape = runCatching { JSONObject(part.shape) }.getOrDefault(JSONObject())
            when (open) {
                // the point toward the value's box: the lowest box's middle is 144.4 dp over the bar's foot, each above it 55.8 more
                "refine", "feather" -> BarSlider(
                    ed,
                    if (open == "refine") "Refine" else "Feather",
                    ed.liveShape[open] ?: shape.optDouble(open, 50.0),
                    if (open == "refine") 1.0 else 0.0,
                    100.0,
                    pointFromBottom = 144.4.dp,
                    signed = open == "refine",
                    onChange = { vm.setShapeValue(open, it) },
                    onDone = vm::shapeDone,
                )
                "size" -> BarSlider(ed, "Size", ed.maskBrushSize * 1000.0, 2.0, 100.0, 256.dp, onChange = { ed.maskBrushSize = (it / 1000).toFloat() }, onDone = {})
                "brushFeather" -> BarSlider(ed, "Feather", ed.maskBrushFeather.toDouble(), 0.0, 100.0, 200.2.dp, onChange = { ed.maskBrushFeather = it.toFloat() }, onDone = {})
                "flow" -> BarSlider(ed, "Flow", ed.maskBrushFlow.toDouble(), 1.0, 100.0, 144.4.dp, onChange = { ed.maskBrushFlow = it.toFloat() }, onDone = {})
            }
        }
    }
}

/** The 7.8 dp between the bar's boxes. */
@Composable
private fun BarGap() = Spacer(Modifier.height(7.8.dp))

/** The bar's Invert, as the reference's: a grey square cut corner to corner, a disc in it dark on one side of the cut. */
fun DrawScope.drawInvertIcon(tint: Color) {
    val s = size.minDimension
    val inset = s * 0.12f
    val side = s - 2 * inset
    drawRect(tint, Offset(inset, inset + side * 0.1f), Size(side, side * 0.8f))
    val c = center
    val r = side * 0.3f
    // the disc's half above the cut (bottom left to top right) dark, the rest the square's grey
    val half = Path().apply {
        moveTo(c.x - r * 1.2f, c.y + r * 1.2f)
        lineTo(c.x + r * 1.2f, c.y - r * 1.2f)
        lineTo(c.x - r * 1.2f, c.y - r * 1.2f)
        close()
    }
    clipPath(half) { drawCircle(Lc.masksColumn, r, c) }
    drawLine(Lc.masksColumn, Offset(inset, inset + side * 0.9f), Offset(inset + side, inset + side * 0.1f), 1.6.dp.toPx())
}

/** A tool of the bar in its 48 dp box (black, a 1 dp grey edge; [on]: its edge [edge]). */
@Composable
private fun BarBox(description: String, on: Boolean, edge: Color = Lc.panelText, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(barBox)
            .border(1.dp, if (on) edge else Lc.panelButtonBorder, RoundedCornerShape(5.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A value of the bar: its number in a box (open: its edge blue). */
@Composable
private fun BarValue(label: String, value: Double, open: Boolean, onClick: () -> Unit) {
    BarBox("$label ${value.roundToInt()}", on = open, edge = Lc.primaryBlue, onClick = onClick) {
        Text("${value.roundToInt()}", color = Lc.panelText, fontFamily = Inter, fontSize = 16.sp, maxLines = 1)
    }
}

/** A tool of the bar with no box: its icon in 48 dp. */
@Composable
private fun BarIcon(icon: LcIcon, description: String, tint: Color = Lc.panelText, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        LcIcon(icon, null, tint = tint, size = 28.dp)
    }
}

/** The bar's Pick, as the reference's: a dropper from the top right down to the left, a disc of [fill] (what the part picked) over its middle. */
private fun DrawScope.drawDropper(fill: Color) {
    val s = size.minDimension
    val stroke = Lc.panelTextSoft
    // the barrel (hollow), its tip at the bottom left, the bulb at the top right
    drawLine(stroke, Offset(s * 0.66f, s * 0.3f), Offset(s * 0.16f, s * 0.8f), 4.6.dp.toPx(), cap = StrokeCap.Round)
    drawLine(barBox, Offset(s * 0.62f, s * 0.34f), Offset(s * 0.2f, s * 0.76f), 1.8.dp.toPx(), cap = StrokeCap.Round)
    drawLine(stroke, Offset(s * 0.84f, s * 0.12f), Offset(s * 0.66f, s * 0.3f), 4.2.dp.toPx(), cap = StrokeCap.Round)
    val c = Offset(s * 0.62f, s * 0.6f)
    val r = s * 0.29f
    drawCircle(barBox, r + 1.dp.toPx(), c)
    drawCircle(Color.White, r, c)
    drawCircle(fill, r - 1.6.dp.toPx(), c)
}

/** The value slider's track above its ring (below it, the ring's white). */
private val barTrackAbove = Color(0xFF929292)

/**
 * A value's slider beside the bar, as the reference's: 31 x 251 dp of the column's grey with a 1 dp
 * light edge, 20 dp right of the bar and 5 dp below its foot, a point on its left toward the
 * value's box ([pointFromBottom]: its middle above the bar's foot); its track upright (the least at
 * the foot), white below the ring and grey above it, broken round the ring; drag it (or tap on the
 * track) to set it. While it's open its name and value stand in the pill under the top bar.
 */
@Composable
private fun BarSlider(ed: EditorState, label: String, value: Double, min: Double, max: Double, pointFromBottom: Dp, signed: Boolean = false, onChange: (Double) -> Unit, onDone: () -> Unit) {
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val current by rememberUpdatedState(value)
    fun text(v: Double) = "$label: ${if (signed && v.roundToInt() > 0) "+" else ""}${v.roundToInt()}"
    // the pill under the top bar, as long as the slider's open
    SideEffect { ed.barPill = text(value) }
    DisposableEffect(Unit) { onDispose { ed.barPill = null } }
    val edge = Lc.panelText
    Canvas(
        Modifier
            .padding(start = 12.7.dp)
            .offset(y = 4.8.dp)
            .size(width = 38.9.dp, height = 251.dp)
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val top = 20.dp.toPx()
                    val travel = (size.height - top - 22.8.dp.toPx()).coerceAtLeast(1f)
                    fun at(y: Float) = (min + (1 - ((y - top) / travel).coerceIn(0f, 1f)) * (max - min)).roundToInt().toDouble()
                    down.consume()
                    change(at(down.position.y))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        change(at(c.position.y))
                    }
                    done()
                }
            }
            .semantics { contentDescription = "$label ${current.roundToInt()}" },
    ) {
        // the popup (right of the point) and its point toward the box
        val point = 7.8.dp.toPx()
        val left = point
        val w = size.width - left
        val corner = CornerRadius(4.dp.toPx())
        val py = size.height - (pointFromBottom + 4.8.dp).toPx()
        drawRoundRect(Lc.masksColumn, Offset(left, 0f), Size(w, size.height), corner)
        drawRoundRect(edge, Offset(left, 0f), Size(w, size.height), corner, style = Stroke(1.dp.toPx()))
        val tip = Path().apply {
            moveTo(left + 1.dp.toPx(), py - 7.dp.toPx())
            lineTo(0f, py)
            lineTo(left + 1.dp.toPx(), py + 7.dp.toPx())
            close()
        }
        drawPath(tip, Lc.masksColumn)
        drawLine(edge, Offset(left, py - 7.dp.toPx()), Offset(0f, py), 1.dp.toPx())
        drawLine(edge, Offset(0f, py), Offset(left, py + 7.dp.toPx()), 1.dp.toPx())
        // the track, broken round the ring
        val top = 20.dp.toPx()
        val bottom = size.height - 22.8.dp.toPx()
        val x = left + w / 2
        val t = ((current - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
        val y = top + (1 - t) * (bottom - top)
        val ring = 9.dp.toPx()
        val gap = ring + 4.3.dp.toPx()
        if (y - gap > top) drawLine(barTrackAbove, Offset(x, top), Offset(x, y - gap), 2.dp.toPx())
        if (y + gap < bottom) drawLine(edge, Offset(x, y + gap), Offset(x, bottom), 2.dp.toPx())
        drawCircle(edge, ring, Offset(x, y), style = Stroke(2.4.dp.toPx()))
    }
}

/**
 * A mask's picture: what it selects white on black, in the photo's shape (its render; until there is
 * one a radial's soft ellipse, a linear's ramp from full to none, the others their kind's icon),
 * framed while it's edited.
 */
@Composable
private fun MaskThumb(m: MaskInfo, photoW: Float, photoH: Float, frame: Color?, picture: ImageBitmap?, modifier: Modifier) {
    val s = shapeOf(m)
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .background(Color.Black)
            // the reference's frames: 2 dp for the mask edited (blue, or light while one of its parts is), 1 dp grey otherwise
            .border(if (frame != null) 2.dp else 1.dp, frame ?: Lc.chevron, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // what it selects, white on black, as rendered (the reference's); drawn from its shape until then
        if (picture != null) {
            Image(picture, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            return@Box
        }
        when (m.kind) {
            "radial", "linear" -> Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                val w = photoW.coerceAtLeast(1f)
                val h = photoH.coerceAtLeast(1f)
                val k = min(size.width / w, size.height / h)
                val pw = w * k
                val ph = h * k
                val left = (size.width - pw) / 2
                val top = (size.height - ph) / 2
                fun at(x: Double, y: Double) = Offset(left + (x * pw).toFloat(), top + (y * ph).toFloat())
                val white = if (m.invert) Color.Black else Color.White
                val black = if (m.invert) Color.White else Color.Black
                drawRect(black, Offset(left, top), Size(pw, ph))
                if (m.kind == "radial") {
                    val (cx, cy) = pt(s, "center")
                    val long = max(pw, ph)
                    val rx = (s.optDouble("rx", 0.2) * long).toFloat().coerceAtLeast(0.1f)
                    val ry = (s.optDouble("ry", 0.15) * long).toFloat()
                    val c = at(cx, cy)
                    rotate(s.optDouble("angle", 0.0).toFloat(), c) {
                        scale(1f, ry / rx, c) {
                            drawCircle(Brush.radialGradient(listOf(white, white, black), c, rx), rx, c)
                        }
                    }
                } else {
                    val (sx, sy) = pt(s, "start")
                    val (ex, ey) = pt(s, "end")
                    drawRect(Brush.linearGradient(listOf(white, black), at(sx, sy), at(ex, ey)), Offset(left, top), Size(pw, ph))
                }
            }
            else -> LcIcon(maskIcon(m.kind), null, tint = Lc.panelTextSoft, size = 24.dp)
        }
    }
}

/**
 * The new mask sheet, as the reference's: "Create new mask" over 47.7 dp rows every 55.5 dp in
 * groups 17.5 dp apart (its first group, Select Subject, Sky and Background, is AI and left out).
 */
@Composable
fun NewMaskSheet(vm: AppViewModel, op: String?, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val title = when (op) {
        "add" -> "Add to the mask"
        "subtract" -> "Subtract from the mask"
        else -> "Create new Mask"
    }
    // the reference's: inset 12.6 dp from the screen's sides, a bold title over a line, then its kinds as dark cards in groups
    LcSheet(onDismiss, inset = 12.6.dp) {
        Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Lc.panelText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            BarButton(MdIcon.HelpOutline, "Help", size = 48.dp, iconSize = 24.dp, tint = Lc.panelText) { runCatching { uri.openUri(HELP_URL) } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.sectionChosen))
        Spacer(Modifier.height(16.dp))
        listOf(maskKinds.take(3), maskKinds.drop(3)).forEachIndexed { g, group ->
            if (g > 0) {
                Spacer(Modifier.height(7.dp))
                Box(Modifier.align(Alignment.CenterHorizontally).width(320.dp).height(2.dp).background(Lc.panelButtonBorder))
                Spacer(Modifier.height(7.dp))
            }
            group.forEachIndexed { i, (kind, label, _) ->
                if (i > 0) Spacer(Modifier.height(7.8.dp))
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(47.7.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Lc.panelButton)
                        .clickable(role = Role.Button) { if (op == null) vm.addMask(kind) else vm.addMaskPart(op, kind) }
                        .padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MaskKindIcon(kind, Modifier.size(24.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(label, color = Lc.panelText, fontFamily = Inter, fontSize = 16.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The photo's masks (tap one to edit it), and a new one. */
@Composable
fun MasksSheet(vm: AppViewModel, ed: EditorState, onDismiss: () -> Unit) {
    LcSheet(onDismiss) {
        SheetHeading("Masks")
        SheetRow(LcIcon.Plus, "Create new mask") { ed.maskPicker = "new" }
        SheetDivider()
        ed.masks.forEach { m ->
            SheetRow(maskIcon(m.kind), m.name, selected = m.id == ed.selectedMask) {
                onDismiss()
                vm.selectMask(m.id)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// --------------------------------------------------------------------------------------- sheet

/** Masking's panel for the edited mask: a section's local sliders (its parts' own tools are on [MaskPartBar]). */
@Composable
fun MaskSheet(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val mask = ed.masks.firstOrNull { it.id == ed.selectedMask }
    Column(modifier) {
        LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
            when {
                mask == null -> {}
                else -> {
                    val section = MaskSection.entries.firstOrNull { it.name.lowercase() == ed.maskSection } ?: MaskSection.LIGHT
                    val adjust = adjustOf(mask)
                    items(section.keys, key = { it }) { key ->
                        localSpecs[key]?.let { spec ->
                            val c = localControl(spec, ed.liveLocal[key] ?: adjust.optDouble(key, 0.0))
                            DevelopSlider(c, onChange = { vm.setLocal(key, it) }, onFinished = vm::localDone, onReset = { vm.setLocal(key, 0.0); vm.localDone() })
                        }
                    }
                    if (section == MaskSection.COLOR) {
                        item(key = "colorSwatch") {
                            MaskTint(
                                hue = ed.liveLocal["color_hue"] ?: adjust.optDouble("color_hue", 0.0),
                                sat = ed.liveLocal["color_sat"] ?: adjust.optDouble("color_sat", 0.0),
                                onChange = { h, sat ->
                                    vm.setLocal("color_hue", h)
                                    vm.setLocal("color_sat", sat)
                                },
                                onDone = vm::localDone,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- the overlays' options

/** The mask overlay's colours (the reference's three) with their names. */
private val maskOverlayColors = listOf(0xFF0000 to "Red", 0x00FF00 to "Green", 0x0000FF to "Blue")

/** The mask overlay's modes, in the reference's order: the engine's ids and their names. */
val maskOverlayModes = listOf(
    "color" to "Color Overlay",
    "colorOnBw" to "Color Overlay on B&W",
    "imageOnBw" to "Image on B&W",
    "imageOnBlack" to "Image on Black",
    "imageOnWhite" to "Image on White",
    "whiteOnBlack" to "White on Black",
)

private val optionText = Color(0xFFEBEBEB)
private val optionDivider = Color(0xFF4B4B4B)

/**
 * Masking's options (the top bar's More while Masking is open), as the reference's sheet: the mask
 * overlay (always shown or not, its mode on a page of its own, its colour, its opacity), the tool
 * overlay (always shown or not), and Help.
 */
@Composable
fun MaskOptionsSheet(vm: AppViewModel, onHelp: () -> Unit, onDismiss: () -> Unit) {
    var modes by remember { mutableStateOf(false) }
    LcSheet(onDismiss) {
        OptionHeading("MASK OVERLAY")
        OptionSwitch("Always show", vm.maskOverlayAlways) { vm.setMaskOptions(always = it) }
        Row(
            Modifier.fillMaxWidth().height(48.2.dp).clickable(role = Role.Button) { modes = true }.padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mode", color = optionText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(maskOverlayModes.firstOrNull { it.first == vm.maskOverlayMode }?.second.orEmpty(), color = optionText, fontFamily = Inter, fontSize = 15.sp)
            MdIcon(MdIcon.ChevronRight, null, tint = Lc.text, modifier = Modifier.padding(start = 14.dp))
        }
        Row(Modifier.fillMaxWidth().height(48.2.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Color", color = optionText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
            maskOverlayColors.forEach { (rgb, name) ->
                val on = vm.maskOverlayColor == rgb
                Canvas(
                    Modifier
                        .padding(end = 16.dp)
                        .size(48.2.dp)
                        .selectable(on, role = Role.RadioButton) { vm.setMaskOptions(color = rgb) }
                        .semantics { contentDescription = name },
                ) {
                    drawCircle(Color(0xFF000000 or rgb.toLong()), 9.7.dp.toPx())
                    if (on) drawCircle(optionText, 11.45.dp.toPx(), style = Stroke(1.9.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(48.2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", color = optionText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
            // the slider's own 16 dp margins put its bar 16 dp in from the sheet's edge (the reference's)
            Box(Modifier.width(224.3.dp).fillMaxHeight(), contentAlignment = Alignment.TopStart) {
                DevelopSlider(
                    DevelopControl("mask.overlayOpacity", "Opacity", "", "", 0.0, 100.0, 50.0, 1.0, 0u, vm.maskOverlayOpacity.toDouble(), SliderTrack.Plain),
                    onChange = { vm.setMaskOptions(opacity = it.roundToInt()) },
                    onFinished = {},
                    onReset = { vm.setMaskOptions(opacity = 50) },
                    label = "",
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
        }
        OptionDivider()
        OptionHeading("TOOL OVERLAY")
        OptionSwitch("Always show", vm.toolOverlayAlways) { vm.setMaskOptions(tools = it) }
        OptionDivider()
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(48.2.dp).clickable(role = Role.Button, onClick = onHelp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(48.2.dp), contentAlignment = Alignment.Center) { MdIcon(MdIcon.HelpOutline, null, tint = optionText, size = 22.dp) }
            Text("Help", color = optionText, fontFamily = Inter, fontSize = 15.sp)
        }
        Spacer(Modifier.height(2.2.dp))
    }
    // the modes: a sheet over the options, the one shown ticked; a choice goes back to the options
    OverlayHost(modes.takeIf { it }) {
        LcSheet({ modes = false }) {
            Spacer(Modifier.height(4.8.dp))
            maskOverlayModes.forEach { (id, label) ->
                val on = vm.maskOverlayMode == id
                Row(
                    Modifier.fillMaxWidth().height(48.2.dp).selectable(on, role = Role.RadioButton) {
                        vm.setMaskOptions(mode = id)
                        modes = false
                    }.padding(start = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = optionText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (on) MdIcon(MdIcon.Check, "Selected", tint = optionText)
                }
            }
            Spacer(Modifier.height(4.9.dp))
        }
    }
}

/** A heading of the options, in capitals and bold, 16 dp around it. */
@Composable
private fun OptionHeading(text: String) {
    Text(text, color = optionText, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.5.sp, modifier = Modifier.padding(16.dp))
}

/** An option with a switch at its end (48 dp). */
@Composable
private fun OptionSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.2.dp).toggleable(on, role = Role.Switch, onValueChange = onChange).padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = optionText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
        LcSwitch(on)
    }
}

/** The line between the options' groups: 2 dp, 32 dp in from the sides, 16 dp below what's above. */
@Composable
private fun OptionDivider() = Box(Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp).fillMaxWidth().height(2.dp).background(optionDivider))

// ------------------------------------------------------------------------------------- overlay

/**
 * The selected mask on the photo: a radial gradient's ellipse and handles, a linear gradient's
 * lines, or the brush (paint or erase with a finger); a colour or luminance range's marker where it
 * was picked (the reference's: a disc of blues in a white ring).
 */
@Composable
fun MaskOverlay(vm: AppViewModel, ed: EditorState, mask: MaskInfo, geometry: ViewGeometry, imageSize: IntSize, modifier: Modifier) {
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // the tool overlay (the shape's lines and handles): always, or while a finger is on the photo and a
    // moment after (the masking options' choice)
    var touching by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(true) }
    LaunchedEffect(touching) {
        if (touching) {
            lingering = true
        } else {
            delay(1500)
            lingering = false
        }
    }
    val tools = vm.toolOverlayAlways || touching || lingering
    // the part being edited
    val part = ed.maskPart
    val kind = partKind(mask, part)
    val shape = partShape(mask, part)
    val live = ed.liveShape
    // read when a finger goes down: a drag's draft renders change the image's size, and restarting
    // the gesture then would cut the drag off before its end
    val currentShape by rememberUpdatedState(shape)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentSize by rememberUpdatedState(imageSize)
    Canvas(
        modifier.pointerInput(Unit) {
            // watches every touch without taking it
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                do {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                } while (ev.changes.any { it.pressed })
                touching = false
            }
        }.pointerInput(mask.id, kind, part) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val shape = currentShape
                val geometry = currentGeometry
                val map = ViewMap(geometry, fittedRect(size, currentSize))
                val long = max(geometry.width, geometry.height)
                when (kind) {
                    "brush" -> {
                        val path = mutableListOf(down.position)
                        stroke = path.toList()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            // a second finger: the photo moves instead, and the stroke is dropped
                            if (ev.changes.count { it.pressed } > 1 || c.isConsumed) {
                                stroke = emptyList()
                                return@awaitEachGesture
                            }
                            if ((c.position - path.last()).getDistance() > 4f) {
                                path += c.position
                                stroke = path.toList()
                            }
                            c.consume()
                        }
                        stroke = emptyList()
                        vm.brushStroke(path.map { map.photo(it) })
                    }
                    "radial", "linear" -> {
                        val handles = shapeHandles(kind, shape, map, geometry)
                        val near = handles.withIndex().minByOrNull { (_, h) -> (h - down.position).getDistance() }
                        val handle = if (near != null && (near.value - down.position).getDistance() < 36.dp.toPx()) near.index else -1
                        if (handle < 0) return@awaitEachGesture
                        vm.beginShapeDrag()
                        val start = JSONObject(shape.toString())
                        val (x0, y0) = map.photo(down.position)
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                // a second finger: the photo's
                                if (ev.changes.count { it.pressed } > 1 || c.isConsumed) break
                                c.consume()
                                val (x, y) = map.photo(c.position)
                                vm.dragShape(draggedShape(kind, start, handle, x - x0, y - y0, x, y, geometry.width, geometry.height, long))
                            }
                        } finally {
                            vm.endShapeDrag()
                        }
                    }
                }
            }
        },
    ) {
        val map = ViewMap(geometry, fittedRect(IntSize(size.width.toInt(), size.height.toInt()), imageSize))
        val current = JSONObject(shape.toString()).also { s -> live.forEach { (k, v) -> s.put(k, v) } }
        if (tools) {
            ed.liveShapeJson?.let { j -> runCatching { JSONObject(j) }.getOrNull() }?.let { drawShape(kind, it, map, geometry) } ?: drawShape(kind, current, map, geometry)
            // the range parts' markers, where each was picked (an area: its middle)
            mask.components.forEachIndexed { i, p ->
                if (p.hidden || (p.kind != "colorRange" && p.kind != "luminanceRange")) return@forEachIndexed
                val picked = runCatching { JSONObject(p.shape).optJSONArray("picked") }.getOrNull() ?: return@forEachIndexed
                val pts = (0 until picked.length()).mapNotNull { k -> picked.optJSONObject(k)?.let { it.optDouble("x") to it.optDouble("y") } }
                if (pts.isEmpty()) return@forEachIndexed
                drawRangeMarker(map.screen(pts.map { it.first }.average(), pts.map { it.second }.average()), i == part)
            }
        }
        if (stroke.isNotEmpty()) {
            val r = map.radius(ed.maskBrushSize.toDouble())
            val path = Path().apply {
                moveTo(stroke[0].x, stroke[0].y)
                stroke.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val color = if (ed.maskErase) Color(0x66E04A4A) else Color(0x5566AAFF)
            drawPath(path, color, style = Stroke(width = 2 * r, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (stroke.size == 1) drawCircle(color, r, stroke[0])
        }
    }
}

/** The blues of a range part's marker, going round. */
private val markerBlues = listOf(0xFF2F86EA, 0xFF4A97EE, 0xFF6AA9F1, 0xFF8CBDF4, 0xFFAECFF7, 0xFFCFE2FA).map { Color(it) }

/** A range part's marker at [c]: a disc of blues (greys while another part is edited) in a 2 dp white ring, 19 dp across. */
private fun DrawScope.drawRangeMarker(c: Offset, edited: Boolean) {
    val r = 7.7.dp.toPx()
    drawCircle(Color.White, r + 2.dp.toPx(), c)
    markerBlues.forEachIndexed { k, blue ->
        val color = if (edited) blue else Color(blue.red * 0.3f + blue.green * 0.59f + blue.blue * 0.11f, blue.red * 0.3f + blue.green * 0.59f + blue.blue * 0.11f, blue.red * 0.3f + blue.green * 0.59f + blue.blue * 0.11f)
        drawArc(color, -90f + k * 60f, 60f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
    }
}

private fun pt(o: JSONObject, key: String): Pair<Double, Double> = o.optJSONObject(key)?.let { it.optDouble("x", 0.5) to it.optDouble("y", 0.5) } ?: (0.5 to 0.5)

/** A point of the photo, `dx`, `dy` long-edge units from [c] turned by [angle] degrees (the radial's frame). */
private fun around(c: Pair<Double, Double>, dx: Double, dy: Double, angle: Double, w: Double, h: Double): Pair<Double, Double> {
    val a = Math.toRadians(angle)
    val long = max(w, h)
    val rx = dx * cos(a) - dy * sin(a)
    val ry = dx * sin(a) + dy * cos(a)
    return (c.first + rx * long / w) to (c.second + ry * long / h)
}

/** Where a radial's (centre, x radius, y radius) or a linear's (start, end, middle) handles are on screen. */
private fun shapeHandles(kind: String, s: JSONObject, map: ViewMap, g: ViewGeometry): List<Offset> = when (kind) {
    "radial" -> {
        val c = pt(s, "center")
        val (rx, ry, a) = Triple(s.optDouble("rx", 0.2), s.optDouble("ry", 0.15), s.optDouble("angle", 0.0))
        listOf(c, around(c, rx, 0.0, a, g.width, g.height), around(c, 0.0, ry, a, g.width, g.height)).map { (x, y) -> map.screen(x, y) }
    }
    else -> {
        val (a, b) = pt(s, "start") to pt(s, "end")
        listOf(a, b, ((a.first + b.first) / 2) to ((a.second + b.second) / 2)).map { (x, y) -> map.screen(x, y) }
    }
}

/**
 * The shape after dragging [handle] by [dx], [dy] (the photo's 0–1; the finger at [x], [y]):
 * a radial's centre moves it, its handles set the radii; a linear's ends move one by one, its middle both.
 */
fun draggedShape(kind: String, start: JSONObject, handle: Int, dx: Double, dy: Double, x: Double, y: Double, w: Double, h: Double, long: Double): JSONObject {
    val s = JSONObject(start.toString())
    fun move(key: String) {
        val (px, py) = pt(start, key)
        s.put(key, JSONObject().put("x", px + dx).put("y", py + dy))
    }
    when (kind) {
        "radial" -> when (handle) {
            0 -> move("center")
            else -> {
                val c = pt(start, "center")
                // the finger's distance from the centre in long-edge units, along the handle's axis
                val ux = (x - c.first) * w / long
                val uy = (y - c.second) * h / long
                val a = Math.toRadians(start.optDouble("angle", 0.0))
                val along = if (handle == 1) ux * cos(a) + uy * sin(a) else -ux * sin(a) + uy * cos(a)
                s.put(if (handle == 1) "rx" else "ry", kotlin.math.abs(along).coerceIn(0.01, 2.0))
            }
        }
        else -> when (handle) {
            0 -> move("start")
            1 -> move("end")
            else -> {
                move("start")
                move("end")
            }
        }
    }
    return s
}

/** A radial's ellipse or a linear's three lines, with their handles. */
private fun DrawScope.drawShape(kind: String, s: JSONObject, map: ViewMap, g: ViewGeometry) {
    val white = Color.White
    val handleR = 7.dp.toPx()
    when (kind) {
        "radial" -> {
            val c = pt(s, "center")
            val (rx, ry, a) = Triple(s.optDouble("rx", 0.2), s.optDouble("ry", 0.15), s.optDouble("angle", 0.0))
            val path = Path()
            for (i in 0..72) {
                val t = Math.toRadians(i * 5.0)
                val (x, y) = around(c, rx * cos(t), ry * sin(t), a, g.width, g.height)
                val p = map.screen(x, y)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.Black.copy(alpha = 0.5f), style = Stroke(3.dp.toPx()))
            drawPath(path, white, style = Stroke(1.5.dp.toPx()))
            shapeHandles(kind, s, map, g).forEachIndexed { i, h ->
                drawCircle(if (i == 0) white else Color.Black.copy(alpha = 0.6f), handleR, h)
                drawCircle(white, handleR, h, style = Stroke(2.dp.toPx()))
            }
        }
        "linear" -> {
            val (a, b) = pt(s, "start") to pt(s, "end")
            val pa = map.screen(a.first, a.second)
            val pb = map.screen(b.first, b.second)
            val dir = pb - pa
            val len = hypot(dir.x, dir.y).coerceAtLeast(1f)
            val normal = Offset(-dir.y / len, dir.x / len) * (size.width + size.height)
            val mid = (pa + pb) / 2f
            drawLine(white, pa - normal, pa + normal, 1.5.dp.toPx())
            drawLine(white.copy(alpha = 0.7f), mid - normal, mid + normal, 1.dp.toPx())
            drawLine(white, pb - normal, pb + normal, 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            listOf(pa, pb, mid).forEach { h ->
                drawCircle(Color.Black.copy(alpha = 0.6f), handleR, h)
                drawCircle(white, handleR, h, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

private operator fun Offset.times(f: Float) = Offset(x * f, y * f)

/** The hues round the tint's wheel at full strength, from the right going clockwise on screen (hue 360 − angle). */
private val tintWheel: List<Color> = (0..12).map { i -> Color.hsv(((360 - i * 30) % 360).toFloat(), 1f, 1f) }

/**
 * A mask's colour, as the reference's: a swatch under the Color sliders (empty while there's none)
 * that tints the masked area; tapped, a wheel opens under it (its hue round it, its strength out from
 * the middle; one step a drag) with Clear to take the colour off.
 */
@Composable
private fun MaskTint(hue: Double, sat: Double, onChange: (Double, Double) -> Unit, onDone: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Color", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Canvas(Modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).border(1.dp, Lc.panelButtonBorder, RoundedCornerShape(4.dp))) {
                if (sat > 0.0) {
                    drawRect(Color.hsv(hue.toFloat().mod(360f), (sat / 100.0).toFloat().coerceIn(0f, 1f), 1f))
                } else {
                    // none: a line across the empty swatch
                    drawLine(Lc.panelTextSoft, Offset(size.width, 0f), Offset(0f, size.height), 1.5.dp.toPx())
                }
            }
        }
        if (open) {
            val change by rememberUpdatedState(onChange)
            val done by rememberUpdatedState(onDone)
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(
                    Modifier
                        .size(160.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val r = size.width / 2.0
                                fun pick(at: Offset) {
                                    val (h, s) = wheelValue(at.x - r, at.y - r, r)
                                    change(h, s)
                                }
                                pick(down.position)
                                do {
                                    val event = awaitPointerEvent()
                                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.pressed) {
                                        c.consume()
                                        pick(c.position)
                                    }
                                } while (c.pressed)
                                done()
                            }
                        },
                ) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.sweepGradient(tintWheel), r)
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
                    if (sat > 0.0) {
                        val a = Math.toRadians(hue)
                        val d = (sat / 100.0 * r).toFloat()
                        val at = center + Offset((cos(a) * d).toFloat(), (-sin(a) * d).toFloat())
                        drawCircle(Color.White, 7.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                    }
                }
                Spacer(Modifier.weight(1f))
                PanelButton("Clear", LcIcon.Close, enabled = sat > 0.0) {
                    change(hue, 0.0)
                    done()
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- callout menu, rename

/**
 * A menu as the reference's masks column opens one beside a ⋮: its grey with a 1 dp lighter edge
 * and 5 dp corners, 10 dp to the left of the button, level with it (kept on the screen), a point on
 * its right side toward the button.
 */
@Composable
fun CalloutMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left - gap - popupContentSize.width).coerceAtLeast(0)
                val y = (anchorBounds.center.y - popupContentSize.height / 2).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // as wide as its widest row (at least 195 dp), the rows filling it
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 195.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Lc.menu)
                    .border(1.dp, Lc.menuEdge, RoundedCornerShape(5.dp))
                    .padding(vertical = 1.dp),
                content = content,
            )
            // the point, its base over the menu's edge
            Canvas(Modifier.offset(x = (-1).dp).size(width = 9.dp, height = 16.dp)) {
                val point = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(point, Lc.menu)
                val edge = Lc.menuEdge
                val w = 1.dp.toPx()
                drawLine(edge, Offset(0f, 0f), Offset(size.width, size.height / 2), w)
                drawLine(edge, Offset(size.width, size.height / 2), Offset(0f, size.height), w)
            }
        }
    }
}

/** The line between a [CalloutMenu]'s groups: 1 dp, 9 dp in from each side. */
@Composable
fun CalloutDivider() = Box(Modifier.padding(horizontal = 9.dp).fillMaxWidth().height(1.dp).background(Lc.menuEdge))

/** Duplicate & Invert Mask: the invert icon over a square with a + at its corner. */
fun DrawScope.drawDuplicateInvertIcon(tint: Color) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    drawRect(tint, Offset(s * 0.08f, s * 0.08f), Size(s * 0.6f, s * 0.6f), style = Stroke(w))
    drawRect(tint, Offset(s * 0.28f, s * 0.28f), Size(s * 0.58f, s * 0.5f))
    drawCircle(Lc.menu, s * 0.14f, Offset(s * 0.5f, s * 0.53f))
    val c = Offset(s * 0.78f, s * 0.78f)
    drawCircle(Lc.menu, s * 0.2f, c)
    drawLine(tint, Offset(c.x - s * 0.14f, c.y), Offset(c.x + s * 0.14f, c.y), w)
    drawLine(tint, Offset(c.x, c.y - s * 0.14f), Offset(c.x, c.y + s * 0.14f), w)
}

/** A row of [CalloutMenu]: 48 dp, its icon 13 dp in, its words at 47 dp; faint while it can't be used. */
@Composable
fun CalloutMenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit, icon: DrawScope.(Color) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.3f)
            .padding(start = 13.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(22.dp)) { icon(Lc.menuIcon) }
        Spacer(Modifier.width(12.4.dp))
        Text(text, color = Lc.menuText, fontFamily = Inter, fontSize = 16.sp, maxLines = 1)
    }
}

/** An eye, struck through when [struck] (Hide; open: Show). */
fun DrawScope.drawEye(tint: Color, struck: Boolean) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    val eye = Path().apply {
        moveTo(s * 0.06f, s * 0.5f)
        quadraticTo(s * 0.5f, s * 0.08f, s * 0.94f, s * 0.5f)
        quadraticTo(s * 0.5f, s * 0.92f, s * 0.06f, s * 0.5f)
        close()
    }
    drawPath(eye, tint, style = Stroke(w))
    drawCircle(tint, s * 0.15f, center, style = Stroke(w))
    if (struck) drawLine(tint, Offset(s * 0.14f, s * 0.12f), Offset(s * 0.86f, s * 0.88f), w)
}

/** Rename: an A and a text cursor. */
fun DrawScope.drawRenameIcon(tint: Color) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    drawLine(tint, Offset(s * 0.06f, s * 0.86f), Offset(s * 0.34f, s * 0.12f), w)
    drawLine(tint, Offset(s * 0.34f, s * 0.12f), Offset(s * 0.62f, s * 0.86f), w)
    drawLine(tint, Offset(s * 0.17f, s * 0.6f), Offset(s * 0.51f, s * 0.6f), w)
    drawLine(tint, Offset(s * 0.86f, s * 0.06f), Offset(s * 0.86f, s * 0.94f), w)
}

/** Duplicate: a square behind another with a + in it. */
fun DrawScope.drawDuplicateIcon(tint: Color) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    val back = Path().apply {
        moveTo(s * 0.26f, s * 0.08f)
        lineTo(s * 0.08f, s * 0.08f)
        lineTo(s * 0.08f, s * 0.66f)
        lineTo(s * 0.26f, s * 0.66f)
    }
    drawPath(back, tint, style = Stroke(w))
    drawLine(tint, Offset(s * 0.08f, s * 0.08f), Offset(s * 0.66f, s * 0.08f), w)
    drawLine(tint, Offset(s * 0.66f, s * 0.08f), Offset(s * 0.66f, s * 0.26f), w)
    drawRect(tint, Offset(s * 0.3f, s * 0.3f), Size(s * 0.62f, s * 0.62f))
    val c = Offset(s * 0.61f, s * 0.61f)
    drawLine(Lc.menu, Offset(c.x - s * 0.17f, c.y), Offset(c.x + s * 0.17f, c.y), w * 1.2f)
    drawLine(Lc.menu, Offset(c.x, c.y - s * 0.17f), Offset(c.x, c.y + s * 0.17f), w * 1.2f)
}

/** The trash can (Delete). */
fun DrawScope.drawTrashIcon(tint: Color) {
    val s = size.minDimension
    val w = 1.6.dp.toPx()
    drawLine(tint, Offset(s * 0.1f, s * 0.2f), Offset(s * 0.9f, s * 0.2f), w * 1.3f)
    drawLine(tint, Offset(s * 0.38f, s * 0.08f), Offset(s * 0.62f, s * 0.08f), w)
    val can = Path().apply {
        moveTo(s * 0.18f, s * 0.24f)
        lineTo(s * 0.26f, s * 0.94f)
        lineTo(s * 0.74f, s * 0.94f)
        lineTo(s * 0.82f, s * 0.24f)
    }
    drawPath(can, tint, style = Stroke(w))
    for (x in listOf(0.38f, 0.5f, 0.62f)) drawLine(tint, Offset(s * x, s * 0.36f), Offset(s * x, s * 0.82f), w)
}

private val dialogRule = Color(0xFF393939)
private val fieldFill = Color(0xFF0E0E0E)
private val fieldLabel = Color(0xFFCDCDCD)
private val cancelEdge = Color(0xFFB0B0B0)

/**
 * The reference's Rename for a mask or a part: 320 dp of its grey, "Rename" in bold over a rule,
 * "Mask Name" over the field (the name chosen, the keyboard up, an ✕ that clears it), then Cancel
 * (outlined) and OK (blue) as pills; OK with a blank name does nothing.
 */
@Composable
fun RenameMaskDialog(initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Lc.dialog)
                .padding(start = 24.5.dp, end = 24.dp, top = 24.dp, bottom = 21.8.dp),
        ) {
            Text("Rename", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(dialogRule))
            Spacer(Modifier.height(20.dp))
            Text("Mask Name", color = fieldLabel, fontFamily = Inter, fontSize = 15.sp)
            Spacer(Modifier.height(5.9.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(42.3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fieldFill)
                    .border(2.dp, Lc.primaryBlue, RoundedCornerShape(4.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    field,
                    onValueChange = { field = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 16.sp),
                    cursorBrush = SolidColor(Lc.primaryBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (field.text.isNotBlank()) onDone(field.text) }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                BarButton(MdIcon.Close, "Clear", size = 36.dp, iconSize = 20.dp, tint = Lc.panelText) { field = TextFieldValue("") }
            }
            Spacer(Modifier.height(32.1.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .size(width = 91.dp, height = 39.9.dp)
                        .clip(RoundedCornerShape(50))
                        .border(1.5.dp, cancelEdge, RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = cancelEdge, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Spacer(Modifier.width(15.6.dp))
                Box(
                    Modifier
                        .size(width = 90.dp, height = 39.9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Lc.primary)
                        .clickable(role = Role.Button) { if (field.text.isNotBlank()) onDone(field.text) },
                    contentAlignment = Alignment.Center,
                ) { Text("OK", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
