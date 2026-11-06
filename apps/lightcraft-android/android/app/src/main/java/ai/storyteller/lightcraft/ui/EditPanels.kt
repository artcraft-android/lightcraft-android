package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.Choice
import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.PhotoFlag
import ai.storyteller.lightcraft.core.PresetInfo
import ai.storyteller.lightcraft.core.ProfileEntry
import ai.storyteller.lightcraft.core.SliderTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.launch

/** The colour mixer's eight bands, as the engine names them. */
val bandIds = listOf("red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta")

/**
 * A slider list's height upright, as the reference app's sheet: about two and a half sliders under
 * the buttons. The sheet stays this tall; a longer list scrolls inside it (the reference's lists are
 * scroll views in a sheet of fixed height), so pulling it up moves the sliders, not the sheet.
 */
val SheetPeek = 184.dp

/** The editor's sheet height upright; none (sideways, beside the photo): lists fill the panel. */
val LocalSheetLimit = staticCompositionLocalOf<Dp?> { null }

/**
 * A list sized to its content, up to what's left of the panel's cap under its tabs and buttons (the
 * panel's own height is capped, as the reference's sheet), or filling the panel sideways.
 */
@Composable
fun Modifier.sheetList(): Modifier {
    LocalSheetLimit.current ?: return fillMaxSize()
    return fillMaxWidth()
}

/**
 * Sideways, how wide the open panel is, as the reference's: as wide as its content needs between
 * a slider's least (224 dp) and 320 dp: 320 for Light and Optics (their widest rows), the width of
 * their tabs for Effects and Detail, 306 for Presets, 250 for Remove; Crop 317 and Profiles 305
 * with their strip of Cancel and Apply; Curve, Mix and Grading take 320 dp and the columns' room
 * besides (the columns step aside for them).
 */
fun sidewaysPanelWidth(ed: EditorState): Dp = when (ed.tool) {
    MainTool.EDIT -> when {
        ed.sub != null -> 320.dp
        // Profiles, a mode: its strip of Cancel and Apply included
        ed.section == EditSection.PROFILES -> 305.dp
        ed.section == EditSection.LIGHT || ed.section == EditSection.OPTICS -> 320.dp
        ed.section == EditSection.EFFECTS -> 247.dp
        ed.section == EditSection.DETAIL -> 292.dp
        else -> 224.dp
    }
    MainTool.PRESETS -> 306.dp
    MainTool.CROP -> 317.dp
    MainTool.REMOVE -> 250.dp
    else -> 320.dp
}

/** A row of a panel: a slider (by control id) or a sub-title, in the order the desktop lists them. */
private sealed interface PanelRow

/** [shown]: the slider appears only then (the reference app shows a setting's details once its amount is set). */
private data class SliderRow(
    val id: String,
    val label: String? = null,
    val enabled: (EditorState) -> Boolean = { true },
    val shown: (EditorState) -> Boolean = { true },
) : PanelRow

private data class TitleRow(val text: String) : PanelRow

private fun EditorState.value(id: String) = control(id)?.value ?: 0.0

/** An Edit section's panel. */
@Composable
fun SectionSheet(vm: AppViewModel, ed: EditorState, section: EditSection, modifier: Modifier) {
    Column(modifier) {
        when (section) {
            EditSection.LIGHT -> if (ed.sub == "curve") CurvePanel(vm, ed) else LightPanel(vm, ed)
            EditSection.COLOR -> when (ed.sub) {
                "mix" -> MixPanel(vm, ed)
                "grading" -> GradingPanel(vm, ed)
                else -> ColorPanel(vm, ed)
            }
            EditSection.EFFECTS -> {
                Tabs(listOf("effects" to "Effects", "vignette" to "Vignette", "grain" to "Grain"), ed.effectsTab) { ed.effectsTab = it }
                EffectsPanel(vm, ed)
            }
            EditSection.DETAIL -> {
                Tabs(listOf("sharpening" to "Sharpening", "noise" to "Noise", "color" to "Color noise"), ed.detailTab) { ed.detailTab = it }
                DetailPanel(vm, ed)
            }
            EditSection.OPTICS -> OpticsPanel(vm, ed)
            EditSection.AUTO, EditSection.PROFILES -> {}
        }
    }
}

private val lightRows: List<PanelRow> =
    listOf("light.exposure", "light.contrast", "light.highlights", "light.shadows", "light.whites", "light.blacks").map { SliderRow(it) }

// ------------------------------------------------------------------ the panels' own buttons

/**
 * A panel's button, as the reference's (Curve, Grading, Mix): 36 dp, 5 dp corners, near black with a
 * 1 dp grey edge; its icon (18 dp) and name (15 sp), [pad] around and between them. With no [label]
 * (sideways, where the reference shortens Grading and Mix) just the icon.
 */
@Composable
fun LcButton(
    label: String?,
    icon: Glyph?,
    description: String = label.orEmpty(),
    textColor: Color = Lc.panelTextSoft,
    pad: Dp = 8.dp,
    iconContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(Lc.panelButton)
            .border(1.dp, Lc.panelButtonBorder, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (label == null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconContent != null) iconContent() else if (icon != null) GlyphIcon(icon, null, tint = textColor, size = 18.dp)
        if (label != null) {
            Spacer(Modifier.width(pad))
            Text(label, color = textColor, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** B&W, as the reference's: words only, at least 88 dp; on, a blue edge round a darker fill. */
@Composable
private fun BwButton(on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .height(36.dp)
            .widthIn(min = 88.dp)
            .clip(shape)
            .background(if (on) Color(0xFF1A1A1A) else Lc.panelButton)
            .border(1.dp, if (on) Lc.primaryBlue else Lc.panelButtonBorder, shape)
            .toggleable(on, role = Role.Switch) { onClick() }
            .semantics {
                contentDescription = "Black and white"
                stateDescription = if (on) "On" else "Off"
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("B & W", color = if (on) Color(0xFFE8E8E8) else Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, maxLines = 1)
    }
}

/** The Mix button's icon: a ring of muted hues, yellow at the right going round through green, blue and red. */
@Composable
fun HueRing(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.18f
        drawCircle(Brush.sweepGradient(mixRing, center), this.size.minDimension / 2 - stroke / 2, center, style = Stroke(stroke))
    }
}

private val mixRing = listOf(0xFFF4DB01, 0xFF9CC43A, 0xFF51AC8B, 0xFF3F7FB5, 0xFF41519D, 0xFF7B3F9A, 0xFFC92935, 0xFFE8772A, 0xFFF4DB01).map { Color(it) }

/**
 * A sub-panel's Done, as the reference's: 88 × 36 dp, words in capitals. Curve's is fainter (the
 * reference's own two styles).
 */
@Composable
private fun DoneButton(faint: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(88.dp, 36.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (faint) Lc.subPanelButton else Lc.panelButton)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("DONE", color = if (faint) Lc.panelTextDim else Color(0xFFB0B0B0), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.4.sp)
    }
}

/** Targeted adjustment's 36 dp button (Mix): on, it turns blue and a drag on the photo adjusts what's under the finger. */
@Composable
private fun TargetButton(on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (on) Lc.primaryBlue else Lc.panelButton)
            .toggleable(on, role = Role.Switch) { onClick() }
            .semantics { contentDescription = "Targeted adjustment" },
        contentAlignment = Alignment.Center,
    ) {
        LcIcon(LcIcon.TargetMove, null, tint = if (on) Color.White else Lc.panelTextDim, size = 26.dp)
    }
}

/** A sub-title in a list (Calibration's groups). */
@Composable
private fun SubTitle(text: String) {
    Text(
        text,
        color = Lc.panelTextDim,
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        modifier = Modifier.hiddenWhileDragging().padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun Slider(vm: AppViewModel, ed: EditorState, row: SliderRow) {
    val c = ed.control(row.id) ?: return
    DevelopSlider(
        c,
        onChange = { vm.onControlChange(c, it) },
        onFinished = vm::onControlChangeFinished,
        onReset = { vm.resetControl(c) },
        onClippingPreview = { active -> vm.setClippingPreview(ed, c.id, active) },
        label = row.label ?: c.label,
        enabled = row.enabled(ed),
    )
}

private fun LazyListScope.rows(vm: AppViewModel, ed: EditorState, rows: List<PanelRow>) {
    items(rows.filter { it !is SliderRow || it.shown(ed) }, key = { r -> if (r is SliderRow) r.id else "title:" + (r as TitleRow).text }) { r ->
        when (r) {
            is SliderRow -> Slider(vm, ed, r)
            is TitleRow -> SubTitle(r.text)
        }
    }
}

/**
 * A panel's list: [header] items (they scroll with it, as the reference app's buttons do), then
 * [rows]; [bottom] under the last, as the reference's lists end (24 dp for Light and Color).
 */
@Composable
private fun PanelList(vm: AppViewModel, ed: EditorState, rows: List<PanelRow>, top: Dp = 0.dp, bottom: Dp = 16.dp, header: LazyListScope.() -> Unit = {}) {
    LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(top = top, bottom = bottom)) {
        header()
        rows(vm, ed, rows)
    }
}

/** Choices drawn as pills in a row that scrolls (Mix's targeted adjustment: hue, saturation or luminance). */
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    LazyRow(Modifier.hiddenWhileDragging(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (id, label) -> PanelButton(label, active = id == selected) { onSelect(id) } }
    }
}

/**
 * Text tabs, as the reference's: centred and all as wide as the widest (its name and 16 dp each
 * side), 40 dp, 10 dp under the panel's top and 16 dp over its list (Effects, Detail); or from the
 * left, each its own width (12 dp each side), 59 dp (Presets); or centred, each its own width with
 * [tabPad] each side and the line [linePad] past its name (Crop's). The open one is lighter, a 2 dp
 * line under its name that slides to the next one picked (300 ms). Too wide for the panel, they scroll.
 */
@Composable
fun Tabs(
    options: List<Pair<String, String>>,
    selected: String,
    centred: Boolean = true,
    @Suppress("UNUSED_PARAMETER") minWidth: Dp = 0.dp,
    bottomPadding: Dp = if (centred) 16.dp else 0.dp,
    textSize: Float = 15.5f,
    tabPad: Dp? = null,
    linePad: Dp = 0.dp,
    onSelect: (String) -> Unit,
) {
    val pad = tabPad ?: if (centred) 16.dp else 12.dp
    val height = if (centred) 40.dp else 59.dp
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val at = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { at.animateTo(index.toFloat(), tween(300, easing = AccelerateDecelerate)) }
    // each tab's left and right edges (px), as laid out, for the line under the open one
    val edges = remember(options.size) { mutableStateListOf<Pair<Float, Float>>().apply { repeat(options.size) { add(0f to 0f) } } }
    BoxWithConstraints(Modifier.fillMaxWidth().hiddenWhileDragging().padding(top = if (centred) 10.dp else 0.dp, bottom = bottomPadding)) {
        val room = constraints.maxWidth
        Layout(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .height(height)
                .drawWithContent {
                    drawContent()
                    if (edges.isEmpty()) return@drawWithContent
                    val lo = floor(at.value).toInt().coerceIn(0, edges.lastIndex)
                    val hi = (lo + 1).coerceAtMost(edges.lastIndex)
                    val f = (at.value - lo).coerceIn(0f, 1f)
                    val l = edges[lo].first + (edges[hi].first - edges[lo].first) * f
                    val r = edges[lo].second + (edges[hi].second - edges[lo].second) * f
                    if (r > l) drawRect(Lc.panelText, Offset(l, size.height - 2.dp.toPx()), Size(r - l, 2.dp.toPx()))
                },
            content = {
                options.forEach { (id, label) ->
                    val on = id == selected
                    Box(Modifier.selectable(on, role = Role.Tab) { onSelect(id) }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (on) Lc.panelText else Lc.panelTextSoft, fontFamily = Inter, fontSize = textSize.sp, maxLines = 1, softWrap = false)
                    }
                }
            },
        ) { measurables, constraints ->
            val h = constraints.maxHeight
            // each name's own width, then the room either side of it: the reference's, or less when the row
            // would be wider than the panel (its font is narrower than ours), down to 8 dp
            val text = measurables.map { it.maxIntrinsicWidth(h) }
            val full = pad.roundToPx()
            val least = 8.dp.roundToPx()
            val widest = text.maxOrNull() ?: 0
            val equal = centred && tabPad == null && (widest + 2 * full) * text.size <= room
            val side = when {
                equal -> full
                text.sum() + 2 * full * text.size <= room -> full
                else -> ((room - text.sum()) / (2 * text.size.coerceAtLeast(1))).coerceIn(least, full)
            }
            val widths = text.map { (if (equal) widest else it) + 2 * side }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], h)) }
            val total = widths.sum()
            val width = max(total, if (centred) room else total)
            val start = if (centred) ((width - total) / 2).coerceAtLeast(0) else 0
            layout(width, h) {
                var x = start
                placeables.forEachIndexed { i, p ->
                    p.place(x, 0)
                    // the line under a tab spans it less its sides (all as wide as the widest name when they're even),
                    // or its name and [linePad] each side
                    val inner = if (equal) side else ((widths[i] - text[i]) / 2 - linePad.roundToPx()).coerceAtLeast(0)
                    val edge = (x + inner).toFloat() to (x + widths[i] - inner).toFloat()
                    if (i < edges.size && edges[i] != edge) edges[i] = edge
                    x += widths[i]
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Light

@Composable
private fun LightPanel(vm: AppViewModel, ed: EditorState) {
    PanelList(vm, ed, lightRows, bottom = 24.dp) {
        item(key = "buttons") {
            // Curve at the right, 10 dp from the panel's top and from the first slider
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                LcButton("Curve", LcIcon.CurveLine, textColor = Lc.panelText, pad = 10.dp) { vm.openCurve() }
            }
        }
    }
}

/**
 * Light › Curve's panel, as the reference's: its name and Done, then the channels evenly across
 * (all, red, green, blue, the parametric curve). Upright the curve is over the photo; sideways it
 * is here, under the channels. A double-tap on a channel sets it straight again.
 */
@Composable
private fun CurvePanel(vm: AppViewModel, ed: EditorState) {
    val sideways = LocalSheetLimit.current == null
    val curve = ed.curve
    Column(Modifier.fillMaxWidth().hiddenWhileDragging().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Curve", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp).weight(1f))
            DoneButton(faint = true) { ed.sub = null }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("master", "red", "green", "blue", "parametric").forEach { ch ->
                CurveChannelButton(ch, ch == ed.curveChannel, onReset = {
                    vm.setCurveChannel(ch)
                    vm.resetCurveChannel()
                }) { vm.setCurveChannel(ch) }
            }
        }
        if (sideways && curve != null) {
            // the parametric curve's split band takes 57 dp under its square
            Box(Modifier.padding(top = 20.dp).fillMaxWidth().aspectRatio(if (ed.curveChannel == "parametric") 0.84f else 1f)) {
                CurveOverlay(vm, ed, curve, Modifier.fillMaxSize(), inPanel = true)
            }
        }
    }
}

/**
 * A channel's 48 dp button, as the reference's: a 20 dp ring in its colour (all: grey), filled with
 * a white dot in its middle while open; the parametric curve's own glyph.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurveChannelButton(channel: String, selected: Boolean, onReset: () -> Unit, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(role = Role.Tab, onDoubleClick = onReset, onClick = onClick)
            .semantics {
                contentDescription = curveChannelName(channel)
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (channel == "parametric") {
            LcIcon(LcIcon.Parametric, null, tint = if (selected) Lc.panelText else curveButtonGrey, size = 26.dp, weight = 1.25f)
        } else {
            val color = curveButtonColor(channel)
            Canvas(Modifier.size(20.dp)) {
                val r = size.minDimension / 2
                if (selected) {
                    drawCircle(color, r)
                    drawCircle(Color.White, 3.dp.toPx())
                } else {
                    drawCircle(color, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Color

@Composable
private fun ColorPanel(vm: AppViewModel, ed: EditorState) {
    val bw = ed.state?.blackAndWhite == true
    // sideways the reference shortens the row: Grading and Mix as icons, "WB"
    val sideways = LocalSheetLimit.current == null
    // rendered files have a relative Temp slider (`wb.tempRel`), raws one in Kelvin (`wb.temp`)
    val temp = if (ed.control("wb.temp") != null) "wb.temp" else "wb.tempRel"
    PanelList(vm, ed, listOf(SliderRow(temp), SliderRow("wb.tint"), SliderRow("color.vibrance", enabled = { !bw }), SliderRow("color.saturation", enabled = { !bw })), bottom = 24.dp) {
        item(key = "buttons") {
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BwButton(bw) { vm.toggleBlackAndWhite() }
                Spacer(Modifier.weight(1f))
                LcButton(if (sideways) null else "Grading", LcIcon.Grading, description = "Grading") { ed.sub = "grading" }
                Spacer(Modifier.width(10.dp))
                LcButton(if (sideways) null else "Mix", null, description = "Mix", iconContent = { HueRing(17.dp) }) { ed.sub = "mix" }
            }
        }
        item(key = "wb") { WhiteBalanceRow(vm, ed, sideways) }
    }
}

/** "White balance" ("WB" sideways), the mode's list in the middle and the eyedropper at the right (tap it, then a neutral spot). */
@Composable
private fun WhiteBalanceRow(vm: AppViewModel, ed: EditorState, short: Boolean) {
    var open by remember { mutableStateOf(false) }
    val mode = ed.state?.wbMode
    // as the reference's: a rendered file's list is As Shot, Auto and Custom
    val modes = vm.wbModes.filter { ed.info.raw || it.id in renderedWbModes }
    Box(Modifier.fillMaxWidth().hiddenWhileDragging().padding(top = 8.dp).height(48.dp)) {
        Text(if (short) "WB" else "White balance", color = Lc.panelText, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
        Box(Modifier.align(Alignment.Center)) {
            Row(
                Modifier.height(39.dp).clip(RoundedCornerShape(4.dp)).clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(vm.wbModes.firstOrNull { it.id == mode }?.label ?: "As Shot", color = Lc.panelText, fontFamily = Inter, fontSize = 14.sp)
                Spacer(Modifier.width(3.dp))
                LcIcon(LcIcon.ChevronDown, null, tint = Lc.panelText, size = 16.dp, weight = 1.4f)
            }
            if (open) {
                WbList(modes, mode, onDismiss = { open = false }) { id ->
                    open = false
                    if (id != mode) vm.setWhiteBalance(id)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .toggleable(ed.pickingWb, role = Role.Switch) { ed.pickingWb = it }
                .semantics { contentDescription = "White balance selector" },
            contentAlignment = Alignment.Center,
        ) {
            MdIcon(MdIcon.Colorize, null, tint = if (ed.pickingWb) Lc.selected else Lc.panelTextSoft, size = 22.dp)
        }
    }
}

private val renderedWbModes = setOf("asShot", "auto", "custom")

/**
 * The white balance list as the reference's: over its button (the list's words where the button's
 * are), a shade lighter than the panel with a soft shadow, 48 dp rows of 15 sp words, the open mode
 * blue.
 */
@Composable
private fun WbList(modes: List<Choice>, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val inset = with(LocalDensity.current) { 10.dp.roundToPx() }
    Popup(popupPositionProvider = remember(inset) { OverAnchor(inset) }, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.widthIn(min = 112.dp).width(IntrinsicSize.Max).shadow(6.dp).background(Lc.wbList)) {
            modes.forEach { m ->
                val on = m.id == current
                Box(
                    Modifier.fillMaxWidth().height(48.dp).selectable(on, role = Role.RadioButton) { onPick(m.id) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(m.label, color = if (on) Lc.primary else Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A popup over its anchor: its top on the anchor's and [dx] in from its left, moved up or in to stay on the screen. */
private class OverAnchor(private val dx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
        (anchorBounds.left + dx).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
        anchorBounds.top.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
    )
}

// ------------------------------------------------------------------ Mix

/**
 * Color › Mix, as the reference's: its name (upright), targeted adjustment and Done; the eight
 * colours as rings (the open one a filled dot with a white middle; a double-tap sets a colour's
 * sliders back); then its hue, saturation and luminance. In black and white: the colours and their
 * one slider, how light each turns.
 */
@Composable
private fun MixPanel(vm: AppViewModel, ed: EditorState) {
    val bw = ed.state?.blackAndWhite == true
    val sideways = LocalSheetLimit.current == null
    val band = bandIds.getOrElse(ed.mixerBand) { "red" }
    val on = ed.targeting in listOf("hue", "sat", "lum")
    val rows = if (bw) {
        listOf(SliderRow("bw.$band", "Luminance"))
    } else {
        listOf(SliderRow("mixer.$band.hue", "Hue"), SliderRow("mixer.$band.sat", "Saturation"), SliderRow("mixer.$band.lum", "Luminance"))
    }
    PanelList(vm, ed, rows, bottom = 16.dp) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(top = 10.dp, end = 16.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sideways) Text(if (bw) "B&W Mix" else "Color Mix", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 26.dp))
                Spacer(Modifier.weight(1f))
                if (!bw) {
                    TargetButton(on) { vm.setTargeting(if (on) null else "sat") }
                    Spacer(Modifier.width(10.dp))
                }
                DoneButton {
                    vm.setTargeting(null)
                    ed.sub = null
                }
            }
        }
        item(key = "bands") {
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(start = 16.dp, end = 16.dp, top = 10.dp).height(53.5.dp)) {
                Lc.bandColors.forEachIndexed { i, color ->
                    MixDot(color, bandIds[i], selected = i == ed.mixerBand, modifier = Modifier.weight(1f).fillMaxHeight(), onReset = {
                        ed.mixerBand = i
                        val keys = if (bw) listOf("bw.${bandIds[i]}") else listOf("hue", "sat", "lum").map { "mixer.${bandIds[i]}.$it" }
                        keys.mapNotNull { ed.control(it) }.forEach { vm.resetControl(it) }
                    }) { ed.mixerBand = i }
                }
            }
        }
        if (on) {
            item(key = "target") { Choices(listOf("hue" to "Hue", "sat" to "Saturation", "lum" to "Luminance"), ed.targeting) { vm.setTargeting(it) } }
        }
    }
}

/** One of the mixer's colours: a 20 dp ring, or (open) a filled dot with a 6 dp white middle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixDot(color: Color, name: String, selected: Boolean, modifier: Modifier, onReset: () -> Unit, onClick: () -> Unit) {
    Box(
        modifier
            .combinedClickable(role = Role.RadioButton, onDoubleClick = onReset, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        // the reference draws its dot 19 dp down its cell, not in the cell's middle
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.padding(top = 9.dp).size(20.dp)) {
            val r = size.minDimension / 2
            if (selected) {
                drawCircle(color, r)
                drawCircle(Color.White, 3.dp.toPx())
            } else {
                drawCircle(color, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

// ------------------------------------------------------------------ Grading

/** Colour grading's ranges, as the reference's row: shadows, midtones, highlights, then (set apart) global. */
private val gradingRanges = listOf("shadows" to "Shadows", "midtones" to "Midtones", "highlights" to "Highlights", "global" to "Global")

/**
 * Color › Grading, as the reference's: its name (upright) and Done; the ranges as icons; the open
 * range's wheel with its neighbours dim at the sides (tap one, or swipe, to move to it); the range's
 * name, its menu (reset, copy, paste) and a chevron that opens its hue and saturation as sliders
 * (closed, they're read out); its luminance; then, set apart, blending and balance (not for global).
 */
@Composable
private fun GradingPanel(vm: AppViewModel, ed: EditorState) {
    val sideways = LocalSheetLimit.current == null
    val range = ed.gradingRange
    val name = gradingRanges.firstOrNull { it.first == range }?.second ?: "Midtones"
    var open by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }
    val hue = ed.value("grading.$range.hue")
    val sat = ed.value("grading.$range.sat")
    val rows = buildList<PanelRow> {
        if (open) {
            add(SliderRow("grading.$range.hue", "Hue"))
            add(SliderRow("grading.$range.sat", "Saturation"))
        }
        add(SliderRow("grading.$range.lum", "Luminance"))
    }
    val overall = listOf(SliderRow("grading.blending"), SliderRow("grading.balance"))
    LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(top = 10.dp, end = 16.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!sideways) Text("Color Grading", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp))
                Spacer(Modifier.weight(1f))
                DoneButton { ed.sub = null }
            }
        }
        item(key = "ranges") {
            Row(Modifier.fillMaxWidth().hiddenWhileDragging().padding(top = 10.dp).height(48.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                gradingRanges.forEach { (id, label) ->
                    if (id == "global") Box(Modifier.padding(horizontal = 5.dp).size(1.dp, 13.dp).background(Color(0xFF6A6A6A)))
                    val edited = listOf("hue", "sat", "lum").any { k -> ed.control("grading.$id.$k")?.isChanged == true }
                    RangeButton(id, label, selected = id == range, edited = edited) { ed.gradingRange = id }
                }
            }
        }
        item(key = "wheels") {
            WheelCarousel(vm, ed, range) { ed.gradingRange = it }
        }
        item(key = "info") {
            Column(Modifier.fillMaxWidth().hiddenWhileDragging()) {
                // the range's name on the wheel's foot, its menu and chevron hanging from the same line
                Row(Modifier.fillMaxWidth().height(33.dp), verticalAlignment = Alignment.Top) {
                    Text(name, color = Lc.panelText, fontFamily = Inter, fontSize = 14.sp, lineHeight = 18.5.sp, modifier = Modifier.padding(start = 16.dp))
                    Box {
                        Box(Modifier.size(48.dp, 33.dp).clickable(role = Role.Button) { menu = true }.semantics { contentDescription = "$name options" }, contentAlignment = Alignment.TopCenter) {
                            MdIcon(MdIcon.MoreHoriz, null, tint = Lc.panelTextSoft, size = 24.dp, modifier = Modifier.offset(y = (-3).dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.menu) {
                            MenuItem("Reset") {
                                menu = false
                                listOf("hue", "sat", "lum").mapNotNull { ed.control("grading.$range.$it") }.forEach { vm.resetControl(it) }
                            }
                            MenuItem("Reset All") {
                                menu = false
                                vm.resetSectionNamed("grading")
                            }
                            MenuItem("Copy") {
                                menu = false
                                copied = Triple(hue, sat, ed.value("grading.$range.lum"))
                            }
                            copied?.let { (h, s, l) ->
                                MenuItem("Paste") {
                                    menu = false
                                    vm.dragWheel(range, h, s)
                                    vm.endWheel()
                                    ed.control("grading.$range.lum")?.let {
                                        vm.onControlChange(it, l)
                                        vm.onControlChangeFinished()
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val turn by animateFloatAsState(if (open) -90f else 0f, tween(200), label = "chevron")
                    Box(
                        Modifier.padding(end = 8.dp).size(40.dp, 33.dp).toggleable(open, role = Role.Switch) { open = it }
                            .semantics { contentDescription = "Hue and saturation sliders" },
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LcIcon(LcIcon.ChevronLeft, null, tint = Lc.panelTextSoft, size = 22.dp, weight = 1.4f, modifier = Modifier.rotate(turn))
                    }
                }
                if (!open) {
                    Row(Modifier.padding(start = 16.dp, top = 1.5.dp, bottom = 16.dp).height(18.5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Hue", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 14.sp)
                        Text(hue.toInt().toString(), color = Lc.panelText, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp, end = 10.dp))
                        Text("Sat", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 14.sp)
                        Text(sat.toInt().toString(), color = Lc.panelText, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        rows(vm, ed, rows)
        if (range != "global") {
            item(key = "divider") { Box(Modifier.fillMaxWidth().hiddenWhileDragging().padding(horizontal = 16.dp, vertical = 9.5.dp).height(1.dp).background(Lc.sliderTrackEdge)) }
            rows(vm, ed, overall)
        }
    }
}

/** A range's 48 dp icon: a disc of its tone (global: half dark, half light) in a ring, white and thicker while open; edited, a mark under it. */
@Composable
private fun RangeButton(id: String, label: String, selected: Boolean, edited: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2
            val ring = if (selected) 3.dp.toPx() else 1.5.dp.toPx()
            val inner = r - ring
            when (id) {
                "shadows" -> drawCircle(Color.Black, inner)
                "midtones" -> drawCircle(Color(0xFF5A5A5A), inner)
                "highlights" -> drawCircle(Color(0xFFE3E3E3), inner)
                else -> {
                    drawCircle(Color(0xFF666666), inner)
                    drawArc(Color(0xFFE3E3E3), -90f, 180f, true, center - Offset(inner, inner), Size(inner * 2, inner * 2))
                }
            }
            drawCircle(if (selected) Color.White else Color(0xFFB9B9B9), r - ring / 2, style = Stroke(ring))
        }
        if (edited) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp, 2.dp).background(Lc.toolMark, RoundedCornerShape(1.dp)))
    }
}

/**
 * The ranges' wheels in a row that pages: the open one (230 dp, its wheel 180 dp) in the middle, its
 * neighbours dim at the sides, 300 dp apart. A tap on a neighbour, or a swipe, opens it.
 */
@Composable
private fun WheelCarousel(vm: AppViewModel, ed: EditorState, range: String, onRange: (String) -> Unit) {
    val ids = gradingRanges.map { it.first }
    val index = ids.indexOf(range).coerceAtLeast(0)
    val at = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { at.animateTo(index.toFloat(), tween(300, easing = AccelerateDecelerate)) }
    val sideways = LocalSheetLimit.current == null
    // the neighbours peek in only as far as the panel's edges (sideways they'd run over the photo's side)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 9.dp).height(230.dp).clipToBounds()) {
        // the reference's pages: 298.6 dp apart upright, closer in the narrow panel sideways (a neighbour still peeks in)
        val spacing = if (sideways) 226.6.dp else 298.6.dp
        ids.forEachIndexed { i, id ->
            val shift = (i - at.value)
            if (kotlin.math.abs(shift) > 1.6f) return@forEachIndexed
            val dim = (1f - kotlin.math.abs(shift)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = spacing * shift)
                    .size(230.dp)
                    .then(if (i == index) Modifier else Modifier.clickable(role = Role.Tab) { onRange(id) }),
                contentAlignment = Alignment.Center,
            ) {
                GradingWheel(
                    vm,
                    id,
                    ed.value("grading.$id.hue"),
                    ed.value("grading.$id.sat"),
                    active = i == index,
                    alpha = 0.3f + 0.7f * dim,
                    onSwipe = { dir -> ids.getOrNull(index + dir)?.let(onRange) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Effects, Detail, Optics

@Composable
private fun EffectsPanel(vm: AppViewModel, ed: EditorState) {
    when (ed.effectsTab) {
        "vignette" -> {
            val on = { e: EditorState -> e.value("vignette.amount") != 0.0 }
            PanelList(vm, ed, listOf(SliderRow("vignette.amount", "Amount")) + listOf("vignette.midpoint", "vignette.feather", "vignette.roundness", "vignette.highlights").map { SliderRow(it, shown = on) })
        }
        "grain" -> {
            val on = { e: EditorState -> e.value("grain.amount") != 0.0 }
            PanelList(vm, ed, listOf(SliderRow("grain.amount", "Amount")) + listOf("grain.size", "grain.roughness").map { SliderRow(it, shown = on) })
        }
        else -> PanelList(vm, ed, listOf("effects.texture", "effects.clarity", "effects.dehaze").map { SliderRow(it) })
    }
}

@Composable
private fun DetailPanel(vm: AppViewModel, ed: EditorState) {
    // each tab's amount first; its details once the amount is set
    fun tab(amount: String, details: List<String>): List<PanelRow> {
        val set = { e: EditorState -> e.value(amount) > 0.0 }
        return listOf(SliderRow(amount, "Amount")) + details.map { SliderRow(it, shown = set) }
    }
    val rows = when (ed.detailTab) {
        "noise" -> tab("detail.nrLuminance", listOf("detail.nrDetail", "detail.nrContrast"))
        "color" -> tab("detail.nrColor", listOf("detail.nrColorDetail", "detail.nrColorSmoothness"))
        else -> tab("detail.sharpenAmount", listOf("detail.sharpenRadius", "detail.sharpenDetail", "detail.sharpenMasking"))
    }
    PanelList(vm, ed, rows)
}

/**
 * Optics, as the reference's: its two switches; with lens corrections on and the file's lens data
 * found, how much of the profile's distortion and vignetting to correct. (The manual lens
 * corrections are Crop › Geometry's, as the reference has them.)
 */
@Composable
private fun OpticsPanel(vm: AppViewModel, ed: EditorState) {
    val st = ed.state
    val lens = st?.lensCorrections == true
    val hasLens = st?.hasLensProfile == true
    val rows = if (lens && hasLens) listOf(SliderRow("optics.profileDistortion", "Distortion"), SliderRow("optics.profileVignetting", "Vignetting")) else emptyList()
    PanelList(vm, ed, rows, top = 4.dp, bottom = 20.dp) {
        item(key = "switches") {
            Column(Modifier.hiddenWhileDragging()) {
                SwitchRow("Remove Chromatic Aberration", st?.removeCa == true) { vm.setOptics("remove_ca", it, "Remove CA") }
                SwitchRow("Enable Lens Corrections", lens) { vm.setOptics("lens_profile", it, "Lens Corrections") }
            }
        }
    }
}

/** A 48 dp row: its label (14 sp) at left, a switch at right. */
@Composable
fun SwitchRow(label: String, checked: Boolean, inset: Dp = 16.dp, textSize: Float = 14f, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Lc.panelText, fontFamily = Inter, fontSize = textSize.sp, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
        Spacer(Modifier.width(12.dp))
        LcSwitch(checked)
    }
}

/** The switch's ring when off. */
private val switchRing = Color(0xFFB0B0B0)

/**
 * A switch as the reference app draws its own (sampled from its screens): an 18 dp ring the colour
 * of what's behind it on a 36 × 18 dp pill; on, both turn light blue and the ring slides to the
 * end. Only drawn: the row it sits in takes the taps (and says it's a switch).
 */
@Composable
fun LcSwitch(checked: Boolean, modifier: Modifier = Modifier, behind: Color = Lc.bar) {
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(150), label = "switch")
    Canvas(modifier.size(36.dp, 18.dp)) {
        val r = size.height / 2
        drawRoundRect(lerp(Lc.switchTrack, Lc.highlight, on), cornerRadius = CornerRadius(r))
        val centre = Offset(r + (size.width - size.height) * on, r)
        val ring = 2.dp.toPx()
        drawCircle(behind, r - ring / 2, centre)
        drawCircle(lerp(switchRing, Lc.highlight, on), r - ring / 2, centre, style = Stroke(ring))
    }
}

// ------------------------------------------------------------------ Presets

/** A synthetic 0–200 % slider for the strength of the last applied preset. */
private fun presetAmount(value: Float) = DevelopControl("preset.amount", "Amount", "", "", 0.0, 200.0, 100.0, 1.0, 0u, value.toDouble(), SliderTrack.Plain)

/**
 * Presets, as the reference's: tabs (LightCraft's, yours) over a list of groups (a preview of this
 * photo with the group's first preset, the group's name and size, a chevron); a group opens to its
 * presets as tiles of this photo, each named over its foot: tap one to apply it, then its amount.
 */
@Composable
fun PresetsSheet(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val shown = remember(vm.presets, ed.presetTab, vm.hiddenPresetGroups) {
        vm.presets.filter { (if (ed.presetTab == "yours") !it.builtin else it.builtin) && it.group !in vm.hiddenPresetGroups }
    }
    val groups = remember(shown) { shown.groupBy { it.group } }
    var group by remember(ed.presetTab) { mutableStateOf<String?>(null) }
    var amount by remember(ed.lastPreset) { mutableStateOf(ed.presetAmount) }
    Column(modifier) {
        val open = group?.let { g -> groups[g]?.let { g to it } }
        if (open == null) {
            Tabs(listOf("lightcraft" to "LightCraft", "yours" to "Yours"), ed.presetTab, centred = false, textSize = 15.5f) { ed.presetTab = it }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.panelButtonBorder))
            if (shown.isEmpty()) {
                Text(
                    if (ed.presetTab == "yours") "No presets yet" else "No presets",
                    color = Lc.panelTextDim,
                    fontFamily = Inter,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.sheetList(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                items(groups.entries.toList(), key = { it.key }) { (name, presets) ->
                    PresetGroupRow(vm, ed, name, presets) { group = name }
                }
            }
        } else {
            val (name, presets) = open
            Row(Modifier.fillMaxWidth().height(59.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp, 59.dp).clickable(role = Role.Button) { group = null }.semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) {
                    LcIcon(LcIcon.Back, null, tint = Lc.panelText, size = 22.dp, weight = 1.2f)
                }
                Text(name, color = Lc.panelText, fontFamily = Inter, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.panelButtonBorder))
            ed.lastPreset?.takeIf { p -> presets.any { it.id == p.id } }?.let { p ->
                DevelopSlider(
                    presetAmount(amount),
                    onChange = { amount = it.toFloat() },
                    onFinished = { vm.setPresetAmount(amount) },
                    onReset = {
                        amount = 100f
                        vm.setPresetAmount(100f)
                    },
                    label = "${p.name} amount",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (LocalSheetLimit.current == null) {
                // sideways the reference shows two columns well in from the panel's sides, the names on the tiles
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(presets, key = { it.id }) { p -> PresetTile(vm, ed, p) }
                }
            } else {
                // upright, its strip of 96 dp squares, each name under its tile
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.6.dp),
                ) {
                    items(presets, key = { it.id }) { p -> PresetFilmTile(vm, ed, p) }
                }
            }
        }
    }
}

/** A group of presets: a 53 dp preview, its name, how many it holds and a chevron, 59 dp high. */
@Composable
private fun PresetGroupRow(vm: AppViewModel, ed: EditorState, name: String, presets: List<PresetInfo>, onOpen: () -> Unit) {
    val first = presets.firstOrNull()
    Row(Modifier.fillMaxWidth().height(59.dp).clickable(role = Role.Button, onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(start = 3.dp).size(53.dp).clip(RoundedCornerShape(4.dp)).background(Lc.cell)) {
            if (first != null) {
                val key = vm.presetKey(ed.info, first.id)
                val look by produceState(vm.peekLook(key), key) { vm.presetPreview(ed.info, first.id)?.let { value = it } }
                look?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(name, color = Lc.panelTextDim, fontFamily = Inter, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp))
        Text(presets.size.toString(), color = Lc.panelTextDim, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        LcIcon(LcIcon.ChevronRight, null, tint = Lc.chevron, size = 24.dp, weight = 1.3f, modifier = Modifier.padding(start = 10.dp))
    }
}

/** A preset upright, as the reference's strip: a 96 dp square of this photo with it (outlined while applied), its name under it in grey. */
@Composable
private fun PresetFilmTile(vm: AppViewModel, ed: EditorState, p: PresetInfo) {
    val key = vm.presetKey(ed.info, p.id)
    val look by produceState(vm.peekLook(key), key) { vm.presetPreview(ed.info, p.id)?.let { value = it } }
    val selected = ed.lastPreset?.id == p.id
    val shape = RoundedCornerShape(3.dp)
    Column(
        Modifier.width(96.dp).combinedClickable(role = Role.RadioButton, onLongClick = { if (!p.builtin) ed.presetHeld = p }) { vm.applyPreset(p) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(shape).background(Lc.cell).border(2.dp, if (selected) Lc.primaryBlue else Color.Transparent, shape)) {
            look?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(
            p.name,
            color = if (selected) Lc.panelText else Lc.panelTextDim,
            fontFamily = Inter,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

/** A preset sideways: this photo with it, its name over the tile's foot; the one applied is outlined. */
@Composable
private fun PresetTile(vm: AppViewModel, ed: EditorState, p: PresetInfo) {
    val key = vm.presetKey(ed.info, p.id)
    val look by produceState(vm.peekLook(key), key) { vm.presetPreview(ed.info, p.id)?.let { value = it } }
    val selected = ed.lastPreset?.id == p.id
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Lc.cell)
            .border(2.dp, if (selected) Lc.primaryBlue else Color.Transparent, shape)
            .combinedClickable(role = Role.RadioButton, onLongClick = { if (!p.builtin) ed.presetHeld = p }) { vm.applyPreset(p) },
    ) {
        look?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            p.name,
            color = Color.White,
            fontFamily = Inter,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 6f)),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 6.dp, bottom = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ Profiles

/**
 * Profiles (a mode: Cancel goes back to the profile it started from), as the reference's: one strip
 * of 120 dp tiles of this photo, Favorites first and then each group, a line between groups; over it
 * the name of the group in view with a caret (its list jumps to a group) and More (which profiles it
 * shows, Manage Profiles, Import Profiles). Sideways, under "Profiles" and More, the group in view
 * and its count over the groups in a grid of two, a line between groups.
 */
@Composable
fun ProfilesSheet(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val current = ed.state?.profile
    val sections = profileSections(vm)
    // the slots: each group's profiles, then the line before the next group
    val slots = remember(sections) {
        sections.flatMapIndexed { i, (g, ps) -> ps.map { ProfileSlot(g, it) } + if (i < sections.lastIndex) listOf(ProfileSlot(g, null)) else emptyList() }
    }
    val sideways = LocalSheetLimit.current == null
    val strip = rememberLazyListState()
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // the group in view: the one most of whose first tile in view is in view
    val inView by remember(slots, sideways) {
        derivedStateOf {
            val index = if (sideways) {
                val info = grid.layoutInfo
                (info.visibleItemsInfo.firstOrNull { it.offset.y + it.size.height / 2 >= info.viewportStartOffset } ?: info.visibleItemsInfo.firstOrNull())?.index
            } else {
                val info = strip.layoutInfo
                (info.visibleItemsInfo.firstOrNull { it.offset + it.size / 2 >= info.viewportStartOffset } ?: info.visibleItemsInfo.firstOrNull())?.index
            }
            index?.let { slots.getOrNull(it)?.group }
        }
    }
    val group = inView ?: sections.firstOrNull()?.first.orEmpty()
    var menu by remember { mutableStateOf(false) }
    val more = @Composable {
        Box(
            Modifier
                .padding(end = 3.dp)
                .size(48.dp, 30.dp)
                .clickable(role = Role.Button) { ed.profileSheet = "options" }
                .semantics { contentDescription = "More profile options" },
            contentAlignment = Alignment.Center,
        ) {
            MdIcon(MdIcon.MoreVert, null, tint = profileMore, size = 24.dp)
        }
    }
    Column(modifier) {
        if (sideways) {
            // the mode's name stands here sideways, its bar a strip without one
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Profiles", color = Lc.panelText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                more()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = if (sideways) 14.dp else 16.dp, end = if (sideways) 16.dp else 0.dp).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.DropdownList) { menu = true }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(group.ifEmpty { "Profiles" }, color = Lc.panelText, fontFamily = Inter, fontSize = 16.sp)
                    LcIcon(LcIcon.ChevronDown, null, tint = Lc.panelText, size = 20.dp, weight = 1.3f, modifier = Modifier.padding(start = 4.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Lc.menu) {
                    sections.forEach { (g, _) ->
                        MenuItem(g) {
                            menu = false
                            val at = slots.indexOfFirst { it.group == g }
                            if (at >= 0) scope.launch { if (sideways) grid.animateScrollToItem(at) else strip.animateScrollToItem(at) }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (sideways) {
                Text("${sections.firstOrNull { it.first == group }?.second?.size ?: 0}", color = Lc.panelText, fontFamily = Inter, fontSize = 12.sp)
            } else {
                more()
            }
        }
        if (current != null && current != "lc.color") ed.control("profile.amount")?.let { Slider(vm, ed, SliderRow("profile.amount")) }
        if (sideways) {
            // sideways: the reference's 90 dp tiles two by two down the panel, a line between groups
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = grid,
                contentPadding = PaddingValues(start = 33.dp, end = 23.dp, top = 14.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(21.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    slots,
                    key = { "${it.group}/${it.profile?.id ?: "|"}" },
                    span = { GridItemSpan(if (it.profile == null) maxLineSpan else 1) },
                ) { slot ->
                    val p = slot.profile
                    if (p == null) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp).height(2.dp).background(profileGroupLine))
                    } else {
                        ProfileTile(vm, ed, p, selected = p.id == current, favorite = p.id in vm.favoriteProfiles, tile = null)
                    }
                }
            }
        } else {
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 7.dp),
                state = strip,
                contentPadding = PaddingValues(horizontal = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(slots, key = { "${it.group}/${it.profile?.id ?: "|"}" }) { slot ->
                    val p = slot.profile
                    if (p == null) {
                        // the line between two groups
                        Box(Modifier.size(2.dp, 120.dp)) { Box(Modifier.padding(top = 17.dp).size(2.dp, 47.7.dp).background(profileGroupLine)) }
                    } else {
                        ProfileTile(vm, ed, p, selected = p.id == current, favorite = p.id in vm.favoriteProfiles)
                    }
                }
            }
        }
    }
}

/** A place in the profile strip: one of [group]'s profiles, or (null) the line after the group. */
private data class ProfileSlot(val group: String, val profile: ProfileEntry?)

/** The browser's groups, as the reference lists them: Favorites, then each group Manage Profiles shows, all as its More filters them. */
@Composable
private fun profileSections(vm: AppViewModel): List<Pair<String, List<ProfileEntry>>> {
    val filter = vm.profileFilter
    val hidden = vm.hiddenProfileGroups
    val favorites = vm.favoriteProfiles
    return remember(vm.profiles, filter, hidden, favorites) {
        val shown = vm.profiles.filter {
            when (filter) {
                "color" -> !it.monochrome()
                "bw" -> it.monochrome()
                else -> true
            }
        }
        val starred = shown.filter { it.id in favorites }
        (if (starred.isNotEmpty()) listOf(FAVORITES to starred) else emptyList()) +
            shown.filter { it.group !in hidden }.groupBy { it.group }.toList()
    }
}

/** A black and white profile (Monochrome and the B&W group), as the engine treats them (`is_bw`). */
private fun ProfileEntry.monochrome() = id == "lc.mono" || id.startsWith("lc.bw.")

/** The profiles' group of the ones starred. */
private const val FAVORITES = "Favorites"

private val profileGroupLine = Color(0xFF5F5F5F)
private val profileMore = Color(0xFF909090)

/**
 * A profile: this photo with it, [tile] square (null: as wide as its cell), its name in a band along
 * its foot (blue on the one in use). As the reference's, the one in use has a blue badge at its
 * corner, its star filled while it's a favourite (tap it to star or unstar it); another favourite
 * shows its star alone.
 */
@Composable
private fun ProfileTile(vm: AppViewModel, ed: EditorState, p: ProfileEntry, selected: Boolean, favorite: Boolean, tile: Dp? = 120.dp) {
    val key = vm.profileKey(ed.info, p.id)
    val look by produceState(vm.peekLook(key), key) { vm.profilePreview(ed.info, p.id)?.let { value = it } }
    Box(
        Modifier
            .then(if (tile != null) Modifier.size(tile) else Modifier.fillMaxWidth().aspectRatio(1f))
            .clip(RoundedCornerShape(3.dp))
            .background(Lc.cell)
            .selectable(selected, role = Role.RadioButton) { vm.setProfile(p.id) },
    ) {
        look?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp).background(if (selected) Lc.primaryBlue else Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(p.name, color = Color.White, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
                    .toggleable(favorite, role = Role.Checkbox) { vm.toggleFavoriteProfile(p.id) }
                    .semantics { contentDescription = "Favorite" },
                contentAlignment = Alignment.TopEnd,
            ) {
                Box(
                    Modifier.padding(top = 4.4.dp, end = 4.9.dp).size(17.5.dp).background(Lc.primaryBlue, RoundedCornerShape(2.5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    LcIcon(if (favorite) LcIcon.StarFilled else LcIcon.Star, null, tint = Color.White, size = 13.dp)
                }
            }
        } else if (favorite) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.4.dp, end = 4.9.dp).size(17.5.dp), contentAlignment = Alignment.Center) {
                LcIcon(LcIcon.StarFilled, null, tint = Color.Black.copy(alpha = 0.35f), size = 13.dp, modifier = Modifier.offset(0.6.dp, 0.6.dp))
                LcIcon(LcIcon.StarFilled, null, tint = Color.White, size = 13.dp)
            }
        }
    }
}

/**
 * The profile browser's More, as the reference's: a sheet of which profiles it shows (all, only the
 * colour ones, only the black and white ones; the choice blue), then Manage Profiles and Import
 * Profiles (`.cube` looks from the phone's files).
 */
@Composable
fun ProfileOptionsSheet(vm: AppViewModel, onManage: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    LcSheet(onDismiss, inset = 12.6.dp) {
        listOf("all" to "Show All Profiles", "color" to "Show Only Color", "bw" to "Show Only B&W").forEach { (id, label) ->
            val on = vm.profileFilter == id
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .selectable(on, role = Role.RadioButton) {
                        vm.filterProfiles(id)
                        onDismiss()
                    }
                    .padding(start = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioRing(on)
                Spacer(Modifier.width(38.dp))
                Text(label, color = if (on) Lc.primary else Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.sectionChosen))
        listOf("Manage Profiles" to onManage, "Import Profiles" to onImport).forEach { (label, action) ->
            Box(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = action).padding(start = 23.dp), contentAlignment = Alignment.CenterStart) {
                Text(label, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp)
            }
        }
    }
}

/** A radio button's ring as the reference draws it: 20 dp, grey; chosen, blue around a blue dot. */
@Composable
private fun RadioRing(on: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val ring = 2.dp.toPx()
        drawCircle(if (on) Lc.primary else radioOff, size.minDimension / 2 - ring / 2, style = Stroke(ring))
        if (on) drawCircle(Lc.primary, 5.dp.toPx())
    }
}

private val radioOff = Color(0xFF767676)

/**
 * Manage Profiles, as the reference's: a page under a blue bar (Cancel, its name, Done), each group
 * with how many profiles it has and a switch that shows it in the browser or hides it; Basic set
 * apart from the looks.
 */
@Composable
fun ManageProfilesPage(vm: AppViewModel, onDismiss: () -> Unit) {
    var hidden by remember { mutableStateOf(vm.hiddenProfileGroups) }
    val groups = remember(vm.profiles) { vm.profiles.groupBy { it.group }.toList() }
    LcOverlay(onDismiss, Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().background(Lc.wbList).windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().height(56.dp).background(Lc.primary), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.Close, "Cancel", size = 56.dp, tint = Color.White, onClick = onDismiss)
                Text("Manage Profiles", color = Color.White, fontFamily = Inter, fontSize = 19.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
                BarButton(MdIcon.Check, "Done", size = 56.dp, tint = Color.White) {
                    vm.hideProfileGroups(hidden)
                    onDismiss()
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.panelButtonBorder))
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEachIndexed { i, (g, ps) ->
                    item(key = g) {
                        val shown = g !in hidden
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .toggleable(shown, role = Role.Switch) { hidden = if (it) hidden - g else hidden + g }
                                .padding(start = 24.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g, color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${ps.size}", color = Lc.panelTextSoft, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
                            LcSwitch(shown, behind = Lc.wbList)
                        }
                    }
                    // Basic stands apart from the looks
                    if (i == 0 && groups.size > 1) item(key = "line") { Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.panelButtonBorder)) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Rate (Actions)

/** A panel's 36 dp round button with just an icon. */
@Composable
fun PanelIconButton(icon: LcIcon, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .border(1.dp, Lc.buttonBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        LcIcon(icon, null, tint = Lc.text, size = 20.dp)
    }
}

/** A 36 dp pill, icon and label (selected: the tool highlight): masks' parts and actions, Rate's flags. */
@Composable
fun PanelButton(label: String, icon: LcIcon? = null, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        Modifier
            .height(36.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clip(shape)
            .background(if (active) Color(0xFF1A1A1A) else Lc.panelButton)
            .border(1.dp, if (active) Lc.primaryBlue else Lc.panelButtonBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            LcIcon(icon, null, tint = Lc.panelTextSoft, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = if (active) Color(0xFFE8E8E8) else Lc.panelTextSoft, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
    }
}

/** A curve channel's button colour (the reference's: the mixer's red, green and blue; grey for all). */
private fun curveButtonColor(channel: String) = when (channel) {
    "red" -> Color(0xFFEC1E24)
    "green" -> Color(0xFF69BC45)
    "blue" -> Color(0xFF3953A3)
    else -> curveButtonGrey
}

private val curveButtonGrey = Color(0xFF999999)
