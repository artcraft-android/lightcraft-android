package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.engine.DeviceAccess
import ai.storyteller.lightcraft.engine.DevicePhoto
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

/** The bottom navigation's labels and icons when not chosen (the chosen tab's are blue). */
private val tabLabel = Color(0xFFC8C8C8)
private val tabIcon = Color(0xFFA2A2A2)

/**
 * The home screen's bottom navigation, as the reference's: 64.2 dp, a 24 dp icon 10.2 dp from its
 * top over a label, 175.1 dp a tab. Two tabs, the phone's gallery and LightCraft's library, in the
 * middle (the reference's third, its online community, has no counterpart: issue 6).
 */
@Composable
fun HomeNav(tab: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    // sideways the reference's tabs are 168 dp
    val config = LocalConfiguration.current
    val width = if (config.screenWidthDp > config.screenHeightDp) 168.dp else 175.1.dp
    Row(modifier.fillMaxWidth().background(Lc.bar).height(64.2.dp), horizontalArrangement = Arrangement.Center) {
        HomeTab(LcIcon.DevicePhotos, "Gallery", tab == "device", width) { onTab("device") }
        HomeTab(LcIcon.Library, "LightCraft", tab == "library", width) { onTab("library") }
    }
}

/**
 * Sideways the tabs stand in a rail at the left, as the reference's: 80.3 dp wide, a tab 59.3 dp
 * tall every 67.1 dp, in the middle of the height (its icon 7.8 dp from the tab's top).
 */
@Composable
fun HomeRail(tab: String, onTab: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.8.dp)) {
        HomeTab(LcIcon.DevicePhotos, "Gallery", tab == "device", 80.3.dp, Modifier.height(59.3.dp), iconTop = 7.8.dp) { onTab("device") }
        HomeTab(LcIcon.Library, "LightCraft", tab == "library", 80.3.dp, Modifier.height(59.3.dp), iconTop = 7.8.dp) { onTab("library") }
    }
}

@Composable
private fun HomeTab(icon: LcIcon, label: String, selected: Boolean, width: Dp, modifier: Modifier = Modifier.fillMaxHeight(), iconTop: Dp = 10.2.dp, onClick: () -> Unit) {
    Column(
        modifier.width(width).clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(iconTop))
        LcIcon(icon, null, tint = if (selected) Lc.highlight else tabIcon, size = 24.dp)
        // the label's letters as high and as tall as the reference's (Inter sits lower in its line)
        Text(label, color = if (selected) Lc.highlight else tabLabel, fontFamily = Inter, fontSize = 13.sp, maxLines = 1, modifier = Modifier.offset(y = (-0.4).dp))
    }
}

/** The Gallery tab's kinds of photo (its filter's): `jpg` (JPEGs, PNGs and the like) and `raw`. */
val deviceKindsAll = setOf("jpg", "raw")

/** Raw files, by their extension (the filter's Raws; everything else is with the JPEGs). */
private val rawExtensions = setOf(
    "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl", "raw", "pef", "ptx",
    "srw", "3fr", "fff", "iiq", "dcr", "kdc", "erf", "mef", "mos", "mrw", "x3f", "gpr",
)

/** A gallery photo's kind: `raw` or `jpg`. */
fun deviceKind(p: DevicePhoto): String =
    if (p.name.substringAfterLast('.', "").lowercase() in rawExtensions || p.mime == "image/x-adobe-dng" || p.mime == "image/dng") "raw" else "jpg"

/**
 * The Gallery tab's title row, as the reference's: the folder shown (a tap opens the folders) with
 * "Filtered" under it while a kind of photo is left out; at the right, more photos (when LightCraft
 * sees only some), the filter and the order (newest or oldest first; the icon turns over).
 */
@Composable
fun DeviceHeader(vm: AppViewModel, wide: Boolean, filterOpen: Boolean, onFolders: () -> Unit, onFilter: () -> Unit, onMoreAccess: () -> Unit, onOrder: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = !wide && vm.deviceAccess != DeviceAccess.NONE, role = Role.DropdownList, onClick = onFolders)
                    .padding(start = 16.dp, end = 12.dp)
                    .semantics { contentDescription = "${vm.deviceAlbum ?: "Photos on device"}: change folder" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.deviceAlbum?.ifBlank { "Other" } ?: "Photos on device",
                    color = Lc.text,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                )
                if (!wide && vm.deviceAccess != DeviceAccess.NONE) {
                    Spacer(Modifier.width(5.dp))
                    MdIcon(MdIcon.ExpandMore, null, tint = Lc.text)
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                vm.deviceFiltered,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.5.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
            ) {
                Text("Filtered", color = Lc.selected, fontFamily = Inter, fontSize = 11.sp, lineHeight = 13.sp)
            }
        }
        if (vm.deviceAccess != DeviceAccess.NONE) {
            Row {
                if (!vm.selecting && vm.deviceAccess == DeviceAccess.PARTIAL) BarButton(MdIcon.Add, "Select photos", size = 48.dp, tint = Lc.text, onClick = onMoreAccess)
                if (!vm.selecting) {
                    BarButton(MdIcon.FilterAlt, if (vm.deviceFiltered) "Filter (on)" else "Filter", size = 48.dp, tint = if (vm.deviceFiltered) Lc.selected else Lc.text, active = filterOpen, onClick = onFilter)
                }
                // the order: the icon turns over for oldest first
                val turn by androidx.compose.animation.core.animateFloatAsState(if (vm.deviceAscending) -1f else 1f, label = "order")
                Box(Modifier.graphicsLayer { scaleY = turn }) {
                    BarButton(LcIcon.Sort, if (vm.deviceAscending) "Toggle sort order (oldest first)" else "Toggle sort order (newest first)", size = 48.dp, tint = Lc.text, onClick = onOrder)
                }
            }
        }
    }
}

/** Grid Settings (the bar's ⋮ on the Gallery tab), as the reference's: Select, then the headings. */
@Composable
fun DeviceGridSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    LcSheet(onDismiss) {
        SheetRow(LcIcon.SelectBox, "Select", enabled = vm.shownDevicePhotos.isNotEmpty()) {
            onDismiss()
            vm.startDeviceSelectingNone()
        }
        SheetDivider()
        SheetHeading("Segmentation")
        segmentUnits.forEach { (unit, label) -> RadioRow(label, vm.deviceSegment == unit) { vm.setDeviceSegments(unit) } }
        Spacer(Modifier.height(8.dp))
    }
}

/** The Gallery tab's filter, as the reference's: a box for each kind of photo, at least one ticked. */
@Composable
fun DeviceFilterSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    LcSheet(onDismiss) {
        SheetHeading("Filters")
        SheetDivider()
        listOf(Triple("jpg", LcIcon.Photos, "JPGs / PNGs"), Triple("raw", LcIcon.Camera, "Raws")).forEach { (kind, icon, label) ->
            val on = kind in vm.deviceKinds
            SheetRow(icon, label, trailing = { MdIcon(if (on) MdIcon.CheckBox else MdIcon.CheckBoxOutline, if (on) "Shown" else "Hidden", tint = if (on) Lc.check else Lc.text) }) {
                vm.toggleDeviceKind(kind)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Import's question when the library has albums, as the reference's (no title): the library, or an album too. */
@Composable
fun DeviceImportSheet(onDismiss: () -> Unit, onLibrary: () -> Unit, onAlbum: () -> Unit) {
    LcSheet(onDismiss) {
        SheetRow(LcIcon.Import, "Import into LightCraft") {
            onDismiss()
            onLibrary()
        }
        SheetRow(LcIcon.Photos, "Import & add to LightCraft album") { onAlbum() }
        Spacer(Modifier.height(8.dp))
    }
}

/** An app on the phone that can pick a picture (the folders' Open from). */
class PickerApp(val component: android.content.ComponentName, val label: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

/** What the folders open a photo with: another app's picker, or the system's (Browse). */
class GalleryPickers(val apps: List<PickerApp>, val openWith: (PickerApp) -> Unit, val browse: () -> Unit)

/**
 * The folders' Open from: the apps that pick pictures (the last one used first) and the system's
 * picker; the photo picked opens in the editor, as a gallery photo.
 */
@Composable
fun rememberGalleryPickers(vm: AppViewModel): GalleryPickers {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("library", android.content.Context.MODE_PRIVATE) }
    var last by remember { mutableStateOf(prefs.getString("galleryOpenFrom", null)) }
    val apps = remember(last) {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE)
        runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            // the system's own picker is Browse
            .filter { it.activityInfo.packageName != context.packageName && !it.activityInfo.packageName.contains("documentsui") }
            .distinctBy { it.activityInfo.packageName }
            .map { r ->
                val icon = runCatching { r.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                PickerApp(android.content.ComponentName(r.activityInfo.packageName, r.activityInfo.name), r.loadLabel(pm).toString(), icon)
            }
            .sortedBy { if (it.component.packageName == last) 0 else 1 }
    }
    val withApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> r.data?.data?.let { vm.openPickedPhoto(it) } }
    val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.openPickedPhoto(it) } }
    return GalleryPickers(
        apps,
        openWith = { app ->
            prefs.edit().putString("galleryOpenFrom", app.component.packageName).apply()
            last = app.component.packageName
            runCatching {
                withApp.launch(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(android.content.Intent.CATEGORY_OPENABLE).setComponent(app.component))
            }.onFailure { vm.tell("${app.label} couldn't be opened") }
        },
        browse = { runCatching { browse.launch(arrayOf("image/*")) } },
    )
}

/**
 * The Gallery tab's folders, as the reference's tray (most of the screen's height): a note when
 * LightCraft sees only some photos; Open from (apps that pick a picture, and Browse); then the
 * phone's folders as tiles (all photos first), the one shown outlined. A tap shows a folder.
 */
@Composable
fun FolderTray(vm: AppViewModel, pickers: GalleryPickers, onDismiss: () -> Unit, onMoreAccess: () -> Unit) {
    val drag = rememberSheetDrag(onDismiss)
    val tall = if (android.os.Build.VERSION.SDK_INT >= 35) 0.85f else 0.9f
    LcOverlay(onDismiss, Alignment.BottomCenter) {
        Column(
            Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .fillMaxHeight(tall)
                .onSizeChanged { drag.height = it.height }
                .offset { IntOffset(0, drag.offset.roundToInt()) }
                .background(Lc.bar)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .nestedScroll(drag.connection),
        ) {
            FolderList(vm, pickers, onMoreAccess) { onDismiss() }
        }
    }
}

/** Sideways the folders are beside the photos, as the reference's. */
@Composable
fun FolderPane(vm: AppViewModel, pickers: GalleryPickers, onMoreAccess: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { FolderList(vm, pickers, onMoreAccess) {} }
}

@Composable
private fun FolderList(vm: AppViewModel, pickers: GalleryPickers, onMoreAccess: () -> Unit, onPicked: () -> Unit) {
    val folders = remember(vm.devicePhotos) {
        vm.devicePhotos.groupBy { it.album }.map { (album, photos) -> album to photos }.sortedByDescending { it.second.size }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (vm.deviceAccess == DeviceAccess.PARTIAL) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "partial") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LightCraft sees only the photos you picked.", color = Lc.textDim, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        "Change",
                        color = Lc.selected,
                        fontFamily = Inter,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onMoreAccess).padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "openFrom") { SheetHeading("Open from") }
        item(span = { GridItemSpan(maxLineSpan) }, key = "apps") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pickers.apps.forEach { app ->
                    OpenFromEntry(app.label, { app.icon?.let { Image(it, null, Modifier.size(40.dp)) } }) {
                        onPicked()
                        pickers.openWith(app)
                    }
                }
                OpenFromEntry("Browse", { LcIcon(LcIcon.Folder, null, tint = Lc.text, size = 32.dp) }) {
                    onPicked()
                    pickers.browse()
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "albums") { SheetHeading("Albums") }
        item(key = "all") { FolderTile(vm, "Photos on device", vm.devicePhotos.size, null, vm.deviceAlbum == null) { vm.showDeviceAlbum(null); onPicked() } }
        items(folders, key = { "f:${it.first}" }) { (album, photos) ->
            FolderTile(vm, album.ifBlank { "Other" }, photos.size, photos.firstOrNull(), vm.deviceAlbum == album) { vm.showDeviceAlbum(album); onPicked() }
        }
    }
}

@Composable
private fun OpenFromEntry(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = Lc.text, fontFamily = Inter, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A folder: a square of its newest photo (all photos: an icon), its name and how many. */
@Composable
private fun FolderTile(vm: AppViewModel, name: String, count: Int, cover: DevicePhoto?, chosen: Boolean, onClick: () -> Unit) {
    val thumb by produceState(cover?.let { vm.peekDeviceThumbnail(it) }, cover?.id) { cover?.let { c -> vm.deviceThumbnail(c)?.let { value = it } } }
    Column(Modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick).semantics { selected = chosen }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Lc.cell)
                .then(if (chosen) Modifier.border(2.dp, Lc.selected, RoundedCornerShape(4.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (cover == null) {
                LcIcon(LcIcon.DevicePhotos, null, tint = Lc.text, size = 36.dp)
            } else {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(name, color = Lc.text, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("$count", color = Lc.textDim, fontFamily = Inter, fontSize = 12.sp)
    }
}

/** Import's question about photos the library has already, as the reference's: them, then import them anyway or skip them. */
@Composable
fun DuplicateDialog(vm: AppViewModel) {
    val q = vm.duplicateQuestion ?: return
    val n = q.photos.size
    AlertDialog(
        onDismissRequest = { q.answer(null) },
        containerColor = Lc.dialog,
        title = { Text(if (n == 1) "This photo is in LightCraft already" else "These $n photos are in LightCraft already", fontFamily = Inter, color = Lc.text, fontSize = 18.sp) },
        text = {
            LazyVerticalGrid(GridCells.Adaptive(64.dp), Modifier.heightIn(max = 260.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(q.photos, key = { it.id }) { p ->
                    val thumb by produceState(vm.peekDeviceThumbnail(p), p.id) { vm.deviceThumbnail(p)?.let { value = it } }
                    Box(Modifier.aspectRatio(1f).background(Lc.cell)) { thumb?.let { Image(it, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = { q.answer(true) }) { Text("Import duplicate", fontFamily = Inter, color = Lc.selected) } },
        dismissButton = {
            TextButton(onClick = { q.answer(if (q.all) null else false) }) { Text(if (q.all) "Cancel" else "Skip import", fontFamily = Inter, color = Lc.text) }
        },
    )
}

/**
 * Deleting gallery photos, as the reference's: its question first (how many; they can't be brought
 * back; Don't ask again, unless there are more than a batch), then the system's request for each
 * batch, then what came of it.
 */
@Composable
fun DeviceDeletionHost(vm: AppViewModel, asking: Boolean, onAsked: () -> Unit) {
    val n = vm.deviceSelected.size
    var dontAsk by remember(asking) { mutableStateOf(false) }
    if (asking) {
        val many = n > AppViewModel.DELETE_BATCH
        AlertDialog(
            onDismissRequest = onAsked,
            containerColor = Lc.dialog,
            title = { Text(if (n == 1) "Delete 1 photo from this phone?" else "Delete $n photos from this phone?", fontFamily = Inter, color = Lc.text, fontSize = 18.sp) },
            text = {
                Column {
                    Text(if (n == 1) "The original of this photo is deleted from your phone." else "The originals of these $n photos are deleted from your phone.", fontFamily = Inter, color = Lc.textDim)
                    Text("They can't be brought back.", fontFamily = Inter, color = Lc.text, modifier = Modifier.padding(top = 8.dp))
                    if (many) {
                        Text("Your phone asks you to confirm each batch of up to ${AppViewModel.DELETE_BATCH}.", fontFamily = Inter, color = Lc.textDim, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Row(Modifier.padding(top = 8.dp).fillMaxWidth().toggleable(dontAsk, role = Role.Checkbox) { dontAsk = it }, verticalAlignment = Alignment.CenterVertically) {
                            MdIcon(if (dontAsk) MdIcon.CheckBox else MdIcon.CheckBoxOutline, null, tint = if (dontAsk) Lc.check else Lc.text)
                            Text("Don't ask again", fontFamily = Inter, color = Lc.text, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAsked()
                    if (dontAsk) vm.stopAskingBeforeDelete(true)
                    vm.startDeviceDelete()
                }) { Text("Continue", fontFamily = Inter, color = Lc.reject) }
            },
            dismissButton = { TextButton(onClick = onAsked) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
        )
    }
    val d = vm.deviceDeletion ?: return
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> vm.deleteBatchAnswered(r.resultCode == android.app.Activity.RESULT_OK) }
    if (!d.done) {
        LaunchedEffect(d, d.batch, d.answers) {
            vm.nextDeleteRequest()?.let { sender ->
                runCatching { request.launch(IntentSenderRequest.Builder(sender).build()) }.onFailure { vm.deleteBatchAnswered(false) }
            }
        }
        return
    }
    // cancelled at once (one batch, nothing gone): nothing to tell
    if (d.stopped && d.deleted == 0 && d.batches.size == 1) {
        LaunchedEffect(d) { vm.closeDeviceDeletion() }
        return
    }
    AlertDialog(
        onDismissRequest = { vm.closeDeviceDeletion() },
        containerColor = Lc.dialog,
        title = { Text(if (d.stopped) "Deletion stopped" else "Deletion complete", fontFamily = Inter, color = Lc.text, fontSize = 18.sp) },
        text = {
            Column {
                Text("${d.deleted} of ${d.uris.size} photos deleted.", fontFamily = Inter, color = Lc.text)
                if (d.stopped) {
                    Text("Stopped after ${d.batch} of ${d.batches.size} batches.", fontFamily = Inter, color = Lc.textDim, modifier = Modifier.padding(top = 6.dp))
                } else if (d.deleted < d.uris.size) {
                    Text("${d.uris.size - d.deleted} couldn't be deleted.", fontFamily = Inter, color = Lc.caution, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeDeviceDeletion() }) { Text("Done", fontFamily = Inter, color = Lc.selected) } },
    )
}

/** The gallery's photos under a heading ([label] null: not segmented). */
class DeviceGroup(val key: String, val label: String?, val photos: List<DevicePhoto>)

/** [photos] (in their order) under a heading for each year, month, day or hour taken ([unit]; `none`: one group), in [zone]. */
fun deviceGroups(photos: List<DevicePhoto>, unit: String, zone: ZoneId = ZoneId.systemDefault()): List<DeviceGroup> {
    if (photos.isEmpty()) return emptyList()
    if (unit !in listOf("year", "month", "day", "hour")) return listOf(DeviceGroup("", null, photos))
    return byDate(photos, zone, unit) { it.taken }.map { (key, run) -> DeviceGroup(key, if (key.isEmpty()) "No date" else segmentLabel(key), run) }
}

/** [items] in runs by the day (ISO, in [zone]) of their time [millis], in their order. */
fun <T> byDay(items: List<T>, zone: ZoneId, millis: (T) -> Long): List<Pair<String, List<T>>> = byDate(items, zone, "day", millis)

/** [items] in runs by the year, month, day or hour ([unit]; ISO keys, in [zone]) of their time [millis]; no time: an empty key. */
fun <T> byDate(items: List<T>, zone: ZoneId, unit: String, millis: (T) -> Long): List<Pair<String, List<T>>> {
    val out = mutableListOf<Pair<String, List<T>>>()
    var key: String? = null
    var run = mutableListOf<T>()
    for (item in items) {
        val ms = millis(item)
        val k = if (ms <= 0) "" else Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().toString().let { t ->
            when (unit) {
                "year" -> t.take(4)
                "month" -> t.take(7)
                "hour" -> t.take(13)
                else -> t.take(10)
            }
        }
        if (k != key) {
            key?.let { out += it to run }
            key = k
            run = mutableListOf()
        }
        run += item
    }
    key?.let { out += it to run }
    return out
}

/**
 * The Gallery tab: the phone's photos under their headings in the library's grid; a tap opens one
 * in the editor. Without access, why it's needed and a button to allow it; nothing to show, why.
 */
@Composable
fun DeviceGallery(vm: AppViewModel, list: LazyListState, denied: Boolean, onAllow: () -> Unit, onSettings: () -> Unit, onBrowse: () -> Unit) {
    if (vm.deviceAccess == DeviceAccess.NONE) {
        DeviceEmpty(
            "No access to photos",
            "LightCraft needs access to the photos on your phone to edit them.",
            if (denied) "Change permissions" to onSettings else "Allow" to onAllow,
            big = true,
        )
        return
    }
    val shown = vm.shownDevicePhotos
    when {
        shown.isNotEmpty() -> DeviceGrid(vm, shown, list)
        vm.devicePhotos.isNotEmpty() && vm.deviceFiltered -> DeviceEmpty("Nothing matches", "Try another filter.", "Clear filter" to { vm.clearDeviceFilter() })
        vm.deviceAccess == DeviceAccess.PARTIAL -> DeviceEmpty(
            "No photos found",
            "This folder is empty, or LightCraft can see only a few of your photos.",
            "Change permissions" to onSettings,
            second = "Select photos" to onAllow,
        )
        vm.deviceAlbum != null -> DeviceEmpty("No photos found", "There are no photos in this folder.", "Browse device" to onBrowse)
        else -> DeviceEmpty("No photos found", "There are no photos on this phone.", "Browse device" to onBrowse)
    }
}

/** The gallery's grid: justified rows under each heading, as the library's; pinched larger or smaller. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceGrid(vm: AppViewModel, photos: List<DevicePhoto>, list: LazyListState) {
    val groups = remember(photos, vm.deviceSegment) { deviceGroups(photos, vm.deviceSegment) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideways = maxWidth > maxHeight
        val inner = (maxWidth - 11.7.dp).value
        val k = AppViewModel.GRID_SIZES.getOrElse(vm.deviceGridSize) { 1f }
        val target = ((if (sideways) 110f else 125.5f) * k).coerceIn(84f, 520f)
        val rows = remember(groups, inner, target) { groups.map { g -> justify(g.photos.map { it.aspect }, inner, target = target, gap = 3.9f) } }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 3.9.dp, top = 3.9.dp, end = 7.8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.9.dp),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // two fingers pinch the photos larger or smaller (a step a pinch); one still scrolls
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var scale = 1f
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            scale *= ev.calculateZoom()
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale > 1.2f) vm.stepDeviceGridSize(1) else if (scale < 0.83f) vm.stepDeviceGridSize(-1)
                }
            },
        ) {
            groups.forEachIndexed { gi, g ->
                g.label?.let { label ->
                    stickyHeader(key = "g:$gi:${g.key}", contentType = "heading") {
                        // choosing, a tap on a heading chooses its photos (or, all chosen, none of them)
                        GroupHeading(label, g.photos.size, if (vm.selecting) ({ vm.toggleDeviceGroup(g.photos) }) else null)
                    }
                }
                items(rows[gi], key = { r -> "row:${g.photos[r.start].id}" }, contentType = { "row" }) { r ->
                    val full = r.height < target - 0.01f
                    Row(Modifier.fillMaxWidth().height(r.height.dp), horizontalArrangement = Arrangement.spacedBy(3.9.dp)) {
                        for (i in r.start until r.end) {
                            val p = g.photos[i]
                            val aspect = p.aspect.coerceIn(0.25, 4.0).toFloat()
                            val size = if (full) Modifier.weight(aspect) else Modifier.width((aspect * r.height).dp)
                            DeviceCell(vm, p, size.fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeading(label: String, count: Int, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(Lc.canvas).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(top = 2.dp, bottom = 1.5.dp).height(36.5.dp).padding(start = 12.2.dp, end = 8.3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Lc.text, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f).semantics { heading() })
        Text("$count", color = Lc.textDim, fontFamily = Inter, fontSize = 17.sp)
    }
}

/** A gallery photo: a tap opens it (choosing, toggles it); a long press starts choosing with it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCell(vm: AppViewModel, p: DevicePhoto, modifier: Modifier) {
    val thumb by produceState(vm.peekDeviceThumbnail(p), p.id) { vm.deviceThumbnail(p)?.let { value = it } }
    val chosen = vm.selecting && p.id in vm.deviceSelected
    Box(
        modifier.background(Lc.cell).combinedClickable(
            role = Role.Button,
            onLongClick = { if (!vm.selecting) vm.startDeviceSelecting(p) else vm.toggleDevice(p) },
        ) { if (vm.selecting) vm.toggleDevice(p) else vm.openDevicePhoto(p) }
            .semantics { selected = chosen },
    ) {
        thumb?.let { Image(it, contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // choosing: the library's ring at the top left, filled when chosen
        if (vm.selecting) {
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
                    .background(if (chosen) Lc.selected else Color.Black.copy(alpha = 0.25f))
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (chosen) MdIcon(MdIcon.Check, "Selected", tint = Color.White, size = 16.dp) }
        }
    }
}

/** Nothing to show on the Gallery tab, and why: a title, a line, and what to do (a button, and maybe a second). */
@Composable
private fun DeviceEmpty(title: String, body: String, action: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>? = null, big: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (big) {
                LcIcon(LcIcon.Phone, null, tint = Lc.text, size = 100.dp, weight = 0.3f)
                Spacer(Modifier.height(16.dp))
            }
            Text(title, color = Lc.text, fontFamily = Inter, fontSize = 17.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(body, color = Lc.text, fontFamily = Inter, fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Lc.primary).clickable(role = Role.Button, onClick = action.second).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(action.first, color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            second?.let { (label, onClick) ->
                Text(
                    label,
                    color = Lc.selected,
                    fontFamily = Inter,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
