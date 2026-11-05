package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.R
import ai.storyteller.lightcraft.core.AlbumInfo
import ai.storyteller.lightcraft.core.Facet
import ai.storyteller.lightcraft.core.PhotoFlag
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt


/** What an album's menu led to: a name to give, a folder to move it to, or a folder deletion to confirm. */
data class AlbumDialog(val kind: String, val album: AlbumInfo? = null, val parent: ULong? = null, val folder: Boolean = false)

/**
 * An album's or folder's menu (a long press in the albums), as the reference's: a sheet headed by its
 * name, with Create Album and Create Folder (a folder), Add photos (an album), Move to, Rename, Delete
 * and Slideshow; then what they lead to. [onPicked] closes the albums when a choice leaves them.
 */
@Composable
fun AlbumMenus(vm: AppViewModel, onPicked: () -> Unit, onNewAlbum: () -> Unit) {
    OverlayHost(vm.albumMenu) { a -> AlbumMenuSheet(vm, a, onPicked) { vm.albumMenu = null } }
    // the albums' order, as the reference's sheet: by when made, name or size; the one chosen blue with its
    // direction, which a second tap turns
    OverlayHost(vm.albumDialog?.takeIf { it.kind == "sort" }) {
        LcSheet({ vm.albumDialog = null }, background = albumSheetColor) {
            AlbumSheetTitle("Sort albums by")
            listOf(Triple("time", LcIcon.Calendar, "Date added"), Triple("title", LcIcon.Az, "Name"), Triple("count", LcIcon.PhotoStack, "Photo count")).forEach { (by, icon, label) ->
                val on = vm.albumSort == by
                AlbumSheetRow(icon, label, selected = on, trailing = {
                    if (on) LcIcon(LcIcon.ChevronDown, if (vm.albumSortUp) "Ascending" else "Descending", tint = Lc.choice, size = 22.dp, modifier = Modifier.rotate(if (vm.albumSortUp) 180f else 0f))
                }) { vm.sortAlbums(by) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    // the albums' Add new: an album (with the photos chosen, if any) or a folder, at the top level
    OverlayHost(vm.albumDialog?.takeIf { it.kind == "new" }) {
        LcSheet({ vm.albumDialog = null }, background = albumSheetColor) {
            AlbumSheetTitle("Add new")
            AlbumSheetRow(LcIcon.PhotoStack, "Album") {
                vm.albumDialog = null
                onNewAlbum()
            }
            AlbumSheetRow(LcIcon.Folder, "Folder") { vm.albumDialog = AlbumDialog("create", folder = true) }
            Spacer(Modifier.height(8.dp))
        }
    }
    OverlayHost(vm.albumDialog?.takeIf { it.kind == "move" }?.album) { a -> MoveAlbumSheet(vm, a) { vm.albumDialog = null } }
    val d = vm.albumDialog ?: return
    val done = { vm.albumDialog = null }
    when (d.kind) {
        "create" -> NameDialog(if (d.folder) "Create Folder" else "Create Album", "Create", onDismiss = done) { name ->
            done()
            vm.createAlbumIn(name, d.parent, d.folder)
        }
        "rename" -> d.album?.let { a ->
            NameDialog(if (a.folder) "Rename Folder" else "Rename album", "Rename", initial = a.name, onDismiss = done) { name ->
                done()
                vm.renameAlbum(a, name)
            }
        }
        "delete" -> d.album?.let { f ->
            val inside = vm.overview?.albums.orEmpty().filter { it.id in vm.albumTree(f.id) - f.id }
            val folders = inside.count { it.folder }
            val albums = inside.size - folders
            fun n(k: Int, word: String) = if (k == 1) "1 $word" else "$k ${word}s"
            AlertDialog(
                onDismissRequest = done,
                containerColor = Lc.dialog,
                title = { Text("Delete Folder", fontFamily = Inter, color = Lc.text) },
                text = {
                    Text(
                        when {
                            inside.isEmpty() -> "Delete the folder “${f.name}”?"
                            folders == 0 -> "Delete “${f.name}” and the ${n(albums, "album")} in it? Their photos stay in your library."
                            else -> "Delete “${f.name}” and the ${n(folders, "folder")} and ${n(albums, "album")} in it? Their photos stay in your library."
                        },
                        fontFamily = Inter,
                        color = Lc.textDim,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        done()
                        vm.deleteAlbum(f)
                    }) { Text("Delete", fontFamily = Inter, color = Lc.selected) }
                },
                dismissButton = { TextButton(onClick = done) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
            )
        }
    }
}

/** The album menu's sheet colour (the reference's). */
private val albumSheetColor = Color(0xFF232323)

/** The album menu's title (the reference's: 56 dp, 16 sp, a faint line under it). */
@Composable
private fun AlbumSheetTitle(text: String) {
    Text(
        text,
        color = Color(0xFFE8E8E8),
        fontFamily = Inter,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().height(56.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x26FFFFFF)))
}

/** An album menu row (the reference's: 48 dp, a 22 dp icon at 16 dp, the label 32 dp after it at 16 sp). */
@Composable
private fun AlbumSheetRow(icon: Glyph, label: String, selected: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) Lc.choice else Color(0xFFC8C8C8)
        GlyphIcon(icon, null, tint = tint, size = 22.dp)
        Text(label, color = tint, fontFamily = Inter, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 32.dp).weight(1f))
        when {
            trailing != null -> trailing()
            selected -> MdIcon(MdIcon.Check, "Selected", tint = Lc.check)
        }
    }
}

@Composable
private fun AlbumMenuSheet(vm: AppViewModel, a: AlbumInfo, onPicked: () -> Unit, onDismiss: () -> Unit) {
    fun then(next: () -> Unit) {
        onDismiss()
        next()
    }
    LcSheet(onDismiss, background = albumSheetColor) {
        AlbumSheetTitle(a.name)
        if (a.folder) {
            AlbumSheetRow(LcIcon.PhotoStack, "Create Album") { then { vm.albumDialog = AlbumDialog("create", parent = a.id) } }
            AlbumSheetRow(LcIcon.Folder, "Create Folder") { then { vm.albumDialog = AlbumDialog("create", parent = a.id, folder = true) } }
        } else if (!a.smart) {
            AlbumSheetRow(LcIcon.AddPhoto, "Add photos") {
                then {
                    vm.startAddingTo(a)
                    onPicked()
                }
            }
        }
        AlbumSheetRow(LcIcon.Import, "Move to") { then { vm.albumDialog = AlbumDialog("move", a) } }
        AlbumSheetRow(LcIcon.Pencil, "Rename") { then { vm.albumDialog = AlbumDialog("rename", a) } }
        // a folder asks first, as the reference's: what's in it goes too
        AlbumSheetRow(LcIcon.Trash, "Delete") { then { if (a.folder) vm.albumDialog = AlbumDialog("delete", a) else vm.deleteAlbum(a) } }
        if (!a.folder && a.count > 0u) {
            AlbumSheetRow(LcIcon.Play, "Slideshow") {
                then {
                    vm.albumSlideshow(a)
                    onPicked()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Move to: the top level or a folder (not the album itself or one inside it), where it is now checked. */
@Composable
private fun MoveAlbumSheet(vm: AppViewModel, a: AlbumInfo, onDismiss: () -> Unit) {
    val all = vm.overview?.albums.orEmpty()
    val mine = remember(all, a.id) { vm.albumTree(a.id) }
    val folders = all.filter { it.folder && it.id !in mine }.sortedBy { it.name.lowercase() }
    LcSheet(onDismiss, background = albumSheetColor) {
        AlbumSheetTitle("Move “${a.name}” to")
        (listOf<AlbumInfo?>(null) + folders).forEach { f ->
            AlbumSheetRow(if (f == null) LcIcon.Library else LcIcon.Folder, f?.name ?: "Your albums", selected = f?.id == a.parent) {
                onDismiss()
                if (f?.id != a.parent) vm.moveAlbum(a, f?.id)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The square behind an album's or a view's picture. */
private val tileColor = Color(0xFF303030)

/**
 * The reference app's sheets: at the bottom, as wide as the screen up to 500 dp, square, on the
 * dim; in and out with its motion ([LcMotion]), and pulled down to close.
 */
@Composable
fun LcSheet(onDismiss: () -> Unit, inset: Dp = 0.dp, background: Color = Lc.bar, content: @Composable ColumnScope.() -> Unit) {
    // sideways the screen may want it as a card by its button (the reference's)
    LocalPopupPlacement.current?.let { at ->
        LcPopupCard(onDismiss, at, content)
        return
    }
    val drag = rememberSheetDrag(onDismiss)
    LcOverlay(onDismiss, Alignment.BottomCenter) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = inset)
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .onSizeChanged { drag.height = it.height }
                .offset { IntOffset(0, drag.offset.roundToInt()) }
                .background(background)
                // taps between its rows stay on the sheet
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .nestedScroll(drag.connection)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** A group's heading in capitals (`FILTER BY`), 50 dp tall. */
@Composable
fun SheetHeading(text: String) {
    Text(
        text.uppercase(),
        color = Lc.text,
        fontFamily = Inter,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().height(50.6.dp).padding(start = 16.1.dp, top = 17.dp),
    )
}

@Composable
fun SheetDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Lc.divider))

/**
 * A 48 dp row: an icon, a label, and at its end a check (the current choice, in blue), a chevron
 * (a page of choices) or [trailing].
 */
@Composable
fun SheetRow(
    icon: Glyph?,
    label: String,
    selected: Boolean = false,
    chevron: Boolean = false,
    enabled: Boolean = true,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Lc.textDisabled
        selected -> Lc.choice
        else -> Lc.text
    }
    Row(
        Modifier.fillMaxWidth().height(48.2.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) GlyphIcon(icon, null, tint = if (enabled) (if (selected) Lc.choice else Lc.text) else Lc.textDisabled, size = 24.dp)
        Spacer(Modifier.width(if (icon != null) 22.3.dp else 46.3.dp))
        Text(label, color = color, fontFamily = Inter, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        detail?.let { Text(it, color = Lc.textDim, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp)) }
        when {
            trailing != null -> trailing()
            selected -> MdIcon(MdIcon.Check, "Selected", tint = Lc.check)
            chevron -> MdIcon(MdIcon.ChevronRight, null, tint = Lc.text)
        }
    }
}

/** A page of a sheet: back and its title (48 dp with the line under it), then its choices. */
@Composable
private fun SheetPageTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(47.1.dp).padding(start = 2.8.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(MdIcon.ArrowBack, "Back", size = 48.dp, tint = Lc.text, onClick = onBack)
        Spacer(Modifier.width(11.5.dp))
        Text(title, color = Lc.text, fontFamily = Inter, fontSize = 15.sp)
    }
    SheetDivider()
}

/**
 * A radio choice of a sheet's page, its button as the reference's: an 18 dp ring, or (chosen) a
 * light blue disc with a 4 dp dot.
 */
@Composable
fun RadioRow(label: String, selected: Boolean, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.8.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.size(18.dp).background(Lc.highlight, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(4.dp).background(Lc.bar, CircleShape))
            }
        } else {
            Box(Modifier.size(18.dp).border(1.5.dp, Lc.text, CircleShape))
        }
        Spacer(Modifier.width(28.3.dp))
        Text(label, color = Lc.text, fontFamily = Inter, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        detail?.let { Text(it, color = Lc.textDim, fontFamily = Inter, fontSize = 14.sp) }
    }
}

// ---------------------------------------------------------------------------------------- filter

/** Filter by: rating (at least, exactly or at most), flag, then pages of choices. */
@Composable
fun FilterSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<String?>(null) }
    val f = vm.filter
    val o = vm.overview
    LcSheet(onDismiss) {
        when (page) {
            "type" -> {
                SheetPageTitle("Type") { page = null }
                listOf(null to "All", "image" to "Photos", "raw" to "Raw photos", "video" to "Videos").forEach { (kind, label) ->
                    RadioRow(label, f.kind == kind) { vm.updateFilter(f.copy(kind = kind)) }
                }
            }
            "camera" -> FacetPage("Camera", o?.cameras.orEmpty(), f.camera, { page = null }) { vm.updateFilter(f.copy(camera = it)) }
            "people" -> FacetPage("People", o?.people.orEmpty(), f.person, { page = null }) { vm.updateFilter(f.copy(person = it)) }
            "location" -> FacetPage("Location", o?.places.orEmpty(), f.place, { page = null }) { vm.updateFilter(f.copy(place = it)) }
            "keyword" -> FacetPage("Keyword", o?.keywords.orEmpty(), f.keyword, { page = null }) { vm.updateFilter(f.copy(keyword = it)) }
            "edited" -> {
                SheetPageTitle("Edited") { page = null }
                listOf(null to "All", true to "Edited", false to "Not edited").forEach { (e, label) ->
                    RadioRow(label, f.edited == e) { vm.updateFilter(f.copy(edited = e)) }
                }
            }
            // as tall as 60 % of the screen, as the reference's, its rows at the top
            else -> Column(Modifier.heightIn(min = if (LocalPopupPlacement.current != null) 0.dp else (LocalConfiguration.current.screenHeightDp * 0.6f).dp)) {
                SheetHeading("Filter by")
                SheetDivider()
                // the rating: the operator, then the stars (tap the same star again for any rating)
                Row(Modifier.padding(start = 4.3.dp, top = 3.1.dp), verticalAlignment = Alignment.CenterVertically) {
                    val op = when (f.ratingOp) {
                        "exactly" -> LcIcon.Exactly
                        "atMost" -> LcIcon.AtMost
                        else -> LcIcon.AtLeast
                    }
                    FilterIcon(op, "Rating: ${f.ratingOp}", on = false) {
                        val next = when (f.ratingOp) {
                            "atLeast" -> "exactly"
                            "exactly" -> "atMost"
                            else -> "atLeast"
                        }
                        vm.updateFilter(f.copy(ratingOp = next))
                    }
                    for (stars in 1..5) {
                        FilterIcon(MdIcon.Star, if (stars == 1) "1 star" else "$stars stars", on = stars <= f.rating) {
                            vm.updateFilter(f.copy(rating = if (f.rating == stars) 0 else stars))
                        }
                    }
                }
                Row(Modifier.padding(start = 4.3.dp, bottom = 3.1.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Triple("pick", LcIcon.FlagPickSolid, "Picks"), Triple("none", LcIcon.FlagNone, "Unflagged"), Triple("reject", LcIcon.FlagRejectSolid, "Rejects"))
                        .forEach { (flag, icon, label) ->
                            FilterIcon(icon, label, on = f.flag == flag) { vm.updateFilter(f.copy(flag = if (f.flag == flag) null else flag)) }
                        }
                }
                SheetDivider()
                SheetRow(LcIcon.Film, "Type", chevron = true, detail = f.kind?.let { k -> mapOf("image" to "Photos", "raw" to "Raw", "video" to "Videos")[k] }) { page = "type" }
                SheetRow(MdIcon.PhotoCamera, "Camera", chevron = true, detail = f.camera) { page = "camera" }
                SheetRow(MdIcon.AccountCircle, "People", chevron = true, detail = f.person) { page = "people" }
                SheetRow(MdIcon.Place, "Location", chevron = true, detail = f.place) { page = "location" }
                SheetRow(MdIcon.Sell, "Keyword", chevron = true, detail = f.keyword) { page = "keyword" }
                SheetRow(LcIcon.Pencil, "Edited", chevron = true, detail = f.edited?.let { if (it) "Edited" else "Not edited" }) { page = "edited" }
                if (f.active) {
                    SheetDivider()
                    SheetRow(MdIcon.Close, "Clear filters") { vm.updateFilter(LibraryFilter(text = f.text)) }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FilterIcon(icon: Glyph, description: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 41.5.dp, height = 45.dp).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(icon, description, tint = if (on) Lc.choice else Lc.text, size = 24.dp)
    }
}

@Composable
private fun FacetPage(title: String, facets: List<Facet>, current: String?, onBack: () -> Unit, onPick: (String?) -> Unit) {
    SheetPageTitle(title, onBack)
    RadioRow("All", current == null) { onPick(null) }
    if (facets.isEmpty()) {
        Text("None in this library yet.", color = Lc.textDim, fontFamily = Inter, fontSize = 14.sp, modifier = Modifier.padding(start = 62.3.dp, top = 8.dp, bottom = 16.dp))
    }
    facets.forEach { RadioRow(it.name, current == it.name, detail = "${it.count}") { onPick(it.name) } }
    Spacer(Modifier.height(12.dp))
}

// ------------------------------------------------------------------------------------------- sort

@Composable
fun SortSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val icons = mapOf<String, Glyph>(
        "captureDate" to MdIcon.PhotoCamera,
        "importDate" to LcIcon.Import,
        "editDate" to LcIcon.Sliders,
        "fileName" to LcIcon.Az,
        "rating" to MdIcon.StarSharp,
    )
    LcSheet(onDismiss) {
        SheetHeading("Sort by")
        SheetDivider()
        sortKeys.forEach { (key, label) -> SheetRow(icons[key], label, selected = vm.sortKey == key) { vm.sortBy(key) } }
        SheetDivider()
        SheetRow(LcIcon.Sort, "Reverse order") { vm.reverseSort() }
    }
}

// ---------------------------------------------------------------------------------- more (⋮)

/** Select, the grid's view options and adding photos. */
@Composable
fun MoreSheet(vm: AppViewModel, onDismiss: () -> Unit, onPickPhotos: () -> Unit, onPickFiles: () -> Unit) {
    var page by remember { mutableStateOf<String?>(null) }
    var emptying by remember { mutableStateOf(false) }
    if (emptying) {
        AlertDialog(
            onDismissRequest = { emptying = false },
            containerColor = Lc.dialog,
            title = { Text("Permanently Delete All", fontFamily = Inter, color = Lc.text) },
            text = {
                val n = vm.photos.size
                Text(
                    if (n == 1) "The photo in Deleted goes for good, with the library's copy of it. This can't be undone." else "All $n photos in Deleted go for good, with the library's copies of them. This can't be undone.",
                    fontFamily = Inter,
                    color = Lc.textDim,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    emptying = false
                    onDismiss()
                    vm.deleteAllPermanently()
                }) { Text("Delete", fontFamily = Inter, color = Lc.selected) }
            },
            dismissButton = { TextButton(onClick = { emptying = false }) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
        )
    }
    // Deleted's own options, as the reference's: select, delete all for good, restore all
    if (vm.view.kind == "deleted") {
        LcSheet(onDismiss) {
            SheetRow(LcIcon.SelectBox, "Select", enabled = vm.photos.isNotEmpty()) {
                onDismiss()
                vm.startSelecting()
            }
            SheetRow(LcIcon.Trash, "Permanently Delete All", enabled = vm.photos.isNotEmpty()) { emptying = true }
            SheetRow(LcIcon.Undo, "Restore All", enabled = vm.photos.isNotEmpty()) {
                onDismiss()
                vm.restoreAll()
            }
            Spacer(Modifier.height(8.dp))
        }
        return
    }
    LcSheet(onDismiss) {
        if (page == "segments") {
            SheetPageTitle("Segment by") { page = null }
            segmentUnits.forEach { (unit, label) -> RadioRow(label, vm.segmentBy == unit) { vm.setSegments(unit) } }
            return@LcSheet
        }
        SheetHeading("Batch edit")
        SheetRow(LcIcon.SelectBox, "Select", enabled = vm.photos.isNotEmpty()) {
            onDismiss()
            vm.startSelecting()
        }
        SheetDivider()
        SheetHeading("View options")
        SheetRow(MdIcon.GridView, "Square grid", selected = !vm.justified) { vm.setJustifiedGrid(false) }
        SheetRow(LcIcon.GridJustified, "Justified grid", selected = vm.justified) { vm.setJustifiedGrid(true) }
        val segmented = vm.segmentBy != "none"
        SheetRow(LcIcon.Segments, "Grid segmentation", trailing = { LcSwitch(segmented) }) { vm.setSegments(if (segmented) "none" else "day") }
        // the unit only once there is one, as the reference's
        SheetRow(LcIcon.Calendar, "Segment by", chevron = true, detail = segmentUnits.firstOrNull { it.first == vm.segmentBy && segmented }?.second) { page = "segments" }
        SheetRow(LcIcon.Play, "Slideshow", enabled = vm.photos.isNotEmpty()) {
            onDismiss()
            vm.startSlideshow()
        }
        SheetDivider()
        // files (raws the photo picker doesn't list) from the folder at the row's end: the sheet
        // keeps the reference's rows
        SheetRow(LcIcon.AddPhoto, "Add photos", trailing = {
            BarButton(MdIcon.Folder, "Add files (raw, DNG…)", size = 48.dp, tint = Lc.text) {
                onDismiss()
                onPickFiles()
            }
        }) {
            onDismiss()
            onPickPhotos()
        }
    }
}

// --------------------------------------------------------------------------------- album picker

/**
 * The title's menu: a panel over the grid (inset, from under the status bar) with the library's
 * views and the albums, and a button that makes a new album.
 */
@Composable
fun AlbumPicker(vm: AppViewModel, onDismiss: () -> Unit, onNewAlbum: () -> Unit) {
    LcOverlay(onDismiss, Alignment.TopCenter) {
        // the panel: 500 dp at most, 12.6 dp from the sides, from 24.4 dp under the status bar
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.6.dp)
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(Modifier.height(24.4.dp))
            Column(Modifier.fillMaxSize().background(Lc.bar)) {
                BarButton(MdIcon.Close, "Close", size = 55.9.dp, tint = Lc.text, onClick = onDismiss)
                AlbumList(vm, onPicked = onDismiss, onNewAlbum = onNewAlbum, headingTop = 7.1.dp, headingBottom = 4.8.dp, headingStart = 16.1.dp, modifier = Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().padding(vertical = 15.75.dp), contentAlignment = Alignment.Center) { NewAlbumPill(onNewAlbum) }
            }
        }
    }
}

/**
 * Sideways, the views and albums stand beside the photos (the reference's pane: 255.8 dp, its
 * list 214 dp wide, the create pill floating at its foot).
 */
@Composable
fun AlbumPane(vm: AppViewModel, onNewAlbum: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        AlbumList(
            vm,
            onPicked = {},
            onNewAlbum = onNewAlbum,
            headingTop = 8.5.dp,
            headingBottom = 3.15.dp,
            headingStart = 17.5.dp,
            modifier = Modifier.width(214.dp).fillMaxHeight(),
            bottom = 80.dp,
        )
        Box(Modifier.width(214.dp).align(Alignment.BottomStart).padding(bottom = 16.55.dp), contentAlignment = Alignment.Center) { NewAlbumPill(onNewAlbum, Lc.canvas) }
    }
}

/** The library's views (with their counts) and the albums, a row each; [onPicked] after one is chosen. */
@Composable
private fun AlbumList(
    vm: AppViewModel,
    onPicked: () -> Unit,
    onNewAlbum: () -> Unit,
    headingTop: Dp,
    headingBottom: Dp,
    headingStart: Dp,
    modifier: Modifier = Modifier,
    bottom: Dp = 12.dp,
) {
    val o = vm.overview
    var libraryOpen by remember { mutableStateOf(true) }
    var opened by remember { mutableStateOf(setOf<ULong>()) }
    // folders first at each level, then the albums, each in the chosen order; a folder's albums under it when open
    val albums = remember(o, vm.albumSort, vm.albumSortUp, opened) {
        val all = o?.albums.orEmpty()
        val order: Comparator<AlbumInfo> = when (vm.albumSort) {
            "time" -> compareBy { it.id }
            "count" -> compareBy { it.count }
            else -> compareBy { it.name.lowercase() }
        }
        fun level(parent: ULong?, depth: Int): List<Pair<AlbumInfo, Int>> {
            val here = all.filter { it.parent == parent }.sortedWith(if (vm.albumSortUp) order else order.reversed()).sortedByDescending { it.folder }
            return here.flatMap { a -> listOf(a to depth) + if (a.folder && a.id in opened) level(a.id, depth + 1) else emptyList() }
        }
        level(null, 0)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottom)) {
        item(key = "library") {
            // where the reference names itself ("… albums"), LightCraft does
            PickerHeading("LightCraft albums", top = headingTop, bottom = headingBottom, start = headingStart) {
                // open ⌄, folded ‹ (the reference's)
                BarButton(if (libraryOpen) MdIcon.ExpandMore else MdIcon.ChevronLeft, if (libraryOpen) "Collapse" else "Expand", size = 48.2.dp, iconSize = 26.dp, tint = Lc.text) {
                    libraryOpen = !libraryOpen
                }
            }
        }
        if (libraryOpen) {
            // each icon at the reference's size for it
            val rows = listOf(
                PickerView(LibraryView.ALL, LcIcon.PhotoStack, 26.dp, o?.all),
                PickerView(LibraryView.EDITED, LcIcon.Pencil, 21.5.dp, o?.edited),
                PickerView(LibraryView.UNEDITED, LcIcon.PencilOff, 21.5.dp, o?.unedited),
                PickerView(LibraryView.RECENT, LcIcon.Import, 21.5.dp, o?.recentlyAdded),
                PickerView(LibraryView.DELETED, LcIcon.Trash, 28.dp, o?.deleted),
            )
            items(rows, key = { it.view.kind }) { (v, icon, size, count) ->
                PickerRow(v.name, count, selected = vm.view.kind == v.kind, picture = { Tile { LcIcon(icon, null, tint = Lc.icon, size = size) } }) {
                    vm.showView(v)
                    onPicked()
                }
            }
        }
        item(key = "albums") {
            PickerHeading("Your albums", top = 22.4.dp, bottom = 0.dp, start = headingStart) {
                BarButton(LcIcon.Sort, "Sort albums", size = 48.2.dp, tint = Lc.text) { vm.albumDialog = AlbumDialog("sort") }
            }
        }
        items(albums, key = { "album:${it.first.id}" }) { (a, depth) ->
            Box(Modifier.padding(start = 16.dp * depth)) {
                val picture: @Composable () -> Unit = if (a.folder) {
                    { Tile { LcIcon(LcIcon.Folder, null, tint = Lc.icon, size = 24.dp) } }
                } else {
                    { AlbumCover(vm, a) }
                }
                PickerRow(a.name, a.count, selected = vm.view.albumId == a.id, picture = picture, onLongClick = { vm.albumMenu = a }) {
                    if (a.folder) {
                        opened = if (a.id in opened) opened - a.id else opened + a.id
                    } else {
                        vm.showView(LibraryView.album(a.id, a.name))
                        onPicked()
                    }
                }
            }
        }
        item(key = "new") {
            PickerRow("Add new", null, selected = false, picture = { Tile { MdIcon(MdIcon.Add, null, tint = Lc.icon) } }, onClick = onNewAlbum)
        }
    }
}

/** The Add new pill (an album or a folder): white outline, 44.7 dp tall (the reference's); [fill] behind its words (the pane's, where rows pass under it). */
@Composable
private fun NewAlbumPill(onNewAlbum: () -> Unit, fill: Color = Color.Transparent) {
    Row(
        Modifier
            .height(44.7.dp)
            .clip(RoundedCornerShape(22.35.dp))
            .border(2.dp, Color.White, RoundedCornerShape(22.35.dp))
            .background(fill)
            .clickable(role = Role.Button, onClick = onNewAlbum)
            .padding(start = 11.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MdIcon(MdIcon.Add, null, tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text("Add new", color = Color.White, fontFamily = Inter, fontSize = 16.sp)
    }
}

/** A heading of the menu (18 sp) with its button 18.6 dp from the right edge, [top] and [bottom] apart from its neighbours as the reference's. */
private data class PickerView(val view: LibraryView, val icon: LcIcon, val size: Dp, val count: UInt?)

@Composable
private fun PickerHeading(text: String, top: Dp, bottom: Dp, start: Dp, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = bottom).height(48.2.dp).padding(start = start), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Lc.text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // its button a little above the heading's middle, as the reference's
        Box(Modifier.offset(x = 5.5.dp, y = (-1.5).dp)) { action() }
    }
}

@Composable
private fun Tile(content: @Composable () -> Unit) {
    Box(Modifier.size(51.1.dp).clip(RoundedCornerShape(2.dp)).background(tileColor), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun AlbumCover(vm: AppViewModel, a: AlbumInfo) {
    val cover = a.cover
    val image by produceState<ImageBitmap?>(null, cover) {
        if (cover != null) vm.photoInfo(cover)?.let { p -> vm.thumbnail(p)?.let { value = it } }
    }
    Tile {
        val img = image
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) else LcIcon(LcIcon.Photos, null, tint = Lc.icon, size = 24.dp)
    }
}

/** A picker row's photo count (sampled from the reference's), dimmer than other secondary text. */
private val pickerCount = Color(0xFF6A6A6A)

/**
 * A row every 67.6 dp: a picture, the name with its count under it; the current one on a 66.6 dp
 * highlight 12.5 dp in from the sides, its corners rounded 3.9 dp. An album's row has its options
 * on a long press ([onLongClick]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickerRow(name: String, count: UInt?, selected: Boolean, picture: @Composable () -> Unit, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(67.6.dp)
            .padding(horizontal = 12.5.dp, vertical = 0.5.dp)
            .clip(RoundedCornerShape(3.9.dp))
            .background(if (selected) Lc.divider else Color.Transparent)
            .combinedClickable(role = Role.Button, onLongClickLabel = onLongClick?.let { "Album options" }, onLongClick = onLongClick, onClick = onClick)
            .padding(start = 7.9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        picture()
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).padding(top = 2.2.dp)) {
            Text(name, color = Lc.text, fontFamily = Inter, fontSize = 15.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            count?.let { Text("$it", color = pickerCount, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp) }
        }
    }
}

@Composable
fun NameDialog(title: String, action: String, initial: String = "", allowBlank: Boolean = false, fieldLabel: String = "Name", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    // a name to change starts with the cursor after it
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val name = field.text
    // the reference's dialogs: 320 dp of its grey, 5 dp corners
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lc.dialog,
        shape = RoundedCornerShape(5.dp),
        modifier = Modifier.width(320.dp),
        title = { Text(title, fontFamily = Inter, color = Lc.text) },
        text = { OutlinedTextField(value = field, onValueChange = { field = it }, singleLine = true, label = { Text(fieldLabel, fontFamily = Inter) }) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = allowBlank || name.isNotBlank()) { Text(action, fontFamily = Inter, color = Lc.selected) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
    )
}

// ----------------------------------------------------------------------------------------- drawer

/** The menu drawer: LightCraft's card, then preferences, storage, about and help. */
@Composable
fun DrawerContent(onPick: (String) -> Unit) {
    Column(
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start))
            .width(280.2.dp)
            .fillMaxHeight()
            .background(Lc.bar)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(Modifier.padding(start = 12.2.dp, top = 23.9.dp, bottom = 23.9.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_tile), contentDescription = null, modifier = Modifier.size(59.8.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("LightCraft", color = Lc.text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Photo editor", color = Lc.textDim, fontFamily = Inter, fontSize = 13.sp)
            }
        }
        Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(1.dp).background(Lc.divider))
        // the first row's middle 25.6 dp under the line, as the reference's
        Spacer(Modifier.height(1.5.dp))
        listOf(
            Triple("preferences", MdIcon.Settings, "Preferences"),
            Triple("storage", MdIcon.PhoneIphone, "Local storage"),
            Triple("about", MdIcon.Info, "About LightCraft"),
            Triple("help", MdIcon.Help, "Help"),
        ).forEach { (id, icon, label) ->
            Row(
                Modifier.fillMaxWidth().height(48.2.dp).clickable(role = Role.Button) { onPick(id) }.padding(start = 11.2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdIcon(icon, null, tint = Lc.text)
                Spacer(Modifier.width(11.dp))
                Text(label, color = Lc.text, fontFamily = Inter, fontSize = 15.sp)
            }
        }
    }
}

/** Rate and flag the selected photos. */
@Composable
fun RateFlagSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    // the rating and flag the selected photos share, if they do: shown blue (and updated as they change)
    val chosen = vm.photos.filter { it.id in vm.selected }
    val rating = chosen.map { it.rating.toInt() }.distinct().singleOrNull()
    val flag = chosen.map { it.flag }.distinct().singleOrNull()
    LcSheet(onDismiss) {
        SheetHeading("Rate and flag")
        SheetDivider()
        Row(Modifier.padding(start = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (stars in 0..5) {
                val on = rating == stars
                val tint = if (on) Lc.highlight else Lc.text
                Box(Modifier.size(width = 52.dp, height = 48.dp).selectable(on, role = Role.Button) { vm.rateSelected(stars) }, contentAlignment = Alignment.Center) {
                    if (stars == 0) Text("0", color = tint, fontFamily = Inter, fontSize = 16.sp) else MdIcon(MdIcon.Star, if (stars == 1) "1 star" else "$stars stars", tint = tint)
                    if (stars > 0) Text("$stars", color = if (on) Lc.highlight else Lc.textDim, fontFamily = Inter, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        Row(Modifier.padding(start = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(
                Triple(PhotoFlag.PICK, LcIcon.FlagPickSolid, "Pick"),
                Triple(PhotoFlag.UNFLAGGED, LcIcon.FlagNone, "Unflag"),
                Triple(PhotoFlag.REJECT, LcIcon.FlagRejectSolid, "Reject"),
            ).forEach { (f, icon, label) ->
                val on = flag == f
                Box(Modifier.size(width = 52.dp, height = 48.dp).selectable(on, role = Role.Button) { vm.flagSelected(f.key) }, contentAlignment = Alignment.Center) {
                    LcIcon(icon, label, tint = if (on) Lc.highlight else Lc.text, size = 24.dp)
                }
            }
        }
    }
}

/** The engine's name for a flag (`photo.flag`). */
private val PhotoFlag.key
    get() = when (this) {
        PhotoFlag.PICK -> "pick"
        PhotoFlag.REJECT -> "reject"
        PhotoFlag.UNFLAGGED -> "none"
    }

/** Add the selected photos to an album (or a new one); [move]: move them there from the album on show. */
@Composable
fun AddToAlbumSheet(vm: AppViewModel, onDismiss: () -> Unit, onNewAlbum: () -> Unit, move: Boolean = false, title: String? = null, onPick: ((AlbumInfo) -> Unit)? = null) {
    LcSheet(onDismiss) {
        SheetHeading(title ?: if (move) "Move to" else "Add to album")
        SheetDivider()
        vm.overview?.albums.orEmpty().filter { !it.smart && !it.folder && !(move && it.id == vm.view.albumId) }.forEach { a ->
            SheetRow(LcIcon.Photos, a.name, detail = "${a.count}") {
                onDismiss()
                when {
                    onPick != null -> onPick(a)
                    move -> vm.moveSelectedToAlbum(a)
                    else -> vm.addSelectedToAlbum(a)
                }
            }
        }
        SheetRow(MdIcon.Add, "New album") {
            onDismiss()
            onNewAlbum()
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Select mode's More, as the reference's: Select All and Select None; Add to and (in an album) Move
 * to; Edit Info, (in an album, one photo chosen) Set As Cover and Paste Settings; photo merges.
 */
@Composable
fun SelectMoreSheet(vm: AppViewModel, onDismiss: () -> Unit, onSheet: (String) -> Unit) {
    val any = vm.selected.isNotEmpty()
    val inAlbum = vm.view.kind == "album" && vm.overview?.albums?.find { it.id == vm.view.albumId }?.smart == false
    LcSheet(onDismiss) {
        SheetRow(LcIcon.SelectBox, "Select All", enabled = vm.selected.size < vm.photos.size) {
            onDismiss()
            if (vm.selected.size < vm.photos.size) vm.selectAll()
        }
        SheetRow(MdIcon.CheckBoxOutline, "Select None", enabled = any) {
            onDismiss()
            vm.selectNone()
        }
        SheetDivider()
        SheetRow(LcIcon.Photos, "Add to", enabled = any) { onSheet("album") }
        if (inAlbum) SheetRow(LcIcon.Folder, "Move to", enabled = any) { onSheet("move") }
        SheetDivider()
        SheetRow(MdIcon.Info, "Edit Info", enabled = any) { onSheet("editInfo") }
        if (inAlbum) {
            SheetRow(LcIcon.PhotoStack, "Set As Cover", enabled = vm.selected.size == 1) {
                onDismiss()
                vm.setSelectedAsCover()
            }
        }
        SheetRow(LcIcon.PasteSettings, "Paste Settings", enabled = any && vm.hasCopiedSettings) {
            onDismiss()
            vm.pasteToSelected()
        }
        if (vm.view.kind != "deleted") {
            SheetDivider()
            SheetRow(LcIcon.PhotoStack, "HDR Merge", enabled = vm.selected.size >= 2 && !vm.merging) {
                onDismiss()
                vm.mergeSelected("merge.hdr")
            }
            SheetRow(LcIcon.PhotoStack, "Panorama Merge", enabled = vm.selected.size >= 2 && !vm.merging) {
                onDismiss()
                vm.mergeSelected("merge.panorama")
            }
            SheetRow(LcIcon.PhotoStack, "HDR Panorama Merge", enabled = vm.selected.size >= 4 && !vm.merging) {
                onDismiss()
                vm.mergeSelected("merge.hdrPanorama")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun MergeOptionsDialog(vm: AppViewModel, dialog: MergeDialogState, onDismiss: () -> Unit) {
    val projections = listOf(
        "auto" to "Auto",
        "spherical" to "Spherical",
        "cylindrical" to "Cylindrical",
        "perspective" to "Perspective",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Lc.dialog,
        title = { Text(dialog.title, fontFamily = Inter, color = Lc.text) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 260.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Lc.canvas),
                    contentAlignment = Alignment.Center,
                ) {
                    dialog.preview?.let {
                        Image(it, "Merge preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    if (dialog.loading) CircularProgressIndicator(color = Lc.highlight, trackColor = Lc.divider)
                    if (dialog.preview == null && !dialog.loading && dialog.error == null) {
                        Text("Preview unavailable", color = Lc.textDim, fontFamily = Inter, fontSize = 14.sp)
                    }
                }
                if (dialog.command != "merge.hdr") {
                    Text("Projection", color = Lc.text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    projections.forEach { (id, label) ->
                        RadioRow(label, selected = dialog.projection == id) { vm.setMergeProjection(id) }
                    }
                }
                dialog.error?.let {
                    Text(it, color = Lc.reject, fontFamily = Inter, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = dialog.preview != null && !dialog.loading && !vm.merging,
                onClick = vm::confirmMerge,
            ) { Text("Merge", fontFamily = Inter, color = Lc.selected) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", fontFamily = Inter, color = Lc.text) }
        },
    )
}

/**
 * Select mode's Delete in an album, as the reference's: the album's name, what will happen, then
 * Remove from album (the photos stay in the library) or Delete (to Deleted). Undo brings either back.
 */
@Composable
fun AlbumRemoveSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val n = vm.selected.size
    LcSheet(onDismiss) {
        SheetHeading(vm.view.name)
        Text(
            if (n == 1) "Remove the photo from this album, or delete it from the library?" else "Remove the $n photos from this album, or delete them from the library?",
            color = Lc.textDim,
            fontFamily = Inter,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        SheetDivider()
        SheetRow(LcIcon.Photos, "Remove from album") {
            onDismiss()
            vm.removeSelectedFromAlbum()
        }
        SheetRow(LcIcon.Trash, "Delete") {
            onDismiss()
            vm.deleteSelected()
        }
        Spacer(Modifier.height(8.dp))
    }
}
