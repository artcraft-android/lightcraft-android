package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.PhotoFlag
import ai.storyteller.lightcraft.core.PhotoInfo
import ai.storyteller.lightcraft.engine.DeviceAccess
import ai.storyteller.lightcraft.engine.DeviceMedia
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu

private val sides: WindowInsets
    @Composable get() = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)

/** Where LightCraft's documentation lives. */
const val HELP_URL = "https://github.com/storytold/lightcraft"

/**
 * The library, laid out as the reference app's: a top bar
 * (menu, search, more), the view's title with filter and sort, the grid, and the add button.
 */
@Composable
fun LibraryScreen(vm: AppViewModel, snackbar: SnackbarHostState, list: LazyListState, onPickPhotos: () -> Unit, onPickFiles: () -> Unit, onCamera: () -> Unit) {
    var sheet by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    // the Gallery tab's Import & add to album › New album, and its Delete's question
    var deviceNaming by remember { mutableStateOf(false) }
    var askingDelete by remember { mutableStateOf(false) }
    // kept by the view model: back from the editor, the search (and its words) is still there
    var searching by vm::searching
    // a page from the drawer (preferences, storage, about), over the drawer as the reference's
    var page by remember { mutableStateOf<String?>(null) }
    val device = vm.homeTab == "device"
    // sideways: the reference's landscape layout (a rail, the albums beside the photos, cards for sheets)
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp > config.screenHeightDp
    // where the filter, sort and more buttons are (window px), for their cards sideways
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    val context = LocalContext.current
    // refused for good (the system won't ask again): the Device tab's button opens the app's settings
    var refused by remember { mutableStateOf(false) }
    val askAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshDevice()
        val activity = context as? Activity
        refused = vm.deviceAccess == DeviceAccess.NONE && activity != null && DeviceMedia.permissions().none { activity.shouldShowRequestPermissionRationale(it) }
    }
    val openSettings = {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
        Unit
    }
    val askMore = { askAccess.launch(DeviceMedia.permissions()) }
    // the Gallery tab: its grid (the order's toggle goes back to its top), its folders' pickers, its Delete
    val deviceList = rememberLazyListState()
    val pickers = rememberGalleryPickers(vm)
    val onDeviceDelete = { if (vm.deviceSelected.size > AppViewModel.DELETE_BATCH || !vm.deviceDeleteUnasked) askingDelete = true else vm.startDeviceDelete() }
    // back in the app (from the settings, say): the gallery may have changed
    LifecycleResumeEffect(device) {
        if (device) vm.refreshDevice()
        onPauseOrDispose {}
    }
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val uri = LocalUriHandler.current
    BackHandler(enabled = vm.selecting) { vm.endSelecting() }
    // the sent search's words (the filter's; the Gallery tab has no search, as the reference's)
    val query = if (device) "" else vm.filter.text
    val setQuery = { t: String -> if (!device && t != vm.filter.text) vm.updateFilter(vm.filter.copy(text = t)) }
    val deviceOrder = {
        vm.toggleDeviceOrder()
        scope.launch { runCatching { deviceList.scrollToItem(0) } }
        Unit
    }
    val closeSearch = {
        searching = false
        setQuery("")
    }
    // Back leaves the search's results (typing has its own Back)
    BackHandler(enabled = query.isNotBlank() && !searching && !vm.selecting) { closeSearch() }
    // Back closes the drawer first (as the reference's), not the app behind it
    BackHandler(enabled = drawer.targetValue == DrawerValue.Open) { scope.launch { drawer.snapTo(DrawerValue.Closed) } }
    ModalNavigationDrawer(
        drawerState = drawer,
        // the left edge is the system's back gesture: open from the menu button only
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            DrawerContent { id ->
                if (id == "help") {
                    scope.launch { drawer.snapTo(DrawerValue.Closed) }
                    runCatching { uri.openUri(HELP_URL) }
                } else {
                    // the drawer stays under the page: Back from it returns there
                    page = id
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            // what the grid area shows, either way up
            val body: @Composable BoxScope.() -> Unit = {
                val error = vm.loadError
                when {
                    error != null -> Centered("LightCraft couldn't start its engine:\n$error")
                    vm.engine == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Lc.highlight, trackColor = Lc.divider)
                    device -> DeviceGallery(vm, deviceList, refused, onAllow = askMore, onSettings = openSettings, onBrowse = pickers.browse)
                    vm.photos.isEmpty() -> EmptyLibrary(vm, Modifier.align(Alignment.Center))
                    else -> PhotoGrid(vm, list)
                }
                if (vm.importing) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = Lc.selected, trackColor = Lc.bar)
            }
            if (wide) {
                WideHome(
                    vm,
                    device = device,
                    query = query,
                    sheet = sheet,
                    anchors = anchors,
                    snackbar = snackbar,
                    onMenu = { scope.launch { openDrawer(drawer) } },
                    onSearch = { searching = true },
                    onSheet = { sheet = it },
                    onTab = { t ->
                        if (searching || query.isNotBlank()) closeSearch()
                        vm.showHomeTab(t)
                    },
                    onNewAlbum = { vm.albumDialog = AlbumDialog("new") },
                    onEditSearch = { searching = true },
                    onClearSearch = closeSearch,
                    onPickPhotos = onPickPhotos,
                    onCamera = onCamera,
                    onDeviceDelete = onDeviceDelete,
                    deviceHeader = {
                        DeviceHeader(vm, wide = true, filterOpen = sheet == "deviceFilter", onFolders = {}, onFilter = { sheet = "deviceFilter" }, onMoreAccess = askMore, onOrder = deviceOrder)
                    },
                    folderPane = { m -> FolderPane(vm, pickers, askMore, m) },
                    body = body,
                )
            } else Scaffold(
                containerColor = Lc.canvas,
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    if (vm.selecting) {
                        SelectBar(vm)
                    } else {
                        Column {
                            LibraryTopBar(onMenu = { scope.launch { openDrawer(drawer) } }, onSearch = if (device) null else ({ searching = true }), onMore = { sheet = if (device) "deviceGrid" else "more" })
                            // a sent search, as the reference shows it: its words in a chip over the results
                            if (query.isNotBlank()) SearchChips(query, onEdit = { searching = true }, onClear = closeSearch)
                        }
                    }
                },
                bottomBar = {
                    Column {
                        if (vm.selecting) {
                            // adding to an album: just the choice and ADD
                            if (vm.addTarget == null) SelectActions(vm, onSheet = { sheet = it }, onDeviceDelete = onDeviceDelete)
                        } else {
                            HomeNav(vm.homeTab, onTab = { t ->
                                if (searching || query.isNotBlank()) closeSearch()
                                vm.showHomeTab(t)
                            }, Modifier.windowInsetsPadding(sides))
                        }
                        NavStrip()
                    }
                },
                // 16 dp above the bottom navigation, as the reference's
                floatingActionButton = { if (vm.engine != null && !vm.selecting && !device) AddButton(onPickPhotos, onCamera) },
                snackbarHost = { SnackbarHost(snackbar) { LcToast(it) } },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (device) {
                        DeviceHeader(
                            vm,
                            wide = false,
                            filterOpen = sheet == "deviceFilter",
                            onFolders = { sheet = "deviceFolders" },
                            onFilter = { sheet = "deviceFilter" },
                            onMoreAccess = askMore,
                            onOrder = deviceOrder,
                        )
                    } else {
                        LibraryHeader(vm, results = if (query.isNotBlank() && !vm.selecting) vm.photos.size else null, onPicker = { picker = true }, onFilter = { sheet = "filter" }, onSort = { sheet = "sort" })
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(sides), content = body)
                }
            }
            // sideways the albums are always beside the photos
            OverlayHost(picker.takeIf { it && !wide }) { AlbumPicker(vm, onDismiss = { picker = false }, onNewAlbum = { vm.albumDialog = AlbumDialog("new") }) }
            AlbumMenus(vm, onPicked = { picker = false }, onNewAlbum = { naming = true })
            // over the picker, what's done in it (an album deleted, with its Undo) shows there too
            if (picker && !wide) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 76.dp)) { LcToast(it) }
            // typing a search: a bar over the dimmed screen; the words apply when sent
            if (searching && !vm.selecting) {
                SearchPanel(query, wide, onSubmit = { t ->
                    searching = false
                    setQuery(t.trim())
                }, onCancel = { searching = false })
            }
        }
    }
    val density = LocalDensity.current
    val placement = if (wide) popupPlacement(sheet, anchors, density) else null
    CompositionLocalProvider(LocalPopupPlacement provides placement) {
        OverlayHost(sheet) { shown ->
            when (shown) {
                "filter" -> FilterSheet(vm) { sheet = null }
                "sort" -> SortSheet(vm) { sheet = null }
                "more" -> MoreSheet(vm, onDismiss = { sheet = null }, onPickPhotos = onPickPhotos, onPickFiles = onPickFiles)
                "rate" -> RateFlagSheet(vm) { sheet = null }
                "album" -> AddToAlbumSheet(vm, onDismiss = { sheet = null }, onNewAlbum = { naming = true })
                "deviceAlbum" -> AddToAlbumSheet(vm, onDismiss = { sheet = null }, onNewAlbum = { deviceNaming = true }) { vm.importDeviceSelected(it) }
                "deviceImport" -> DeviceImportSheet(onDismiss = { sheet = null }, onLibrary = { vm.importDeviceSelected() }, onAlbum = { sheet = "deviceAlbum" })
                "deviceFilter" -> DeviceFilterSheet(vm) { sheet = null }
                "deviceGrid" -> DeviceGridSheet(vm) { sheet = null }
                "deviceFolders" -> FolderTray(vm, pickers, onDismiss = { sheet = null }, onMoreAccess = askMore)
                "move" -> AddToAlbumSheet(vm, onDismiss = { sheet = null }, onNewAlbum = { naming = true }, move = true)
                "select" -> SelectMoreSheet(vm, onDismiss = { sheet = null }) { sheet = it }
                "albumRemove" -> AlbumRemoveSheet(vm, onDismiss = { sheet = null })
                "editInfo" -> BatchInfoPage(vm) { sheet = null }
            }
        }
    }
    if (naming) {
        NameDialog("New album", "Create", onDismiss = { naming = false }) { name ->
            naming = false
            vm.createAlbum(name)
        }
    }
    if (deviceNaming) {
        NameDialog("New album", "Import", onDismiss = { deviceNaming = false }) { name ->
            deviceNaming = false
            vm.importDeviceSelectedToNewAlbum(name)
        }
    }
    DuplicateDialog(vm)
    DeviceDeletionHost(vm, askingDelete) { askingDelete = false }
    when (page) {
        "preferences" -> PreferencesPage(vm) { page = null }
        "storage" -> StoragePage(vm) { page = null }
        "about" -> AboutPage(vm) { page = null }
    }
}

/** The drawer opens as the reference's: 295 ms, fast out, slow in (its own spec isn't settable). */
@Suppress("DEPRECATION")
private suspend fun openDrawer(drawer: DrawerState) = drawer.animateTo(DrawerValue.Open, tween(LcMotion.DRAWER_OPEN_MS, easing = FastOutSlowInEasing))

/** The status bar's strip, then the 56 dp bar: menu at left, search and more at right. */
@Composable
private fun LibraryTopBar(onMenu: () -> Unit, onSearch: (() -> Unit)?, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lc.bar)) {
        StatusStrip()
        Row(Modifier.fillMaxWidth().windowInsetsPadding(sides).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(MdIcon.Menu, "Menu", size = 48.dp, tint = Lc.text, onClick = onMenu)
            Spacer(Modifier.weight(1f))
            onSearch?.let { BarButton(MdIcon.Search, "Search", size = 48.dp, tint = Lc.text, onClick = it) }
            BarButton(MdIcon.MoreVert, if (onSearch == null) "Grid settings" else "More options", size = 48.dp, tint = Lc.text, onClick = onMore)
        }
    }
}

/** Behind the search bar while typing (sampled from the reference's: 87 % black). */
private val searchDim = Color.Black.copy(alpha = 0.87f)

/** The search's words, its hint and its chip (sampled). */
private val searchText = Color(0xFFEBEBEB)
private val chipBorder = Color(0xFF6A6A6A)

/**
 * Typing a search, as the reference's: a 55.5 dp bar from just under the status bar's middle, over
 * the dimmed screen (the status bar too): back, the field (an 18 sp hint, a 2 dp blue line under
 * it), and clear once there are words. The words apply when sent with the keyboard's search key;
 * Back or a tap on the dim leave them as they were. Matched against the photos' names, titles and
 * keywords (the Device tab's: their names).
 */
@Composable
private fun SearchPanel(initial: String, wide: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(searchDim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
    ) {
        Column(Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            // sideways the bar is 64.2 dp: the search bar sits on its middle line
            if (wide) Spacer(Modifier.height(4.35.dp))
            Row(
                Modifier.fillMaxWidth().background(Lc.bar).height(55.5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.offset(x = (-1.1).dp)) { BarButton(MdIcon.ArrowBack, "Close search", size = 48.dp, tint = Lc.text, onClick = onCancel) }
                Spacer(Modifier.width(15.7.dp))
                Box(Modifier.weight(1f).padding(end = 16.5.dp).height(42.9.dp)) {
                    Box(Modifier.fillMaxSize().padding(start = 4.9.dp, end = 48.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.text.isEmpty()) Text("Search", color = searchText, fontFamily = Inter, fontSize = 18.sp)
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = TextStyle(color = searchText, fontFamily = Inter, fontSize = 18.sp),
                            cursorBrush = SolidColor(Lc.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (text.text.isBlank()) onCancel() else onSubmit(text.text) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search" },
                        )
                    }
                    if (text.text.isNotEmpty()) {
                        Box(Modifier.align(Alignment.CenterEnd)) {
                            BarButton(MdIcon.Close, "Clear", size = 48.dp, iconSize = 22.5.dp, tint = searchText) { text = TextFieldValue("") }
                        }
                    }
                    // the blue line under the field
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Lc.primary))
                }
            }
        }
    }
}

/**
 * A sent search under the top bar, as the reference's: its words in a 34.6 dp outlined chip (a
 * search icon, the words, × to take them away), × at the right to leave the search. Tapping the
 * chip edits the words.
 */
@Composable
private fun SearchChips(query: String, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Lc.canvas).windowInsetsPadding(sides).height(51.dp)) {
        Row(
            Modifier
                .padding(start = 16.1.dp, top = 12.1.dp)
                .height(34.6.dp)
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(2.5.dp))
                .border(1.5.dp, chipBorder, RoundedCornerShape(2.5.dp))
                .clickable(role = Role.Button, onClickLabel = "Edit the search", onClick = onEdit)
                .padding(start = 8.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdIcon(MdIcon.Search, null, tint = searchText, size = 16.5.dp)
            Spacer(Modifier.width(4.6.dp))
            Text(query, color = searchText, fontFamily = Inter, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.size(width = 30.dp, height = 34.6.dp).clickable(role = Role.Button, onClickLabel = "Remove the search", onClick = onClear), contentAlignment = Alignment.Center) {
                MdIcon(MdIcon.Close, "Remove the search", tint = searchText, size = 16.6.dp)
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(top = 16.dp, end = 2.3.dp)) {
            BarButton(MdIcon.Close, "Leave the search", size = 48.dp, iconSize = 19.dp, tint = Lc.text, onClick = onClear)
        }
    }
}

/** Select mode's bar: done at left, how many are selected, select all at right. */
@Composable
private fun SelectBar(vm: AppViewModel) {
    Column(Modifier.fillMaxWidth().background(Lc.bar)) {
        StatusStrip()
        SelectBarRow(vm, Modifier.windowInsetsPadding(sides).height(56.dp))
    }
}

@Composable
private fun SelectBarRow(vm: AppViewModel, modifier: Modifier = Modifier) {
    run {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BarButton(MdIcon.Close, "Done selecting", size = 48.dp, tint = Lc.text) { vm.endSelecting() }
            // on the Gallery tab, the gallery's own choice
            val device = vm.homeTab == "device"
            Text(
                when (val n = if (device) vm.deviceSelected.size else vm.selected.size) {
                    0 -> vm.addTarget?.let { "Add to ${it.name}" } ?: if (device) "Select items" else "Select photos"
                    1 -> "1 selected"
                    else -> "$n selected"
                },
                color = Lc.text,
                fontFamily = Inter,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            val target = vm.addTarget
            if (target != null) {
                // adding to an album, as the reference's: ADD with a check
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = vm.selected.isNotEmpty(), role = Role.Button) { vm.addChosenToTarget() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MdIcon(MdIcon.Check, null, tint = if (vm.selected.isNotEmpty()) Lc.selected else Lc.textDisabled, size = 20.dp)
                    Text("ADD", color = if (vm.selected.isNotEmpty()) Lc.selected else Lc.textDisabled, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
                }
            } else if (device) {
                // the Gallery's, as the reference's: an overflow with Select All and Select None
                var open by remember { mutableStateOf(false) }
                Box {
                    BarButton(MdIcon.MoreVert, "More options", size = 48.dp, tint = Lc.text) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Lc.bottomBar) {
                        MenuItem("Select All") {
                            open = false
                            vm.selectAllDevice()
                        }
                        MenuItem("Select None") {
                            open = false
                            vm.selectNoDevice()
                        }
                    }
                }
            } else {
                val all = vm.selected.size == vm.photos.size && vm.photos.isNotEmpty()
                Text(
                    if (all) "Select none" else "Select all",
                    color = Lc.selected,
                    fontFamily = Inter,
                    fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { vm.selectAll() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/**
 * Select mode's actions along the bottom, as the reference's: share, add to an album, delete (in an
 * album: take out of it) and more (select all or none, move to, edit info, set as cover, paste
 * settings). In Deleted: restore or delete for good.
 */
@Composable
private fun SelectActions(vm: AppViewModel, onSheet: (String) -> Unit, onDeviceDelete: () -> Unit, modifier: Modifier = Modifier.windowInsetsPadding(sides)) {
    val any = vm.selected.isNotEmpty()
    Row(Modifier.fillMaxWidth().background(Lc.bar).then(modifier).height(61.3.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        // the Gallery tab, as the reference's: share the photos as they are, import them (asking
        // where to once the library has albums), delete them from the phone
        if (vm.homeTab == "device") {
            val chosen = vm.deviceSelected.isNotEmpty()
            ActionButton(LcIcon.Share, "Share", chosen) { vm.shareDeviceSelected() }
            ActionButton(LcIcon.Import, "Import", chosen && !vm.importing) { if (vm.importAsksAlbum) onSheet("deviceImport") else vm.importDeviceSelected() }
            ActionButton(LcIcon.Trash, "Delete", chosen, onDeviceDelete)
            return@Row
        }
        if (vm.view.kind == "deleted") {
            ActionButton(LcIcon.Undo, "Restore", any) { vm.restoreSelected() }
            ActionButton(LcIcon.Trash, "Delete", any) { vm.deleteSelectedPermanently() }
        } else {
            ActionButton(LcIcon.Share, if (vm.sharingSelected) "Sharing…" else "Share", any && !vm.sharingSelected) { vm.shareSelected() }
            ActionButton(LcIcon.Photos, "Add to", any) { onSheet("album") }
            // in an album, as the reference's: asked whether out of the album or to Deleted; elsewhere, to Deleted
            ActionButton(LcIcon.Trash, "Delete", any) { if (vm.inPlainAlbum()) onSheet("albumRemove") else vm.deleteSelected() }
            ActionButton(LcIcon.More, "More", true) { onSheet("select") }
        }
    }
}

@Composable
private fun ActionButton(icon: LcIcon, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val color = if (enabled) Lc.text else Lc.textDisabled
        LcIcon(icon, null, tint = color, size = 24.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, fontFamily = Inter, fontSize = 11.sp)
    }
}

/** The view's title (its menu: the views and albums) at left, filter and sort at right, on black. */
@Composable
private fun LibraryHeader(
    vm: AppViewModel,
    results: Int?,
    onPicker: () -> Unit,
    onFilter: () -> Unit,
    onSort: () -> Unit,
    wide: Boolean = false,
    sheet: String? = null,
    anchors: MutableMap<String, Rect>? = null,
) {
    // under a search: the number of results under the title, the buttons lower (the reference's)
    val tall = results != null
    Row(Modifier.fillMaxWidth().then(if (wide) Modifier else Modifier.windowInsetsPadding(sides)).height(if (tall) 66.dp else 52.dp), verticalAlignment = if (tall) Alignment.Top else Alignment.Bottom) {
        // the title takes what it needs (a long album name ends in …); the buttons stay at the right
        Box(Modifier.weight(1f).padding(top = if (tall) 4.3.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = !wide, role = Role.DropdownList, onClick = onPicker).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.view.name,
                    color = Lc.text,
                    fontFamily = Inter,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                )
                if (!wide) {
                    Spacer(Modifier.width(5.dp))
                    MdIcon(MdIcon.ExpandMore, null, tint = Lc.text)
                }
            }
            if (results != null) {
                Text(
                    if (results == 1) "1 result" else "$results results",
                    color = Lc.text,
                    fontFamily = Inter,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 16.5.dp, top = 40.4.dp),
                )
            }
        }
        Row(Modifier.padding(top = if (tall) 16.2.dp else 0.dp, end = if (wide) 0.8.dp else 0.dp)) {
            Box(Modifier.anchor("filter", anchors)) {
                BarButton(MdIcon.FilterAlt, if (vm.filter.narrowed) "Filter (on)" else "Filter", size = 48.dp, tint = if (vm.filter.narrowed) Lc.selected else Lc.text, onClick = onFilter)
            }
            Box(Modifier.anchor("sort", anchors)) {
                BarButton(LcIcon.Sort, "Sort", size = 48.dp, tint = Lc.text, active = wide && sheet == "sort", onClick = onSort)
            }
        }
    }
}

/** The photos: a justified grid (each at its own shape) or a square one, by segment when asked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGrid(vm: AppViewModel, list: LazyListState) {
    val segs = remember(vm.photos, vm.sortKey, vm.segmentBy) { segments(vm.photos, vm.sortKey, vm.segmentBy) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideways = maxWidth > maxHeight
        // as the reference: 3.9 dp gaps and left margin, 7.8 dp at the right
        val inner = (maxWidth - 11.7.dp).value
        val gap = 3.9f
        val justified = vm.justified
        // pinched larger or smaller a step at a time; the reference's cells stay 84–520 dp
        val k = AppViewModel.GRID_SIZES.getOrElse(vm.gridSize) { 1f }
        val target = ((if (sideways) 110f else 125.5f) * k).coerceIn(84f, 520f)
        val columns = max(if (k < 1f) 4 else 1, ((maxWidth.value - 11.7f) / ((if (sideways) 83.7f else 125.5f) * k + gap)).roundToInt())
        val square = (inner - gap * (columns - 1)) / columns
        val rows = remember(segs, inner, justified, columns) {
            segs.map { s ->
                if (justified) justify(s.photos.map { it.aspect }, inner, target = target, gap = gap) else squareRows(s.photos.size, columns, square / CELL_ASPECT)
            }
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 3.9.dp, top = 3.9.dp, end = 7.8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(3.9.dp),
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // two fingers pinch the photos larger or smaller (one step a pinch); one still scrolls
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
                    if (scale > 1.2f) vm.stepGridSize(1) else if (scale < 0.83f) vm.stepGridSize(-1)
                }
            },
        ) {
            segs.forEachIndexed { si, seg ->
                seg.label?.let { label -> stickyHeader(key = "seg:$si:${seg.key}", contentType = "heading") { SegmentHeading(label, seg.photos.size) } }
                items(rows[si], key = { r -> "row:${seg.photos[r.start].id}" }, contentType = { "row" }) { r ->
                    // a full justified row fills the width exactly; the last keeps its photos' widths
                    val full = justified && r.height < target - 0.01f
                    Row(Modifier.fillMaxWidth().height(r.height.dp), horizontalArrangement = Arrangement.spacedBy(3.9.dp)) {
                        for (i in r.start until r.end) {
                            val p = seg.photos[i]
                            val aspect = p.aspect.coerceIn(0.25, 4.0).toFloat()
                            val size = when {
                                full -> Modifier.weight(aspect)
                                justified -> Modifier.width((aspect * r.height).dp)
                                else -> Modifier.width(square.dp)
                            }
                            PhotoCell(p, vm, size.fillMaxHeight())
                        }
                    }
                }
            }
        }
    }
}

/** A segment's heading: 36.5 dp, its date at left, how many photos at right. */
@Composable
private fun SegmentHeading(label: String, count: Int) {
    // the reference's: 17 sp, held at the top while its photos scroll under it
    Row(Modifier.fillMaxWidth().background(Lc.canvas).padding(top = 2.dp, bottom = 1.5.dp).height(36.5.dp).padding(start = 12.2.dp, end = 8.3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Lc.text, fontFamily = Inter, fontSize = 17.sp, modifier = Modifier.weight(1f).semantics { heading() })
        Text("$count", color = Lc.textDim, fontFamily = Inter, fontSize = 17.sp)
    }
}

private val scrim = Color.Black.copy(alpha = 0.5f)

/** The square grid's cells are a little wider than tall (125.5 × 121.6 dp), as the reference's. */
const val CELL_ASPECT = 125.5f / 121.6f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(photo: PhotoInfo, vm: AppViewModel, modifier: Modifier) {
    val key = vm.thumbnailKey(photo)
    val thumb by produceState(vm.thumbnails.peek(key), key) { vm.thumbnail(photo)?.let { value = it } }
    val selected = vm.selecting && photo.id in vm.selected
    Box(
        modifier
            .background(Lc.cell)
            .combinedClickable(
                onClick = { if (vm.selecting) vm.toggleSelected(photo.id) else vm.openEditor(photo) },
                onLongClick = { if (vm.selecting) vm.toggleSelected(photo.id) else vm.startSelecting(photo.id) },
            ),
    ) {
        thumb?.let { Image(it, contentDescription = photo.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (photo.raw && vm.showBadges) {
            Text(
                photo.format,
                color = Lc.text,
                fontFamily = Inter,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(scrim, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        // a cloud photo that is only a thumbnail so far: opening it downloads its preview
        if (!vm.selecting && photo.id in vm.cloudIds && photo.id !in vm.cloudPreviews) {
            Box(Modifier.align(Alignment.TopStart).padding(4.dp).size(18.dp).background(scrim, CircleShape), contentAlignment = Alignment.Center) {
                MdIcon(MdIcon.CloudDownload, "Not downloaded", tint = Lc.text, size = 12.dp)
            }
        }
        if (vm.selecting) {
            if (selected) Box(Modifier.fillMaxSize().border(4.dp, Lc.primary))
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(if (selected) Lc.primary else Color.Black.copy(alpha = 0.35f), CircleShape)
                    .border(1.5.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) LcIcon(LcIcon.Check, "Selected", tint = Color.White, size = 16.dp)
            }
        }
        if (!vm.showBadges) return@Box
        if (photo.flag == PhotoFlag.REJECT) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (photo.flag == PhotoFlag.PICK) Badge(LcIcon.FlagPick, "Pick")
            if (photo.flag == PhotoFlag.REJECT) Badge(LcIcon.FlagReject, "Reject")
            if (photo.edited) Badge(LcIcon.Edited, "Edited")
        }
        if (photo.rating > 0u) {
            Row(Modifier.align(Alignment.BottomStart).padding(4.dp).background(scrim, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 2.dp)) {
                repeat(photo.rating.toInt()) { LcIcon(LcIcon.StarFilled, null, tint = Lc.star, size = 9.dp) }
            }
        }
    }
}

@Composable
private fun Badge(icon: LcIcon, description: String) {
    Box(Modifier.size(18.dp).background(scrim, CircleShape), contentAlignment = Alignment.Center) {
        LcIcon(icon, description, tint = Lc.pick, size = 12.dp)
    }
}

/** The pill at bottom right: add photos | take one with the camera, split by a hairline. */
@Composable
private fun AddButton(onPickPhotos: () -> Unit, onCamera: () -> Unit, modifier: Modifier = Modifier.windowInsetsPadding(sides)) {
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Lc.primary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(LcIcon.AddPhoto, "Add photos", 25.dp, onPickPhotos)
        Box(Modifier.width(1.dp).height(24.dp).background(Color(0xFF3892F3)))
        PillButton(MdIcon.PhotoCamera, "Take a photo", 28.dp, onCamera)
    }
}

@Composable
private fun PillButton(icon: Glyph, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 55.9.dp, height = 48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(icon, null, tint = Color.White, size = size)
    }
}

/** Nothing to show: why, and what to do about it. */
@Composable
private fun EmptyLibrary(vm: AppViewModel, modifier: Modifier = Modifier) {
    val (title, hint) = when {
        vm.filter.active -> "No photos match the filter." to "Change or clear it with the filter button above."
        vm.view.kind == "deleted" -> "Nothing in Deleted." to "Photos you remove wait here until you delete them for good."
        vm.view.kind == "album" -> "This album is empty." to "Select photos (⋮ › Select), then add them to the album."
        vm.view.kind == "edited" -> "No edited photos yet." to "Photos you edit appear here."
        vm.view.kind == "unedited" -> "Every photo is edited." to "Photos without edits appear here."
        vm.view.kind == "recentlyAdded" -> "Nothing added lately." to "Photos added in the last 30 days appear here."
        else -> "No photos in LightCraft yet." to "Add photos from your phone to start editing."
    }
    // the reference's sizes: a picture about 100 dp wide, the title 80 dp under its middle, the hint 37 dp under that
    Column(modifier.padding(start = 32.dp, end = 32.dp, bottom = 9.4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LcIcon(LcIcon.Photos, null, tint = Lc.text, size = 130.dp, weight = 0.25f)
        Spacer(Modifier.height(2.6.dp))
        Text(title, color = Lc.text, fontFamily = Inter, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(15.dp))
        Text(hint, color = Lc.text, fontFamily = Inter, fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Lc.textLabel, fontFamily = Inter, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------------------------- sideways

/** The reference's landscape library, measured from its capture. */
private object Wide {
    /** The navigation rail, after the system bar's inset. */
    val rail = 80.3.dp

    /** Under the status bar, the bar's buttons on its middle line. */
    val bar = 64.2.dp

    /** The albums' pane beside the photos'. */
    val albums = 255.8.dp

    /** Between the panes, and after the photos' pane. */
    val gap = 3.9.dp

    /** The panes' outer top corners. */
    val radius = 9.7.dp
}

/** Remembers where a sheet's button is (window px), for its card sideways. */
private fun Modifier.anchor(key: String, anchors: MutableMap<String, Rect>?): Modifier =
    if (anchors == null) this else onGloballyPositioned { anchors[key] = it.boundsInWindow() }

/**
 * Where the reference puts its cards sideways (measured): filter's over its button, its right edge
 * on the button's; sort's under its button, its right edge on the button's left; more's left of
 * its button. Null for the sheets that stay at the bottom.
 */
private fun popupPlacement(sheet: String?, anchors: Map<String, Rect>, density: Density): PopupPlacement? = with(density) {
    val a = anchors[sheet ?: return null] ?: return null
    when (sheet) {
        "filter" -> PopupPlacement(right = a.right.toDp(), top = a.top.toDp() - 26.6.dp, width = 280.2.dp)
        "sort" -> PopupPlacement(right = a.left.toDp() + 4.1.dp, top = a.bottom.toDp() + 4.2.dp, width = 240.3.dp)
        "more" -> PopupPlacement(right = a.left.toDp() + 1.dp, top = a.top.toDp() + 32.7.dp, width = 280.2.dp)
        else -> null
    }
}

/**
 * The library sideways, as the reference's: the menu and the tabs in a rail at the left, search and
 * more on the bar, then the views and albums in a pane beside the photos' (both with rounded outer
 * corners); the add pill in the photos' pane. The Device tab has the photos' pane alone.
 */
@Composable
private fun WideHome(
    vm: AppViewModel,
    device: Boolean,
    query: String,
    sheet: String?,
    anchors: MutableMap<String, Rect>,
    snackbar: SnackbarHostState,
    onMenu: () -> Unit,
    onSearch: () -> Unit,
    onSheet: (String) -> Unit,
    onTab: (String) -> Unit,
    onNewAlbum: () -> Unit,
    onEditSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onPickPhotos: () -> Unit,
    onCamera: () -> Unit,
    onDeviceDelete: () -> Unit,
    deviceHeader: @Composable () -> Unit,
    folderPane: @Composable (Modifier) -> Unit,
    body: @Composable BoxScope.() -> Unit,
) {
    // the system bars only: like the reference's, the panes run past the camera's cutout
    val edges = WindowInsets.navigationBars
    Box(Modifier.fillMaxSize().background(Lc.bar)) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(edges.only(WindowInsetsSides.Start))) {
            // the rail: the menu on the bar's line, the tabs in the middle of the height
            Box(Modifier.width(Wide.rail).fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars)) {
                Box(Modifier.fillMaxWidth().height(Wide.bar), contentAlignment = Alignment.Center) {
                    if (!vm.selecting) BarButton(MdIcon.Menu, "Menu", size = 48.dp, tint = Lc.text, onClick = onMenu)
                }
                HomeRail(vm.homeTab, onTab, Modifier.align(Alignment.Center))
            }
            Column(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(edges.only(WindowInsetsSides.End))) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Box(Modifier.fillMaxWidth().height(Wide.bar)) {
                    if (vm.selecting) {
                        SelectBarRow(vm, Modifier.align(Alignment.Center))
                    } else {
                        Row(Modifier.align(Alignment.CenterEnd).padding(end = 1.dp)) {
                            // the Gallery tab has no search; its ⋮ is Grid Settings
                            if (!device) BarButton(MdIcon.Search, "Search", size = 48.dp, tint = Lc.text, onClick = onSearch)
                            Box(Modifier.anchor("more", anchors)) {
                                BarButton(MdIcon.MoreVert, if (device) "Grid settings" else "More options", size = 48.dp, tint = Lc.text, active = sheet == "more" || sheet == "deviceGrid") {
                                    onSheet(if (device) "deviceGrid" else "more")
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxSize().padding(end = Wide.gap)) {
                    // the library's albums or (as the reference's) the phone's folders beside the photos
                    val pane = Modifier.width(Wide.albums).fillMaxHeight().clip(RoundedCornerShape(topStart = Wide.radius)).background(Lc.canvas)
                    if (device) {
                        if (vm.deviceAccess != DeviceAccess.NONE) {
                            folderPane(pane)
                            Spacer(Modifier.width(Wide.gap))
                        }
                    } else {
                        AlbumPane(vm, onNewAlbum, pane)
                        Spacer(Modifier.width(Wide.gap))
                    }
                    val alone = device && vm.deviceAccess == DeviceAccess.NONE
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(topStart = if (alone) Wide.radius else 0.dp, topEnd = Wide.radius)).background(Lc.canvas)) {
                        Column(Modifier.fillMaxSize()) {
                            if (query.isNotBlank() && !vm.selecting) SearchChips(query, onEdit = onEditSearch, onClear = onClearSearch)
                            if (device) {
                                deviceHeader()
                            } else {
                                LibraryHeader(
                                    vm,
                                    results = if (query.isNotBlank() && !vm.selecting) vm.photos.size else null,
                                    onPicker = {},
                                    onFilter = { onSheet("filter") },
                                    onSort = { onSheet("sort") },
                                    wide = true,
                                    sheet = sheet,
                                    anchors = anchors,
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxWidth(), content = body)
                            if (vm.selecting && vm.addTarget == null) SelectActions(vm, onSheet = onSheet, onDeviceDelete = onDeviceDelete, modifier = Modifier)
                        }
                        // as the reference's sideways: 57.8 dp from the pane's right, 46.7 dp from the bottom
                        if (vm.engine != null && !vm.selecting && !device) {
                            AddButton(onPickPhotos, onCamera, Modifier.align(Alignment.BottomEnd).padding(end = 57.8.dp, bottom = 46.7.dp))
                        }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter)) { LcToast(it) }
                    }
                }
            }
        }
    }
}
