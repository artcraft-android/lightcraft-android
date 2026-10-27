package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.ImageSize
import ai.storyteller.lightcraft.core.PhotoFlag
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The editor, laid out as the reference app lays it out on a phone:
 * the photo on black under a slim top bar, then the open tool's panel, Edit's row of sections and
 * the row of tools. Sideways, the top bar spans only the photo, and the panel, the sections and the
 * tools stand as columns at its right.
 */
@Composable
fun EditorScreen(vm: AppViewModel, ed: EditorState, snackbar: SnackbarHostState) {
    var namingPreset by remember { mutableStateOf(false) }
    val createPreset = { namingPreset = true }
    // the export sheet, for sharing (true) or saving (false); Share first opens its sheet of ways, as the reference's
    var exportFor by remember { mutableStateOf<Boolean?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val onExport = { share: Boolean -> if (share) sharing = true else exportFor = false }
    // the slider being dragged: the editor clears around it (see [SliderDrag])
    val sliderDrag = remember { SliderDrag() }
    val view = LocalView.current
    DisposableEffect(ed.immersive) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (ed.immersive) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    Scaffold(containerColor = Lc.canvas, contentWindowInsets = WindowInsets(0), snackbarHost = { SnackbarHost(snackbar, Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = if (LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp) 0.dp else 132.dp)) { LcToast(it) } }) { padding ->
        CompositionLocalProvider(LocalSliderDrag provides sliderDrag) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // sideways whenever the screen is wider than tall, as the reference's
            if (maxWidth > maxHeight) SidewaysEditor(vm, ed, createPreset, onExport) else UprightEditor(vm, ed, createPreset, onExport)
        }
        }
    }
    OverlayHost(exportFor) { share -> ExportSheet(vm, ed, share) { exportFor = null } }
    if (sharing) {
        ShareSheet(
            onDismiss = { sharing = false },
            onShareTo = { vm.export(vm.exportChoice, share = true) },
            onExportAs = { exportFor = true },
            onSave = { exportFor = false },
        )
    }
    // the phone's files, for Import Profiles (kept while the picker is open, whatever the sheet does)
    val pickProfiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importProfiles(it) }
    OverlayHost(ed.viewOptions.takeIf { it }) { ViewOptionsSheet(vm) { ed.viewOptions = false } }
    OverlayHost(ed.organize.takeIf { it }) { OrganizeSheet(vm) { ed.organize = false } }
    OverlayHost(ed.versionsPage.takeIf { it }) { VersionsPage(vm, ed) { ed.versionsPage = false } }
    val pickPresets = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importPresets(it) }
    val uri = LocalUriHandler.current
    OverlayHost(ed.presetOptions.takeIf { it }) {
        PresetOptionsSheet(
            onCreate = {
                ed.presetOptions = false
                namingPreset = true
            },
            onManage = {
                ed.presetOptions = false
                ed.managePresets = true
            },
            onImport = {
                ed.presetOptions = false
                runCatching { pickPresets.launch(arrayOf("*/*")) }.onFailure { vm.tell("There's no app to pick files with.") }
            },
            onHelp = {
                ed.presetOptions = false
                runCatching { uri.openUri(HELP_URL) }
            },
        ) { ed.presetOptions = false }
    }
    OverlayHost(ed.managePresets.takeIf { it }) { ManagePresetsPage(vm) { ed.managePresets = false } }
    OverlayHost(ed.spotMenu.takeIf { it && ed.selectedSpot != null }) { SpotMenuSheet(vm, ed) { ed.spotMenu = false } }
    OverlayHost(ed.maskOptions.takeIf { it }) {
        MaskOptionsSheet(vm, onHelp = {
            ed.maskOptions = false
            runCatching { uri.openUri(HELP_URL) }
        }) { ed.maskOptions = false }
    }
    OverlayHost(ed.presetHeld) { p -> PresetManageSheet(vm, p) { ed.presetHeld = null } }
    if (ed.discardMasking) DiscardMaskingDialog(vm) { ed.discardMasking = false }
    OverlayHost(ed.copyPicker.takeIf { it }) {
        SettingsPickerPage("Copy Settings", vm.copyGroups, modified = { vm.modifiedGroups() }, onDismiss = { ed.copyPicker = false }) { groups ->
            ed.copyPicker = false
            vm.copySettings(groups)
        }
    }
    OverlayHost(ed.infoPage.takeIf { it }) { InfoPage(vm, ed) { ed.infoPage = false } }
    OverlayHost(ed.profileSheet) { sheet ->
        when (sheet) {
            "options" -> ProfileOptionsSheet(
                vm,
                onManage = { ed.profileSheet = "manage" },
                onImport = {
                    ed.profileSheet = null
                    runCatching { pickProfiles.launch(arrayOf("*/*")) }.onFailure { vm.tell("There's no app to pick files with.") }
                },
            ) { ed.profileSheet = null }
            "manage" -> ManageProfilesPage(vm) { ed.profileSheet = null }
        }
    }
    OverlayHost(ed.maskPicker) { picker ->
        when (picker) {
            "new" -> NewMaskSheet(vm, null) { ed.maskPicker = null }
            "add", "subtract" -> NewMaskSheet(vm, picker) { ed.maskPicker = null }
            "list" -> MasksSheet(vm, ed) { ed.maskPicker = null }
        }
    }
    OverlayHost(namingPreset.takeIf { it }) { CreatePresetPage(vm) { namingPreset = false } }
}

private val horizontalInsets: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

/**
 * Upright, as the reference's: the photo full-bleed on black, fitted between the status bar and the
 * rows; an open panel takes from that only the room the photo leaves empty (at least 68 dp) and lies
 * over the photo otherwise (it's 95 % opaque). The top bar floats over the photo with no fill. While
 * the photo is held it shows before its edits, everything else out of the way and a pill saying so;
 * a tap gives the photo the whole screen (another tap, or Back, brings the rest back).
 */
@Composable
private fun UprightEditor(vm: AppViewModel, ed: EditorState, createPreset: () -> Unit, onExport: (Boolean) -> Unit) {
    val density = LocalDensity.current
    // the foot's stack (panel, rows, the navigation bar's strip) and its rows alone, as laid out (px)
    var stack by remember { mutableIntStateOf(0) }
    var rows by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val status = WindowInsets.statusBars.getTop(density)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fit = if (ed.immersive) {
            IntRect(0, 0, width, height)
        } else {
            val area = (height - status - rows).coerceAtLeast(1)
            val panel = (stack - rows).coerceAtLeast(0)
            val (w, h) = photoSize(ed) ?: (1f to 1f)
            val fitted = min(area.toFloat(), width * h / w).roundToInt()
            // Crop keeps clear of its whole panel, as sideways (its dial and round buttons sit at the photo's
            // foot, and a tall photo or one just turned would otherwise have them under the panel: issue 8);
            // elsewhere the panel may lie over a tall photo's foot
            val shrink = when {
                panel <= 0 -> 0
                ed.tool == MainTool.CROP -> panel
                else -> min(panel, max(area - fitted, with(density) { 68.dp.roundToPx() }))
            }
            IntRect(0, status, width, status + area - shrink)
        }
        // as the reference's: the photo eases into the room a panel leaves it (350 ms, slowing)
        val fitTop by animateIntAsState(fit.top, tween(350, easing = LinearOutSlowInEasing), label = "fit top")
        val fitHeight by animateIntAsState(fit.height, tween(350, easing = LinearOutSlowInEasing), label = "fit height")
        PhotoView(vm, ed, Modifier.offset { IntOffset(fit.left, fitTop) }.size(with(density) { DpSize(fit.width.toDp(), fitHeight.toDp()) }), sideways = false)
        if (!ed.immersive) {
            val before = ed.showingBefore
            Column(Modifier.fillMaxWidth().alpha(if (before) 0f else 1f)) {
                // the status bar is shaded, not black, so a zoomed photo shows through it (the reference's)
                Spacer(Modifier.fillMaxWidth().background(STATUS_SHADE).windowInsetsTopHeight(WindowInsets.statusBars))
                if (ed.rangePicker == null) EditorTopBar(vm, ed, createPreset, onExport, Modifier.windowInsetsPadding(horizontalInsets))
                // a mask part's value slider: its name and value 7.8 dp under the bar (the reference's)
                ed.barPill?.let { Pill(it, Modifier.align(Alignment.CenterHorizontally).padding(top = 7.8.dp)) }
            }
            if (before) BeforePill(Modifier.align(Alignment.TopCenter))
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .alpha(if (before) 0f else 1f)
                    .onSizeChanged { stack = it.height }
                    .windowInsetsPadding(horizontalInsets),
            ) {
                BottomArea(vm, ed) { rows = it }
            }
        }
    }
}

/**
 * Sideways, as the reference's: the photo full-bleed from the screen's left edge (under the camera's
 * cutout) to the panel, between the status bar and the foot; the panel (320 dp), Edit's sections and
 * the tools stand as columns at the right; an open panel takes from the photo only the room it leaves
 * empty (at least 61 dp). The top bar floats over the photo and ends at the panel.
 */
@Composable
private fun SidewaysEditor(vm: AppViewModel, ed: EditorState, createPreset: () -> Unit, onExport: (Boolean) -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // the side's width (panel and columns), as laid out (px)
    var side by remember { mutableIntStateOf(0) }
    val endInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val status = WindowInsets.statusBars.getTop(density)
        val foot = WindowInsets.navigationBars.getBottom(density)
        val end = endInsets.getRight(density, direction)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val mode = ed.tool == MainTool.CROP || (ed.section == EditSection.PROFILES && ed.tool == MainTool.EDIT)
        // Curve, Mix and Grading take the columns' room as well, as the reference's
        val aside = ed.tool == MainTool.EDIT && ed.sub != null
        val columns = with(density) { (if (mode || aside) 0f else sideColumnsWidth(ed)).dp.roundToPx() }
        val photoEnd = (width - end - side).coerceAtLeast(0)
        val fit = if (ed.immersive) {
            IntRect(0, 0, width, height)
        } else {
            val areaW = (width - end - columns).coerceAtLeast(1)
            val areaH = (height - status - foot).coerceAtLeast(1)
            val (w, h) = photoSize(ed) ?: (1f to 1f)
            val fitted = min(areaW.toFloat(), areaH * w / h).roundToInt()
            val panel = (side - columns).coerceAtLeast(0)
            // Crop keeps clear of its whole panel, as the reference's (its frame, handles and buttons
            // all on the photo's side); elsewhere the panel may lie over a wide photo's right side
            val shrink = when {
                panel <= 0 -> 0
                ed.tool == MainTool.CROP -> panel
                else -> min(panel, max(areaW - fitted, with(density) { 61.dp.roundToPx() }))
            }
            IntRect(0, status, areaW - shrink, height - foot)
        }
        val fitWidth by animateIntAsState(fit.width, tween(350, easing = LinearOutSlowInEasing), label = "fit width")
        // the panel may lie over the photo view's right side: what's over it keeps clear of that
        val covered = with(density) { (fit.left + fitWidth - photoEnd).coerceAtLeast(0).toDp() }
        PhotoView(vm, ed, Modifier.offset { IntOffset(fit.left, fit.top) }.size(with(density) { DpSize(fitWidth.toDp(), fit.height.toDp()) }), sideways = true, coveredEnd = if (ed.immersive) 0.dp else covered)
        if (!ed.immersive) {
            val before = ed.showingBefore
            Column(Modifier.width(with(density) { photoEnd.toDp() }).alpha(if (before) 0f else 1f)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                if (ed.rangePicker == null) EditorTopBar(vm, ed, createPreset, onExport, Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start)))
            }
            if (before) Box(Modifier.width(with(density) { photoEnd.toDp() })) { BeforePill(Modifier.align(Alignment.TopCenter)) }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .alpha(if (before) 0f else 1f)
                    .windowInsetsPadding(endInsets.union(WindowInsets.statusBars.only(WindowInsetsSides.Top)).union(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                    .onSizeChanged { side = it.width },
            ) {
                SideArea(vm, ed)
            }
        }
    }
}

/** Sideways, the columns beside the panel (dp): the tools' (Masking's Cancel and Apply once it's a mode), and Edit's sections, Masking's or Actions'. */
private fun sideColumnsWidth(ed: EditorState) = (if (ed.maskingMode) MODE_STRIP else 53.5f) + when (ed.tool) {
    MainTool.EDIT, MainTool.MASKING -> 55.5f
    MainTool.ACTIONS -> 59.4f
    else -> 0f
}

/** The photo's shape as it shows: its render's, else the file's (null before either is known). */
private fun photoSize(ed: EditorState): Pair<Float, Float>? {
    val image = ed.image
    val (w, h) = if (image != null) image.width.toFloat() to image.height.toFloat() else ed.info.width.toFloat() to ed.info.height.toFloat()
    return if (w > 0f && h > 0f) w to h else null
}

/** "Before", in the reference's pill, where the (hidden) top bar is, while the photo is held. */
@Composable
private fun BeforePill(modifier: Modifier) {
    Column(modifier) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) { Pill("Before") }
    }
}

/**
 * How tall the open panel may be (dp), as the reference caps its sheet: 184 dp with the rows under
 * it, 229 for Effects and Detail (their tabs take room), 300 when the rows step aside (Curve, Mix),
 * 435 for Grading, 306 for Presets and 232 for Crop. Shorter content keeps its own height.
 */
private fun sheetCap(ed: EditorState): Dp = when {
    ed.tool == MainTool.CROP -> 232.dp
    ed.tool == MainTool.PRESETS -> 306.dp
    ed.tool == MainTool.EDIT && ed.sub == "grading" -> 435.dp
    ed.tool == MainTool.EDIT && (ed.sub == "curve" || ed.sub == "mix") -> 300.dp
    ed.tool == MainTool.EDIT && (ed.section == EditSection.EFFECTS || ed.section == EditSection.DETAIL) -> 229.dp
    else -> SheetPeek
}

/** The editor's status bar over a zoomed photo: shaded, as the reference's. */
private val STATUS_SHADE = Color.Black.copy(alpha = 0.35f)

/** The strip behind the status bar. */
@Composable
fun StatusStrip() = Spacer(Modifier.fillMaxWidth().background(Lc.statusBar).windowInsetsTopHeight(WindowInsets.statusBars))

/** The strip behind the navigation bar (or the gesture handle). */
@Composable
fun NavStrip(color: Color = Lc.navBar) = Spacer(Modifier.fillMaxWidth().background(color).windowInsetsBottomHeight(WindowInsets.navigationBars))

/**
 * The panel's colour: the reference's grey at 95 % ([opaque]: whole, sideways, where a wide photo may
 * run under it and the reference's panel shows none of it), cleared while a slider is dragged (over
 * 250 ms, back in 100 ms) so only that slider shows over the photo.
 */
@Composable
private fun panelColor(opaque: Boolean = false): Color {
    val clear = LocalSliderDrag.current?.id != null
    val shown = if (opaque) Lc.panel.copy(alpha = 1f) else Lc.panel
    val color by animateColorAsState(if (clear) Color.Transparent else shown, tween(if (clear) 250 else 100, easing = AccelerateDecelerate), label = "panel")
    return color
}

/**
 * Upright, the foot: the open tool's panel (sized to its content up to [sheetCap], scrolling inside),
 * then the rows and the navigation bar's strip ([onRows] gets their height). With no tool open the
 * tools are a card of their own on black; with one open the rows run the width, a line along their
 * top and a hairline between the sections and the tools. Curve, Mix and Grading hide the rows.
 */
@Composable
private fun BottomArea(vm: AppViewModel, ed: EditorState, onRows: (Int) -> Unit) {
    val picker = ed.rangePicker
    if (picker != null && ed.tool == MainTool.MASKING) {
        // the photo moves up above the sheet, as the reference's
        Column(Modifier.fillMaxWidth().onSizeChanged { onRows(it.height) }) {
            RangePickerSheet(vm, ed, picker)
            NavStrip(Color(0xFF1C1C1C))
        }
        return
    }
    val cap = sheetCap(ed)
    val sheet = Modifier.fillMaxWidth().heightIn(max = cap).background(panelColor())
    val section = ed.section
    val profiles = ed.section == EditSection.PROFILES && ed.tool == MainTool.EDIT
    CompositionLocalProvider(LocalSheetLimit provides cap) {
        Box(Modifier.fillMaxWidth().animateContentSize(tween(300, easing = QuinticOut))) {
        when {
            ed.tool == MainTool.CROP -> CropSheet(vm, ed, sheet)
            profiles -> ProfilesSheet(vm, ed, sheet)
            ed.tool == MainTool.MASKING -> if (ed.maskSection != null && ed.selectedMask != null) MaskSheet(vm, ed, sheet)
            ed.tool == MainTool.EDIT -> if (section != null) SectionSheet(vm, ed, section, sheet)
            ed.tool == MainTool.PRESETS -> PresetsSheet(vm, ed, sheet)
            ed.tool == MainTool.REMOVE -> RemoveSheet(vm, ed, sheet)
        }
        }
    }
    val open = ed.tool != null
    val aside = ed.tool == MainTool.EDIT && (ed.sub == "curve" || ed.sub == "mix" || ed.sub == "grading")
    Column(Modifier.fillMaxWidth().onSizeChanged { onRows(it.height) }.background(if (open && !aside) Lc.toolRows else if (aside) Lc.panel else Color.Transparent)) {
        if (!aside) {
            // as the reference's: a dark line along the rows' top, 2 dp over the first row
            Column(
                if (open) {
                    Modifier.drawBehind { drawRect(Lc.toolRowsEdge, Offset(0f, 0.5.dp.toPx()), Size(size.width, 1.dp.toPx())) }.padding(top = 2.dp)
                } else {
                    Modifier
                },
            ) {
                when {
                    ed.tool == MainTool.CROP -> ModeBar("Crop", onCancel = vm::cancelCrop, onApply = vm::applyCrop)
                    profiles -> ModeBar("Profiles", onCancel = vm::cancelProfiles, onApply = vm::applyProfiles)
                    else -> {
                        when (ed.tool) {
                            MainTool.EDIT -> SectionRow(vm, ed)
                            MainTool.ACTIONS -> ActionRow(vm, ed)
                            MainTool.MASKING -> MaskSectionRow(vm, ed)
                            else -> {}
                        }
                        // with a mask (or changes to discard), Masking is a mode: its Cancel and Apply where the tools were (the reference's)
                        if (ed.maskingMode) {
                            ModeBar("Masking", onCancel = vm::cancelMasking, onApply = vm::applyMasking)
                        } else {
                            ToolRow(vm, ed)
                        }
                    }
                }
            }
        }
        NavStrip(if (open && !aside) Lc.toolRows else if (aside) Lc.panel else Color.Transparent)
    }
}

/** Sideways: the open tool's 320 dp panel, Edit's sections and the tools, as columns down the right. */
@Composable
private fun SideArea(vm: AppViewModel, ed: EditorState) {
    val profiles = ed.section == EditSection.PROFILES && ed.tool == MainTool.EDIT
    val mode = profiles || ed.tool == MainTool.CROP
    val section = ed.section
    val aside = ed.tool == MainTool.EDIT && ed.sub != null
    Row(Modifier.fillMaxHeight()) {
        val panel = Modifier.width(sidewaysPanelWidth(ed)).fillMaxHeight().background(panelColor(opaque = true))
        val picker = ed.rangePicker
        if (picker != null && ed.tool == MainTool.MASKING) {
            RangePickerSheet(vm, ed, picker, Modifier.width(sidewaysPanelWidth(ed)), sideways = true)
            return@Row
        }
        when {
            ed.tool == MainTool.CROP -> Row(panel) {
                CropSheet(vm, ed, Modifier.weight(1f).fillMaxHeight())
                ModeStrip(onCancel = vm::cancelCrop, onApply = vm::applyCrop)
            }
            ed.tool == MainTool.REMOVE -> RemoveSheet(vm, ed, panel)
            profiles -> Row(panel) {
                ProfilesSheet(vm, ed, Modifier.weight(1f).fillMaxHeight())
                ModeStrip(onCancel = vm::cancelProfiles, onApply = vm::applyProfiles)
            }
            ed.tool == MainTool.EDIT && section != null -> SectionSheet(vm, ed, section, panel)
            // no mask open: no panel, as there's no sheet upright; the photo takes the room
            ed.tool == MainTool.MASKING && ed.masks.any { it.id == ed.selectedMask } -> MaskSheet(vm, ed, panel)
            ed.tool == MainTool.PRESETS -> PresetsSheet(vm, ed, panel)
        }
        if (mode || aside) return@Row
        // with a mask (or changes to discard), Masking is a mode: its Cancel and Apply strip in the tools' place
        val masking = ed.maskingMode
        Row(Modifier.fillMaxHeight().background(if (ed.tool != null) Lc.toolRows else Color.Transparent)) {
        if (ed.tool == MainTool.MASKING) {
            Column(Modifier.padding(start = 2.dp).width(53.5.dp).fillMaxHeight()) {
                MaskSection.entries.forEach { s ->
                    ToolButton(s.icon, s.label, showLabel = true, selected = ed.maskSection == s.name.lowercase(), column = true, modifier = Modifier.size(width = 53.5.dp, height = 68.1.dp)) {
                        vm.selectMaskSection(s.name.lowercase())
                    }
                }
            }
        }
        if (ed.tool == MainTool.ACTIONS) {
            Column(Modifier.padding(start = 2.dp).width(57.4.dp).fillMaxHeight()) {
                QuickAction.entries.forEach { a ->
                    ToolButton(a.icon, a.label, showLabel = true, selected = ed.action == a, column = true, modifier = Modifier.size(width = 57.4.dp, height = 68.1.dp)) { vm.runAction(a) }
                }
            }
        }
        if (ed.tool == MainTool.EDIT) {
            // as the reference's: the column scrolls just enough to show the open section
            val sections = rememberLazyListState()
            LaunchedEffect(ed.section) {
                val i = EditSection.entries.indexOf(ed.section ?: return@LaunchedEffect)
                val info = sections.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                when {
                    item == null -> sections.animateScrollToItem(i)
                    item.offset + item.size > info.viewportEndOffset -> sections.animateScrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
                    item.offset < info.viewportStartOffset -> sections.animateScrollBy((item.offset - info.viewportStartOffset).toFloat())
                }
            }
            LazyColumn(Modifier.padding(start = 2.dp).width(53.5.dp).fillMaxHeight(), state = sections) {
                items(EditSection.entries, key = { it.name }) { s ->
                    if (s == EditSection.AUTO) {
                        Column {
                            SectionButton(vm, ed, s, Modifier.size(width = 53.5.dp, height = 68.1.dp), column = true)
                            ToolDivider(column = true)
                        }
                    } else {
                        SectionButton(vm, ed, s, Modifier.size(width = 53.5.dp, height = 68.1.dp), column = true)
                    }
                }
            }
        }
        if (masking) {
            ModeStrip(onCancel = vm::cancelMasking, onApply = vm::applyMasking)
            return@Row
        }
        val labels = ed.tool == null
        // no tool open: the tools are a card of their own at the top of the side; one open, a column with a hairline along it
        Column(
            Modifier.width(53.5.dp).fillMaxHeight().then(if (labels) Modifier else Modifier.drawBehind { drawRect(Lc.toolRowsHairline, size = Size(1.dp.toPx(), size.height)) }),
            verticalArrangement = Arrangement.Top,
        ) {
            Column(if (labels) Modifier.clip(RoundedCornerShape(4.dp)).background(Lc.toolRows) else Modifier) {
                MainTool.entries.forEach { t ->
                    ToolButton(t.icon, t.label, showLabel = labels, selected = ed.tool == t, primary = true, column = true, modifier = Modifier.size(width = 53.5.dp, height = if (labels) 68.1.dp else 53.5.dp)) {
                        vm.selectTool(t)
                    }
                }
            }
        }
        }
    }
}

/**
 * Back at left; share, save and more at right (none in a mode such as Profiles). While a slider is
 * dragged the bar hides, and a pill in its middle names the slider and its value, as the reference's.
 */
@Composable
private fun EditorTopBar(vm: AppViewModel, ed: EditorState, onCreatePreset: () -> Unit, onExport: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val drag = LocalSliderDrag.current
    Box(Modifier.fillMaxWidth().then(modifier)) {
        TopBarButtons(vm, ed, onCreatePreset, onExport, Modifier.alpha(if (drag?.id != null) 0f else 1f))
        if (drag?.id != null) Pill(drag.text, Modifier.align(Alignment.Center))
    }
}

/** The reference's pill: 16 sp white on its grey at 95 %, 25 dp corners (a moving slider's value, Before). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontFamily = Inter,
        fontSize = 16.sp,
        maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
        modifier = modifier.background(Lc.pill, RoundedCornerShape(50)).padding(horizontal = 19.dp, vertical = 5.6.dp),
    )
}

@Composable
private fun TopBarButtons(vm: AppViewModel, ed: EditorState, onCreatePreset: () -> Unit, onExport: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var more by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(false) }
    if (previous) {
        ChoiceMenu(
            "Apply from previous",
            listOf("basics" to "Basics", "all" to "All"),
            preview = { vm.previousPreview(basics = it == "basics") },
            onDismiss = { previous = false },
        ) { vm.applyFromPrevious(basics = it == "basics") }
    }
    val uri = LocalUriHandler.current
    val help = { runCatching { uri.openUri(HELP_URL) } }
    Column(Modifier.fillMaxWidth().then(modifier)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            // a mode (Profiles, Crop) keeps only help, at the right, as the reference's; Crop names its aspect in a pill
            if (ed.tool == MainTool.CROP || (ed.section == EditSection.PROFILES && ed.tool == MainTool.EDIT)) {
                // upright the pill is centred on the screen; sideways, as the reference's, over the photo and
                // the panel together (all that's left of the mode's Cancel and Apply strip)
                val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
                val view = LocalView.current
                val density = LocalDensity.current
                val end = WindowInsets.navigationBars.union(WindowInsets.displayCutout).getRight(density, LocalLayoutDirection.current)
                var box by remember { mutableStateOf(0f to 0) }
                Box(
                    Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { box = it.positionInWindow().x to it.size.width },
                    contentAlignment = Alignment.Center,
                ) {
                    val frame = ed.crop
                    if (ed.tool == MainTool.CROP && frame != null && ed.cropTab != "geometry") {
                        if (sideways) {
                            val centre = (view.width - end - with(density) { MODE_STRIP.dp.toPx() }) / 2f
                            Pill(aspectName(frame, ed.cropFormat), Modifier.offset { IntOffset((centre - box.first - box.second / 2f).roundToInt(), 0) })
                        } else {
                            Pill(aspectName(frame, ed.cropFormat), Modifier.padding(start = 40.dp))
                        }
                    }
                }
                BarButton(MdIcon.Help, "Help", size = 40.dp, tint = Lc.barIcon) { help() }
                return@Row
            }
            if (ed.maskingMode) {
                Spacer(Modifier.weight(1f))
                if (ed.history.undo != null || ed.history.redo != null || ed.info.edited) {
                    UndoButton(vm, ed)
                    Spacer(Modifier.width(7.7.dp))
                }
                BarButton(MdIcon.HelpOutline, "Help", size = 40.dp, tint = Lc.barIcon) { help() }
                Spacer(Modifier.width(7.7.dp))
                BarButton(LcIcon.MaskOverlay, if (ed.showMaskOverlay) "Hide mask" else "Show mask", size = 40.dp, tint = if (ed.showMaskOverlay) Lc.barIconOn else Lc.barIcon, onClick = vm::toggleMaskOverlay)
                Spacer(Modifier.width(3.9.dp))
                BarButton(MdIcon.MoreVert, "More options", size = 40.dp, tint = Lc.barIcon) { ed.maskOptions = true }
                return@Row
            }
            BarButton(MdIcon.ArrowBack, "Back", size = 40.dp, tint = Lc.barIcon, onClick = vm::closeEditor)
            Spacer(Modifier.weight(1f))
            // there once there's a step to take back, or edits to reset (a photo edited on an earlier visit)
            if (ed.history.undo != null || ed.history.redo != null || ed.info.edited) {
                UndoButton(vm, ed)
                Spacer(Modifier.width(7.7.dp))
            }
            if (ed.exporting) {
                Box(Modifier.size(width = 87.7.dp, height = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Lc.barIcon, strokeWidth = 2.dp)
                }
            } else {
                BarButton(MdIcon.Share, "Share", size = 40.dp, iconSize = 22.dp, tint = Lc.barIcon) { onExport(true) }
                Spacer(Modifier.width(7.7.dp))
                // Masking: show the mask over the photo (between share and save, as in the reference)
                if (ed.tool == MainTool.MASKING) {
                    BarButton(LcIcon.MaskOverlay, if (ed.showMaskOverlay) "Hide mask" else "Show mask", size = 40.dp, tint = if (ed.showMaskOverlay) Lc.barIconOn else Lc.barIcon, onClick = vm::toggleMaskOverlay)
                    Spacer(Modifier.width(7.7.dp))
                }
                BarButton(LcIcon.Download, "Save copy to device", size = 40.dp, tint = Lc.barIcon) { onExport(false) }
            }
            Spacer(Modifier.width(3.9.dp))
            Box {
                // open, the button sits on a grey square, the menu hanging from it (the reference's)
                Box(Modifier.size(40.dp).background(if (more) Lc.barButtonOpen else Color.Transparent)) {
                    // with Presets open, the reference's More is the presets' options
                    BarButton(MdIcon.MoreVert, "More options", size = 40.dp, tint = Lc.barIcon) {
                        when (ed.tool) {
                            MainTool.PRESETS -> ed.presetOptions = true
                            // with Masking open, the reference's More is the overlays' options
                            MainTool.MASKING -> ed.maskOptions = true
                            else -> more = true
                        }
                    }
                }
                // the reference's menu for a photo of the library (one from the phone's gallery has fewer)
                val inLibrary = ed.device == null
                LcMenu(expanded = more, onDismiss = { more = false }) {
                    if (inLibrary) {
                        LcMenuItem(LcIcon.PhotoStack, "Organize") {
                            more = false
                            ed.organize = true
                        }
                    }
                    LcMenuItem(LcIcon.ViewOptions, "View options") {
                        more = false
                        ed.viewOptions = true
                    }
                    if (inLibrary) {
                        LcMenuItem(MdIcon.Info, "Info and rating") {
                            more = false
                            ed.infoPage = true
                        }
                    }
                    LcMenuDivider()
                    LcMenuItem(LcIcon.CreatePreset, "Create Preset") {
                        more = false
                        onCreatePreset()
                    }
                    LcMenuItem(LcIcon.CopySettings, "Copy Settings") {
                        more = false
                        ed.copyPicker = true
                    }
                    LcMenuItem(LcIcon.PasteSettings, "Paste Settings", enabled = vm.hasCopiedSettings) {
                        more = false
                        vm.pasteSettings()
                    }
                    LcMenuItem(LcIcon.ApplyPrevious, "Apply from previous") {
                        more = false
                        previous = true
                    }
                    LcMenuDivider()
                    if (inLibrary && vm.photos.any { it.id == ed.photoId }) {
                        LcMenuItem(LcIcon.Play, "Start Slideshow") {
                            more = false
                            vm.startSlideshow(ed.photoId)
                        }
                    }
                    LcMenuItem(MdIcon.HelpOutline, "Help") {
                        more = false
                        help()
                    }
                }
            }
        }
    }
}

/**
 * Undo, as the reference's: one button (once there's something to take back) that opens a card
 * under it: undo and redo side by side, a line, then Reset (every edit).
 */
@Composable
private fun UndoButton(vm: AppViewModel, ed: EditorState) {
    var open by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    if (resetting) {
        ChoiceMenu(
            "Reset",
            listOf("adjustments" to "Adjustments", "all" to "All", "import" to "To Import", "open" to "To Open").filter { it.first != "open" || ed.openSettings != null },
            preview = { vm.resetPreview(it) },
            onDismiss = { resetting = false },
        ) { vm.resetTo(it) }
    }
    val density = LocalDensity.current
    Box {
        Box(Modifier.size(40.dp).background(if (open) Lc.barButtonOpen else Color.Transparent)) {
            BarButton(LcIcon.Undo, "Undo and redo", size = 40.dp, tint = Lc.barIcon) { open = true }
            // the reference's mark that it opens a menu: a small triangle in its lower right corner
            Canvas(Modifier.matchParentSize()) {
                val (c, a, b) = Triple(center, 9.5.dp.toPx(), 14.9.dp.toPx())
                drawPath(Path().apply { moveTo(c.x + b, c.y + a); lineTo(c.x + b, c.y + b); lineTo(c.x + a, c.y + b); close() }, Lc.barIcon)
            }
        }
        if (open) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = with(density) { IntOffset(0, (40.dp + 4.dp).roundToPx()) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Lc.menu)
                        .border(1.dp, Lc.menuEdge, RoundedCornerShape(5.dp))
                        .padding(vertical = 4.dp),
                ) {
                    Row(Modifier.align(Alignment.CenterHorizontally).width(120.dp).height(40.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarButton(LcIcon.Undo, listOfNotNull("Undo", ed.history.undo).joinToString(" "), size = 40.dp, iconSize = 24.dp, enabled = ed.history.undo != null, tint = Lc.menuText) {
                            vm.undo()
                        }
                        BarButton(LcIcon.Redo, listOfNotNull("Redo", ed.history.redo).joinToString(" "), size = 40.dp, iconSize = 24.dp, enabled = ed.history.redo != null, tint = Lc.menuText) {
                            vm.redo()
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF494949)))
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button) {
                            open = false
                            resetting = true
                        }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LcIcon(LcIcon.Undo, null, tint = Lc.menuIcon, size = 22.dp)
                        Text("Reset", color = Lc.menuText, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    // the open tool's own reset
                    val toolReset = when {
                        ed.tool == MainTool.CROP && ed.cropTab == "geometry" -> "Reset Geometry" to { vm.resetSectionNamed("geometry") }
                        ed.tool == MainTool.CROP -> "Reset Crop" to { vm.cropCommand("crop.reset") }
                        ed.tool == MainTool.MASKING && ed.masks.isNotEmpty() -> "Reset Masking" to { vm.deleteAllMasks() }
                        ed.tool == MainTool.REMOVE && ed.spots.isNotEmpty() -> "Reset Remove" to { vm.deleteAllSpots() }
                        else -> null
                    }
                    toolReset?.let { (label, run) ->
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button) {
                                open = false
                                run()
                            }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LcIcon(LcIcon.Undo, null, tint = Lc.menuIcon, size = 22.dp)
                            Text(label, color = Lc.menuText, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    if (ed.device == null) {
                        Row(
                            Modifier.fillMaxWidth().height(40.dp).clickable(role = Role.Button) {
                                open = false
                                ed.versionsPage = true
                            }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LcIcon(LcIcon.Versions, null, tint = Lc.menuIcon, size = 22.dp)
                            Text("Versions", color = Lc.menuText, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MenuItem(text: String, start: Dp = 12.dp, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text, fontFamily = Inter, fontSize = 15.sp, color = Lc.text) }, onClick = onClick, contentPadding = PaddingValues(start = start, end = 12.dp))
}

/**
 * A popup menu as the reference's editor draws its own: its grey with a 1 dp lighter edge and 5 dp
 * corners, hanging from the button under it at the screen's right edge.
 */
@Composable
fun LcMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Lc.menu,
        shape = RoundedCornerShape(5.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Lc.menuEdge),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.widthIn(min = 201.4.dp),
        content = content,
    )
}

/** A row of [LcMenu]: 48 dp, its icon 24 dp in from the left, its words 38 dp further; faint while it can't be used. */
@Composable
fun LcMenuItem(icon: Glyph, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.2f)
            .padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { GlyphIcon(icon, null, tint = Lc.menuIcon, size = 22.dp) }
        Spacer(Modifier.width(14.dp))
        Text(text, color = Lc.menuText, fontFamily = Inter, fontSize = 16.sp, maxLines = 1)
    }
}

/** The 1 dp line between a menu's groups, edge to edge. */
@Composable
fun LcMenuDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.menuEdge))

/**
 * An icon button: [icon] (its own size, or [iconSize]) in a [size] square that presses as a circle,
 * as the reference's; [active] while its card is open (a light disc, the icon dark).
 */
@Composable
fun BarButton(icon: Glyph, description: String, enabled: Boolean = true, size: Dp = 44.dp, iconSize: Dp? = null, tint: Color = Lc.icon, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Lc.text else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(icon, null, tint = if (active) Lc.bar else if (enabled) tint else Lc.textDisabled, size = iconSize)
    }
}

/** A pressed tool's ripple: [Lc.toolPress] at 12 %. */
private val ToolRipple = RippleConfiguration(color = Lc.toolPress, rippleAlpha = RippleAlpha(0.12f, 0.12f, 0.12f, 0.12f))

/**
 * A tool, an Edit section or an action, as the reference's: its icon (about 18 dp of glyph, in a 36 dp block) over
 * its label, or the icon alone, in a box 4.9 dp inside the cell with 5 dp corners. Chosen, the box
 * fills: blue with a white icon and name for a tool ([primary], the first row), dark grey for a
 * section or an action (the second row). Pressed, a faint grey ripple fills the box. One that holds
 * edits has a 4 × 2 dp mark at the cell's foot ([column]: 2 × 4 dp at its end), grown in over half a
 * second.
 */
@Composable
private fun ToolButton(
    icon: Glyph,
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    edited: Boolean = false,
    primary: Boolean = false,
    column: Boolean = false,
    modifier: Modifier = Modifier.width(53.5.dp).fillMaxHeight(),
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val box = RoundedCornerShape(5.dp)
    val content = when {
        !selected -> null
        primary -> Color.White
        else -> Lc.sectionChosenContent
    }
    val mark by animateFloatAsState(if (edited) 1f else 0f, tween(500, easing = AccelerateDecelerate), label = "mark")
    val markColor by animateColorAsState(if (selected) Color.White else Lc.toolMark, tween(500, easing = AccelerateDecelerate), label = "mark colour")
    Box(
        // with its label showing, the label is what's read
        modifier
            .selectable(selected, interactionSource = press, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
            .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label })
            .drawWithContent {
                drawContent()
                if (mark <= 0f) return@drawWithContent
                val r = CornerRadius(2.dp.toPx() * mark)
                val long = 4.dp.toPx() * mark
                val short = 2.dp.toPx() * mark
                val shape = if (column) {
                    RoundRect(Rect(size.width - short, (size.height - long) / 2, size.width, (size.height + long) / 2), topLeft = r, bottomLeft = r)
                } else {
                    RoundRect(Rect((size.width - long) / 2, size.height - short, (size.width + long) / 2, size.height), topLeft = r, topRight = r)
                }
                drawPath(Path().apply { addRoundRect(shape) }, markColor)
            },
        // a cell given only a least width (Edit's scrolling row) is wider than a short name's icon and
        // name: they're centred in it, as its box and mark are, not left
        contentAlignment = Alignment.Center,
    ) {
        // the box: its fill and its ripple, behind the icon and name (which may be wider)
        Box(
            Modifier
                .matchParentSize()
                .padding(4.9.dp)
                .background(
                    when {
                        !selected -> Color.Transparent
                        primary -> Lc.toolChosen
                        else -> Lc.sectionChosen
                    },
                    box,
                ),
        ) {
            CompositionLocalProvider(LocalRippleConfiguration provides ToolRipple) {
                Box(Modifier.matchParentSize().clip(box).indication(press, ripple()))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.9.dp, vertical = if (showLabel) 0.dp else 4.9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (showLabel) Arrangement.Top else Arrangement.Center,
        ) {
            if (showLabel) Spacer(Modifier.height(4.9.dp))
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                // the reference draws its glyphs 20 dp across, nearly edge to edge; ours have margins
                GlyphIcon(icon, null, tint = content ?: Lc.toolIcon, size = 22.dp)
            }
            if (showLabel) {
                Text(
                    label,
                    color = content ?: Lc.toolLabel,
                    fontFamily = Inter,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    // the reference's names sit 3 dp under their icons
                    modifier = Modifier.wrapContentWidth(unbounded = true).offset(y = 1.dp).padding(horizontal = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionButton(vm: AppViewModel, ed: EditorState, s: EditSection, modifier: Modifier, column: Boolean = false) {
    val edited = s.sections.isNotEmpty() && ed.controls.any { it.section in s.sections && it.isChanged }
    ToolButton(s.icon, s.label, showLabel = true, selected = ed.section == s, edited = edited, column = column, modifier = modifier) { vm.selectSection(s) }
}

/** The line that sets Auto (and Rate) apart, as the reference's: 32 × 1 dp with 3 dp each side, across a column or down a row. */
@Composable
private fun ToolDivider(column: Boolean) {
    val line = if (column) Modifier.size(width = 32.dp, height = 1.dp) else Modifier.size(width = 1.dp, height = 32.dp)
    Box(Modifier.padding(if (column) PaddingValues(vertical = 3.dp) else PaddingValues(horizontal = 3.dp)).then(line).background(Lc.toolDivider, RoundedCornerShape(0.5.dp)))
}

/** The tools, centred; labelled (68 dp) until one is open (53.5 dp), as the reference app's. */
@Composable
private fun ToolRow(vm: AppViewModel, ed: EditorState) {
    val labels = ed.tool == null
    if (labels) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.clip(RoundedCornerShape(4.dp)).background(Lc.toolRows).height(68.1.dp)) {
                MainTool.entries.forEach { t ->
                    ToolButton(t.icon, t.label, showLabel = true, selected = false, primary = true, modifier = Modifier.width(t.labelledWidth.dp).fillMaxHeight()) { vm.selectTool(t) }
                }
            }
        }
        return
    }
    // the reference's hairline runs along the tools' top, half a dp in
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Lc.toolRowsHairline, Offset(0.5.dp.toPx(), 0.5.dp.toPx()), Size(size.width - 1.dp.toPx(), 1.dp.toPx())) }
            .padding(bottom = 7.8.dp)
            .height(53.5.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        MainTool.entries.forEach { t ->
            ToolButton(t.icon, t.label, showLabel = false, selected = ed.tool == t, primary = true, modifier = Modifier.width(53.5.dp).fillMaxHeight()) { vm.selectTool(t) }
        }
    }
}

/**
 * Actions' row (where Edit's sections go): Auto and Fix angle, then, set apart by a 6.8 dp gap as
 * the reference's second group, Rate. Each is as wide as the reference's (its label and 17.5 dp).
 */
@Composable
private fun ActionRow(vm: AppViewModel, ed: EditorState) {
    Row(Modifier.fillMaxWidth().height(68.1.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        QuickAction.entries.forEach { a ->
            ToolButton(a.icon, a.label, showLabel = true, selected = ed.action == a, modifier = Modifier.width(a.width.dp).fillMaxHeight()) { vm.runAction(a) }
        }
    }
}

/** Masking's sections (the edited mask's local adjustments), icon over label; shown before there's a mask, as the reference's. */
@Composable
private fun MaskSectionRow(vm: AppViewModel, ed: EditorState) {
    // faint and inert until there's a mask to adjust, as the reference's
    val on = ed.selectedMask != null
    Row(Modifier.fillMaxWidth().height(68.1.dp).alpha(if (on) 1f else 0.3f), horizontalArrangement = Arrangement.Center) {
        MaskSection.entries.forEach { s ->
            ToolButton(s.icon, s.label, showLabel = true, selected = ed.maskSection == s.name.lowercase(), enabled = on) { vm.selectMaskSection(s.name.lowercase()) }
        }
    }
}

/** Edit's sections, icon over label, Auto set apart from the rest by a hairline. */
@Composable
private fun SectionRow(vm: AppViewModel, ed: EditorState) {
    LazyRow(
        Modifier.fillMaxWidth().height(68.1.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(EditSection.entries, key = { it.name }) { s ->
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                SectionButton(vm, ed, s, Modifier.widthIn(min = 53.5.dp).fillMaxHeight())
                if (s == EditSection.AUTO) ToolDivider(column = false)
            }
        }
    }
}

/** A mode's bar: cancel at left, its name in the middle, apply at right. */
@Composable
private fun ModeBar(title: String, onCancel: () -> Unit, onApply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(Lc.toolRowsHairline, Offset(0.5.dp.toPx(), 0.5.dp.toPx()), Size(size.width - 1.dp.toPx(), 1.dp.toPx())) }
            .height(61.2.dp)
            .padding(start = 6.8.dp, end = 6.8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(MdIcon.Close, "Cancel", size = 40.dp, tint = Lc.panelText, onClick = onCancel)
        Text(title, color = Lc.panelText, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        BarButton(MdIcon.Check, "Apply", size = 40.dp, tint = Lc.panelText, onClick = onApply)
    }
}

/**
 * Masking's Cancel with changes made, as the reference's: "Discard Masking" in bold over a rule, what
 * it does, then Keep editing (grey) over Discard changes (red), outlined pills at the right; discard
 * goes back to how the photo was as Masking opened.
 */
@Composable
private fun DiscardMaskingDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Lc.dialog)
                .padding(start = 24.5.dp, end = 24.dp, top = 24.dp, bottom = 21.8.dp),
        ) {
            Text("Discard Masking", color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF393939)))
            Spacer(Modifier.height(16.dp))
            Text("The changes made to Masking will be discarded when you cancel.", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp, lineHeight = 18.7.sp)
            Spacer(Modifier.height(41.4.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(15.6.dp)) {
                DialogPill("Keep editing", Color(0xFFB0B0B0), onClick = onDismiss)
                DialogPill("Discard changes", Color(0xFFEC5B62), onClick = vm::discardMaskingChanges)
            }
        }
    }
}

/** An outlined pill button of the reference's dialogs: its words and edge in [color]. */
@Composable
private fun DialogPill(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .height(39.9.dp)
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, color, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

/**
 * A mode's Cancel and Apply sideways, as the reference's: a 48.6 dp strip at the panel's right
 * where the tools stand otherwise, a hairline along it, the two stacked halfway down at its right
 * end; no title.
 */
/** The width of a mode's Cancel and Apply strip sideways (dp). */
private const val MODE_STRIP = 48.6f

@Composable
private fun ModeStrip(onCancel: () -> Unit, onApply: () -> Unit) {
    Column(
        Modifier
            .width(MODE_STRIP.dp)
            .fillMaxHeight()
            .background(Lc.toolRows)
            .drawBehind { drawRect(Lc.toolRowsHairline, Offset(2.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(13.6.dp, Alignment.CenterVertically),
    ) {
        BarButton(MdIcon.Close, "Cancel", size = 40.dp, tint = Lc.panelText, onClick = onCancel)
        BarButton(MdIcon.Check, "Apply", size = 40.dp, tint = Lc.panelText, onClick = onApply)
    }
}

/**
 * The photo. Pinch to zoom (and drag when zoomed in), double-tap to zoom in or out, swipe sideways
 * for the next or previous photo, and hold a finger still on it to see it before its edits.
 */
@Composable
private fun PhotoView(vm: AppViewModel, ed: EditorState, modifier: Modifier, sideways: Boolean, coveredEnd: Dp = 0.dp) {
    // how far in and where to the photo is (scale about the view's centre, then moved), and the swipe to a neighbour
    val zoomAnim = remember(ed.photoId) { Animatable(1f) }
    val panAnim = remember(ed.photoId) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val swipeAnim = remember(ed.photoId) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val zoom = zoomAnim.value
    val offset = panAnim.value
    val swipe = swipeAnim.value
    val density = LocalDensity.current
    val gap = with(density) { 16.dp.toPx() }
    val flingSpeed = with(density) { 400.dp.toPx() }
    // the crop tool shows the whole photo, unzoomed, and takes the gestures for its frame
    val curving = ed.tool == MainTool.EDIT && ed.section == EditSection.LIGHT && ed.sub == "curve"
    val target = ed.targeting
    val targeting = target != null && ed.tool == MainTool.EDIT &&
        ((target == "curve" && curving) || (target != "curve" && ed.section == EditSection.COLOR && ed.sub == "mix"))
    val cropping = ed.tool == MainTool.CROP || ed.tool == MainTool.REMOVE || (ed.tool == MainTool.MASKING && ed.selectedMask != null) || curving || targeting
    // a sideways swipe opens the next photo only where the reference allows it: not in Presets,
    // Profiles, Masking, Remove or Crop (there a slip of the finger shouldn't leave the photo)
    val swipeable = when (ed.tool) {
        MainTool.PRESETS, MainTool.MASKING, MainTool.REMOVE, MainTool.CROP -> false
        MainTool.EDIT -> ed.section != EditSection.PROFILES
        else -> true
    }
    // Masking and Remove keep the reference's "free scroll": one finger is the tool's, two move and
    // zoom the photo, which may then be dragged well past its edges (even unzoomed, from under a panel)
    val freeScroll = ed.tool == MainTool.MASKING || ed.tool == MainTool.REMOVE
    LaunchedEffect(cropping, freeScroll) {
        if (cropping && !freeScroll) {
            zoomAnim.snapTo(1f)
            panAnim.snapTo(Offset.Zero)
        } else if (!freeScroll && zoomAnim.value <= 1f && panAnim.value != Offset.Zero) {
            // out of free scroll, an unzoomed photo goes back to its place
            panAnim.animateTo(Offset.Zero, tween(250, easing = AccelerateDecelerate))
        }
    }
    // once a zoom or a move settles, the part on screen is rendered sharp
    LaunchedEffect(ed.photoId, zoom, offset) {
        delay(250)
        vm.onViewMoved(zoom, offset)
    }
    // the neighbours a swipe brings in, shown from their thumbnails until they're open
    val next = remember(ed.photoId, vm.photos) { vm.neighbourInfo(1) }
    val previous = remember(ed.photoId, vm.photos) { vm.neighbourInfo(-1) }
    val nextLook by produceState<ImageBitmap?>(null, next?.id) { value = next?.let { vm.thumbnail(it) } }
    val previousLook by produceState<ImageBitmap?>(null, previous?.id) { value = previous?.let { vm.thumbnail(it) } }
    Box(
        modifier
            .background(Lc.canvas)
            // zoomed in, the photo runs on under the status bar, the top bar and round the tools, as the
            // reference's does (issue 5); fitted, it stays in its room
            .then(if (zoom <= 1f) Modifier.clipToBounds() else Modifier)
            .onSizeChanged { vm.onViewport(it) }
            .pointerInput(ed.photoId, freeScroll) {
                if (!freeScroll) return@pointerInput
                fun clamp(o: Offset, z: Float): Offset {
                    val b = freeBounds(size, ed.image, z)
                    return Offset(o.x.coerceIn(-b.x, b.x), o.y.coerceIn(-b.y, b.y))
                }
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        // seen before the tool's overlay: two fingers are the photo's, and the overlay lets go
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            val z0 = zoomAnim.value
                            val z1 = (z0 * ev.calculateZoom()).coerceIn(1f, maxZoom(size, ed.image, ed.info.width.toInt()))
                            val k = z1 / z0
                            val c = ev.calculateCentroid(useCurrent = true) - Offset(size.width / 2f, size.height / 2f)
                            val o = clamp(c * (1f - k) + panAnim.value * k + ev.calculatePan(), z1)
                            scope.launch {
                                zoomAnim.snapTo(z1)
                                panAnim.snapTo(o)
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(ed.photoId, cropping, swipeable, freeScroll) {
                if (cropping) return@pointerInput
                // the furthest a zoomed photo may move, and where a zoom keeps the point under the fingers
                fun bounds(z: Float) = if (freeScroll) freeBounds(size, ed.image, z) else zoomBounds(size, ed.image?.let { IntSize(it.width, it.height) }, z)
                fun clamp(o: Offset, z: Float): Offset {
                    val b = bounds(z)
                    return Offset(o.x.coerceIn(-b.x, b.x), o.y.coerceIn(-b.y, b.y))
                }
                fun about(at: Offset, from: Float, to: Float, o: Offset): Offset {
                    val k = to / from
                    val c = at - Offset(size.width / 2f, size.height / 2f)
                    return clamp(c * (1f - k) + o * k, to)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // a press a button over the photo took (Masking's +, say) isn't a tap or a hold on the photo
                    val onButton = down.isConsumed
                    val slop = viewConfiguration.touchSlop
                    var dx = 0f
                    var travel = 0f
                    // true: lifted (a tap), false: moved or a second finger, null: held still
                    val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var lifted: Boolean? = null
                        while (lifted == null) {
                            val ev = awaitPointerEvent()
                            when {
                                ev.changes.none { it.pressed } -> lifted = true
                                ev.changes.count { it.pressed } > 1 -> lifted = false
                                else -> {
                                    val pan = ev.calculatePan()
                                    dx += pan.x
                                    travel += pan.getDistance()
                                    if (travel > slop) lifted = false
                                }
                            }
                        }
                        lifted
                    }
                    if (onButton && lifted != false) return@awaitEachGesture
                    if (lifted == null) {
                        vm.showBefore(true)
                        do {
                            val ev = awaitPointerEvent()
                        } while (ev.changes.any { it.pressed })
                        vm.showBefore(false)
                        return@awaitEachGesture
                    }
                    if (lifted) {
                        val image = ed.image
                        if (ed.pickingWb) {
                            if (image != null) photoPoint(down.position, size, image.width, image.height, zoomAnim.value, panAnim.value)?.let { vm.pickWhiteBalance(it.x, it.y) }
                            return@awaitEachGesture
                        }
                        // a tap with no second soon after: the photo alone on the screen, or everything back
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                        if (second == null) {
                            ed.immersive = !ed.immersive
                            return@awaitEachGesture
                        }
                        // a double-tap: in to the photo's own pixels (1:1) about the tap, or back out to it all (400 ms)
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var p = second.position
                            do {
                                val ev = awaitPointerEvent()
                                ev.changes.firstOrNull { it.id == second.id }?.let { p = it.position }
                            } while (ev.changes.any { it.pressed })
                            p
                        } ?: return@awaitEachGesture
                        val z0 = zoomAnim.value
                        val o0 = panAnim.value
                        val fit = image?.let { min(size.width / it.width.toFloat(), size.height / it.height.toFloat()) * it.width } ?: size.width.toFloat()
                        val full = (ed.info.width.toFloat() / fit.coerceAtLeast(1f)).coerceIn(1.5f, maxZoom(size, image, ed.info.width.toInt()))
                        val z1 = if (z0 > 1.01f) 1f else full
                        val o1 = if (z1 <= 1f) Offset.Zero else about(up, z0, z1, o0)
                        scope.launch { zoomAnim.animateTo(z1, tween(400, easing = AccelerateDecelerate)) }
                        scope.launch { panAnim.animateTo(o1, tween(400, easing = AccelerateDecelerate)) }
                        return@awaitEachGesture
                    }
                    var pinched = false
                    val tracker = VelocityTracker()
                    do {
                        val ev = awaitPointerEvent()
                        val fingers = ev.changes.count { it.pressed }
                        ev.changes.firstOrNull { it.id == down.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        if (fingers == 0) {
                            // the last finger lifted: nothing to move (with no finger down there's no
                            // centroid, and a move about it would leave the photo nowhere: a black view)
                        } else if (freeScroll && fingers >= 2) {
                            // the free scroll's own handler has it
                            pinched = true
                        } else if (fingers >= 2 || zoomAnim.value > 1f) {
                            pinched = pinched || fingers >= 2
                            val z0 = zoomAnim.value
                            val z1 = (z0 * ev.calculateZoom()).coerceIn(1f, maxZoom(size, ed.image, ed.info.width.toInt()))
                            // the pinch keeps what's between the fingers under them, the pan moves it along
                            val o = if (z1 <= 1f) Offset.Zero else clamp(about(ev.calculateCentroid(useCurrent = true), z0, z1, panAnim.value) + ev.calculatePan(), z1)
                            scope.launch {
                                zoomAnim.snapTo(z1)
                                panAnim.snapTo(o)
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!pinched && swipeable) {
                            dx += ev.calculatePan().x
                            // a swipe moves the photo with the finger, a neighbour following 16 dp behind
                            val edge = !vm.hasNeighbour(if (dx < 0) 1 else -1)
                            val shown = if (edge) dx / 3f else dx
                            scope.launch { swipeAnim.snapTo(shown) }
                        }
                    } while (ev.changes.any { it.pressed })
                    val v = tracker.calculateVelocity()
                    if (freeScroll && pinched) return@awaitEachGesture
                    if (zoomAnim.value > 1f) {
                        // a zoomed photo coasts on after a flick, stopping at its edges
                        val b = bounds(zoomAnim.value)
                        panAnim.updateBounds(Offset(-b.x, -b.y), Offset(b.x, b.y))
                        scope.launch { panAnim.animateDecay(Offset(v.x, v.y), exponentialDecay()) }
                        return@awaitEachGesture
                    }
                    if (pinched || !swipeable) return@awaitEachGesture
                    val step = if (dx < 0) 1 else -1
                    // past 60 % of the way, or flicked that way: the neighbour slides in and opens
                    val commit = vm.hasNeighbour(step) && (abs(dx) > size.width * 0.6f || (abs(v.x) > flingSpeed && v.x * dx > 0 && abs(dx) > slop))
                    scope.launch {
                        if (commit) {
                            swipeAnim.animateTo(-step * (size.width + gap), tween(250, easing = AccelerateDecelerate))
                            vm.showNeighbour(step)
                        } else {
                            swipeAnim.animateTo(0f, tween(200, easing = AccelerateDecelerate))
                        }
                    }
                }
            },
    ) {
        val image = ed.image
        val cropFrame = ed.crop
        // (Geometry's Guided Upright shows the uncropped photo alone, to draw guides on)
        if (ed.tool == MainTool.CROP && ed.cropTab != "geometry" && ed.showingCropView && cropFrame != null && image != null) {
            // Crop draws the photo itself: turned and moved under its level frame
            CropStage(vm, ed, cropFrame, image, Modifier.fillMaxSize())
        } else if (image != null) {
            Image(
                image,
                contentDescription = ed.info.fileName,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offset.x + swipe
                    translationY = offset.y
                },
            )
            // zoomed in, the part on screen rendered sharp, over the photo it was cut from
            val detail = ed.detail?.takeIf { it.base === image && zoom > 1f }
            if (detail != null) {
                Canvas(
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x + swipe
                        translationY = offset.y
                    },
                ) {
                    // where the photo is drawn fitted (as the Image above places it), then the window on it
                    val fit = min(size.width / image.width, size.height / image.height)
                    val w = (image.width * fit).roundToInt()
                    val h = (image.height * fit).roundToInt()
                    val s = w / detail.frame.width.toFloat()
                    withTransform({
                        translate(((size.width - w) / 2f).roundToInt() + detail.window.left * s, ((size.height - h) / 2f).roundToInt() + detail.window.top * s)
                        scale(s, s, Offset.Zero)
                    }) {
                        drawImage(detail.image, dstSize = IntSize(detail.image.width, detail.image.height), filterQuality = FilterQuality.High)
                    }
                }
            }
            // the neighbour being swiped in
            val incoming = if (swipe < 0) nextLook else if (swipe > 0) previousLook else null
            if (incoming != null) {
                Image(
                    incoming,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationX = swipe + (if (swipe < 0) 1 else -1) * (size.width + gap)
                    },
                )
            }
        } else {
            // the reference's 48 dp spinner, only once loading has taken half a second
            var slow by remember(ed.photoId) { mutableStateOf(false) }
            LaunchedEffect(ed.photoId) {
                delay(500)
                slow = true
            }
            if (slow) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Lc.highlight, trackColor = Lc.divider)
        }
        // the reference's: 300 x 94 dp at the top left, under the top bar; not while cropping, masking or removing
        val histogramShown = vm.viewOverlay == "histogram" && ed.tool != MainTool.CROP && ed.tool != MainTool.MASKING && ed.tool != MainTool.REMOVE
        if (histogramShown) {
            ed.histogram?.let {
                HistogramView(it, Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = 16.dp, top = 56.dp).widthIn(max = 300.dp).fillMaxWidth().height(94.dp))
            }
        }
        // the reference's File Info: where the photo is in the grid, its name and when it was taken; with no tool open
        if (vm.viewOverlay == "info" && ed.tool == null) FileInfo(vm, ed, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 62.5.dp))
        val geometry = ed.geometry
        // the tools' overlays moved and scaled with the photo (their touches come back in the photo's place)
        val onPhoto = Modifier.graphicsLayer {
            scaleX = zoomAnim.value
            scaleY = zoomAnim.value
            translationX = panAnim.value.x
            translationY = panAnim.value.y
        }
        val mask = ed.masks.firstOrNull { it.id == ed.selectedMask }
        val picker = ed.rangePicker
        if (ed.tool == MainTool.MASKING && picker != null && geometry != null && image != null && !ed.showingCropView) {
            RangePickerOverlay(vm, ed, picker, ed.masks.firstOrNull { it.id == picker.mask }, geometry, IntSize(image.width, image.height), Modifier.fillMaxSize().then(onPhoto))
        } else if (ed.tool == MainTool.MASKING && ed.maskSection != "new" && mask != null && geometry != null && image != null && !ed.showingCropView) {
            MaskOverlay(vm, ed, mask, geometry, IntSize(image.width, image.height), Modifier.fillMaxSize().then(onPhoto))
        }
        if (ed.tool == MainTool.REMOVE && !ed.showingCropView && geometry != null && image != null) {
            RemoveOverlay(vm, ed, geometry, IntSize(image.width, image.height), Modifier.fillMaxSize().then(onPhoto))
        }
        // Guided Upright: its guides on the photo without its crop, and a finger to draw more
        if (ed.tool == MainTool.CROP && ed.cropTab == "geometry" && ed.state?.upright == "guided" && ed.showingCropView && image != null) {
            LaunchedEffect(ed.photoId) { vm.refreshGuides() }
            GuidesOverlay(vm, ed, IntSize(image.width, image.height), Modifier.fillMaxSize())
        }
        val curve = ed.curve
        // targeting, the photo is in the clear: the curve's square steps aside
        if (curving && curve != null && !targeting && !sideways) CurveOverlay(vm, ed, curve, Modifier.fillMaxSize())
        if (targeting && image != null) TargetedOverlay(vm, IntSize(image.width, image.height), Modifier.fillMaxSize())
        // as the reference's: 16 dp from the right, its foot 51.6 dp above the photo's
        // the reference's tab against the right edge, 36 dp over the rows
        if (ed.tool == MainTool.MASKING && picker == null) {
            MaskColumn(vm, ed, Modifier.align(Alignment.BottomEnd).padding(end = coveredEnd, bottom = 36.dp))
            // the edited part's tools against the left edge, their foot level with the column's (the reference's)
            MaskPartBar(vm, ed, Modifier.align(Alignment.BottomStart).padding(bottom = 36.dp))
        }
        ed.state?.previewOnly?.let { why ->
            Text(
                "Shown from the camera's preview: $why",
                color = Lc.caution,
                fontFamily = Inter,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * How far in a pinch or a double-tap may take the photo, as the reference: to twice its own pixels
 * (a photo [photoWidth] pixels across, its render [image] fitted to [view]), and at least twice its
 * fitted size.
 */
private fun maxZoom(view: IntSize, image: ImageBitmap?, photoWidth: Int): Float {
    val drawn = if (image == null || image.width <= 0 || image.height <= 0) view.width.toFloat() else min(view.width / image.width.toFloat(), view.height / image.height.toFloat()) * image.width
    return max(2f, 2f * photoWidth / drawn.coerceAtLeast(1f))
}

/**
 * The part of a zoomed view to render sharp over the whole-photo render [image] (fitted to [view]),
 * at [zoom] about the view's centre, moved by [pan]: the zoomed frame's size (the photo drawn [zoom]
 * times its fitted size, never past its own [photoWidth] pixels across nor [maxEdge] pixels along
 * its long edge) and the window of that frame on screen, in its pixels, with a margin so a small
 * move stays sharp. Null: nothing to add (not zoomed past what [image] holds, or none of the photo
 * on screen).
 */
internal fun detailWindow(view: IntSize, image: IntSize, photoWidth: Int, maxEdge: Int, zoom: Float, pan: Offset): Pair<IntSize, IntRect>? {
    if (view.width <= 0 || view.height <= 0 || image.width <= 0 || image.height <= 0 || !zoom.isFinite() || zoom <= 0f) return null
    val fit = min(view.width / image.width.toFloat(), view.height / image.height.toFloat())
    val dw = image.width * fit
    val dh = image.height * fit
    // the frame's scale over the fitted photo: the zoom, but past 100 % (or the phone's limit) the
    // window is magnified
    val k = min(min(zoom, max(photoWidth / dw, 1f)), maxEdge / max(dw, dh))
    if (dw * k <= image.width * 1.1f) return null
    val frame = IntSize((dw * k).roundToInt(), (dh * k).roundToInt())
    val s = frame.width / dw
    // the view's corners on the unzoomed view, then in the frame's pixels
    val c = Offset(view.width / 2f, view.height / 2f)
    val tl = (Offset.Zero - c - pan) / zoom + c
    val br = (Offset(view.width.toFloat(), view.height.toFloat()) - c - pan) / zoom + c
    val left = (view.width - dw) / 2f
    val top = (view.height - dh) / 2f
    val margin = 32f
    val x0 = ((tl.x - left) * s - margin).toInt().coerceIn(0, frame.width)
    val y0 = ((tl.y - top) * s - margin).toInt().coerceIn(0, frame.height)
    val x1 = ceil((br.x - left) * s + margin).toInt().coerceIn(0, frame.width)
    val y1 = ceil((br.y - top) * s + margin).toInt().coerceIn(0, frame.height)
    if (x1 <= x0 || y1 <= y0) return null
    return frame to IntRect(x0, y0, x1, y1)
}

/**
 * How far a zoomed photo may move, either way from the middle, at zoom [z] in a view of [view]: as
 * far as keeps its edges at the view's (the reference's), and not at all along a side where it is
 * smaller than the view (there it stays centred).
 */
internal fun zoomBounds(view: IntSize, image: IntSize?, z: Float): Offset {
    val fit = if (image == null || image.width <= 0 || image.height <= 0) 1f else min(view.width / image.width.toFloat(), view.height / image.height.toFloat())
    val w = (image?.width ?: view.width) * fit
    val h = (image?.height ?: view.height) * fit
    return Offset(max(0f, (w * z - view.width) / 2f), max(0f, (h * z - view.height) / 2f))
}

/**
 * How far the photo may move in free scroll (Masking, Remove), either way from the middle, at zoom
 * [z] in a view of [view]: it keeps covering the middle fifth of the view, the reference's 40 % inset.
 */
private fun freeBounds(view: IntSize, image: ImageBitmap?, z: Float): Offset {
    val fit = if (image == null) 1f else min(view.width / image.width.toFloat(), view.height / image.height.toFloat())
    val w = (image?.width ?: view.width) * fit
    val h = (image?.height ?: view.height) * fit
    return Offset(max(0f, w * z / 2f - view.width * 0.1f), max(0f, h * z / 2f - view.height * 0.1f))
}

/**
 * Targeted adjustment over the photo: press on what to adjust, then drag up to raise it or down to
 * lower it (half a slider unit a dp, as the desktop's); one undo step a drag.
 */
@Composable
private fun TargetedOverlay(vm: AppViewModel, imageSize: IntSize, modifier: Modifier) {
    // read when a finger goes down: the drag's draft renders change the image's size
    val currentSize by rememberUpdatedState(imageSize)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val image = currentSize
                val at = photoPoint(down.position, size, image.width, image.height, 1f, Offset.Zero) ?: return@awaitEachGesture
                down.consume()
                vm.beginTargeted()
                var acc = 0.0
                var last = down.position.y
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        acc -= (change.position.y - last) / density * 0.5
                        last = change.position.y
                        val step = acc.toInt()
                        if (step != 0) {
                            acc -= step
                            vm.targeted(at.x, at.y, step.toDouble())
                        }
                    }
                } finally {
                    vm.endTargeted()
                }
            }
        },
    )
}

/**
 * Where [p], a point of the view, falls on the photo: 0–1 across and down (null off the photo).
 * The photo is fitted to the view, then zoomed by [zoom] about the view's centre and moved by [pan].
 * The view shows the whole photo (the phone has no crop yet), so this is a point of the photo itself.
 */
internal fun photoPoint(p: Offset, view: IntSize, imageWidth: Int, imageHeight: Int, zoom: Float, pan: Offset): Offset? {
    if (view.width <= 0 || view.height <= 0 || imageWidth <= 0 || imageHeight <= 0 || zoom <= 0f) return null
    val c = Offset(view.width / 2f, view.height / 2f)
    val q = (p - c - pan) / zoom + c
    val fit = min(view.width / imageWidth.toFloat(), view.height / imageHeight.toFloat())
    val w = imageWidth * fit
    val h = imageHeight * fit
    val x = (q.x - (view.width - w) / 2f) / w
    val y = (q.y - (view.height - h) / 2f) / h
    return if (x in 0f..1f && y in 0f..1f) Offset(x, y) else null
}

/**
 * View options, as the reference's: a sheet titled over a line, then what shows over the photo: no
 * overlay, its file info or the histogram; the one shown blue with a tick. It stays open.
 */
@Composable
fun ViewOptionsSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    LcSheet(onDismiss, inset = 12.6.dp) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
            Text("View options", color = Lc.panelText, fontFamily = Inter, fontSize = 15.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.panelButtonBorder))
        listOf("none" to "No Overlay", "info" to "File Info", "histogram" to "Histogram").forEach { (id, label) ->
            val on = vm.viewOverlay == id
            Row(
                Modifier.fillMaxWidth().height(48.dp).selectable(on, role = Role.RadioButton) { vm.showOverlay(id) }.padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { if (on) MdIcon(MdIcon.Check, null, tint = Lc.primary, size = 22.dp) }
                Spacer(Modifier.width(16.dp))
                Text(label, color = if (on) Lc.primary else Lc.panelText, fontFamily = Inter, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * File Info over the photo, as the reference's: "3 of 41" over one of five sets of lines, a tap on
 * them going to the next (kept): the file's name, the day and time it was taken (or, without a
 * capture time, added) and its flag and stars when it has any; the camera's settings, the focal
 * length and lens, and the camera; the name, title, caption and copyright; the name, day and time
 * and copyright; the settings, the size (and the original's when cropped) and the day and time.
 * White, no card, a line every 22.4 dp.
 */
@Composable
private fun FileInfo(vm: AppViewModel, ed: EditorState, modifier: Modifier) {
    val at = vm.photos.indexOfFirst { it.id == ed.photoId }
    val variant = vm.fileInfoVariant
    // the library keeps when a photo was added in UTC, without saying so
    val taken = remember(ed.info.captured, ed.info.imported) { ed.info.captured?.let(::takenAt) ?: takenAt(ed.info.imported.take(19) + "Z") }
    // the camera's settings and the text fields come from the photo's metadata
    LaunchedEffect(ed.photoId, variant) { if (variant != 0 && vm.photoMetaOf != ed.photoId) vm.loadPhotoMeta() }
    val meta = vm.photoMeta.takeIf { vm.photoMetaOf == ed.photoId }
    val size by produceState<ImageSize?>(null, ed.photoId, ed.info.settingsHash, variant) { if (variant == 4) value = vm.exportSize(ed, ExportChoice()) }
    val shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 3f)
    val style = TextStyle(color = Color.White, fontFamily = Inter, fontSize = 14.sp, shadow = shadow)
    val settings = meta?.let { m ->
        val speed = listOfNotNull(
            m.shutter.takeIf { it.isNotBlank() }?.let(::shutterText),
            m.aperture?.let { "at f/${it.toBigDecimal().stripTrailingZeros().toPlainString()}" },
        ).joinToString(" ")
        listOfNotNull(speed.takeIf { it.isNotBlank() }, m.iso?.let { "ISO $it" }).joinToString(", ").ifBlank { "No camera settings" }
    }
    val lines: List<String> = when (variant) {
        1 -> listOfNotNull(
            settings,
            meta?.let { m -> listOfNotNull(m.focalMm?.let { "${it.roundToInt()} mm" }, m.lens.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { "Lens unknown" } },
            meta?.let { m -> m.camera.ifBlank { "Camera unknown" } },
        )
        2 -> listOfNotNull(
            ed.info.fileName,
            meta?.let { m -> m.title.ifBlank { "No title" } },
            meta?.let { m -> m.caption.ifBlank { "No caption" } },
            meta?.let { m -> m.copyright.ifBlank { "No copyright" } },
        )
        3 -> listOfNotNull(ed.info.fileName, taken, meta?.let { m -> m.copyright.ifBlank { "No copyright" } })
        4 -> {
            val w = size?.width ?: ed.info.width
            val h = size?.height ?: ed.info.height
            val original = if (w != ed.info.width || h != ed.info.height) "  (${ed.info.width} X ${ed.info.height})" else ""
            listOfNotNull(settings, "$w X $h$original", taken)
        }
        else -> listOfNotNull(ed.info.fileName, taken)
    }
    Column(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { vm.nextFileInfo() }
            .semantics { contentDescription = "File info: tap for other details" },
        verticalArrangement = Arrangement.spacedBy(5.5.dp),
    ) {
        if (at >= 0) Text("${at + 1} of ${vm.photos.size}", style = style)
        lines.forEach { Text(it, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // the first set: its flag and stars, when it has any (16 dp each)
        if (variant == 0 && (ed.info.rating > 0u || ed.info.flag != PhotoFlag.UNFLAGGED)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (ed.info.flag) {
                    PhotoFlag.PICK -> LcIcon(LcIcon.FlagPickSolid, "Pick", tint = Color.White, size = 16.dp, modifier = Modifier.padding(end = 5.dp))
                    PhotoFlag.REJECT -> LcIcon(LcIcon.FlagRejectSolid, "Reject", tint = Color.White, size = 16.dp, modifier = Modifier.padding(end = 5.dp))
                    else -> {}
                }
                repeat(5) { i -> LcIcon(if (i < ed.info.rating.toInt()) LcIcon.StarFilled else LcIcon.Star, null, tint = Color.White, size = 16.dp) }
            }
        }
    }
}

/**
 * An ISO 8601 time as the reference writes it: `Jan 1, 2027 | 12:00:00 PM` (null if it can't be
 * read). A camera's time reads as it was on the camera's clock; a UTC one (`…Z`, an import's) in
 * the phone's own time.
 */
internal fun takenAt(iso: String): String? = runCatching {
    val t = if (iso.endsWith("Z")) {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
    } else {
        java.time.LocalDateTime.parse(iso.take(19))
    }
    val day = t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.getDefault()))
    val time = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm:ss a", java.util.Locale.getDefault()))
    "$day\u2009|\u2009$time"
}.getOrNull()

/**
 * Reset's and Apply from previous's choices, as the reference's: a heading (17 sp) over a line, then
 * a 48 dp row a choice, each with what it would make of the photo (60 × 40 dp) before its name.
 */
@Composable
private fun ChoiceMenu(title: String, choices: List<Pair<String, String>>, preview: suspend (String) -> ImageBitmap?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val density = LocalDensity.current
    Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { IntOffset(-(8.dp).roundToPx(), (56.dp + 4.dp).roundToPx()) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .clip(RoundedCornerShape(5.dp))
                .background(Lc.menu)
                .border(1.dp, Lc.menuEdge, RoundedCornerShape(5.dp))
                .padding(vertical = 8.dp),
        ) {
            Text(title, color = Color(0xFFEFEFEF), fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(Color(0xFF494949)))
            choices.forEach { (id, label) ->
                var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(id) { image = preview(id) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            onDismiss()
                            onPick(id)
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(60.dp, 40.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                        image?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = Lc.menuText, fontFamily = Inter, fontSize = 17.sp, maxLines = 1)
                }
            }
        }
    }
}
