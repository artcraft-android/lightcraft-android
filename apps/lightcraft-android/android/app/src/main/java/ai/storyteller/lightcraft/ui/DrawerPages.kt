package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.core.CloudStatus
import ai.storyteller.lightcraft.core.engineVersion
import ai.storyteller.lightcraft.engine.CloudConfig
import ai.storyteller.lightcraft.engine.LibraryFolder
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A page's row text and its notes under it, sampled from the reference's preferences. */
private val pageText = Color(0xFFEBEBEB)
private val pageNote = Color(0xFF8D8D8D)

/**
 * A page from the menu drawer, as the reference's (Preferences, Local storage, About): over the
 * whole screen at once (no motion, as its), a 56 dp bar with back (55.9 dp) and the title at
 * 75.9 dp, then the page on black. Back returns to the drawer.
 */
@Composable
fun DrawerPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val config = LocalConfiguration.current
    if (config.screenWidthDp > config.screenHeightDp) {
        PageDialog(title, onBack, content)
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Lc.canvas)
            // taps stay on the page
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxWidth().background(Lc.bar)) {
            StatusStrip()
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton(MdIcon.ArrowBack, "Back", size = 55.9.dp, tint = Lc.text, onClick = onBack)
                Spacer(Modifier.width(20.dp))
                Text(title, color = Lc.text, fontFamily = Inter, fontSize = 18.sp, modifier = Modifier.semantics { heading() })
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * Sideways the reference shows these pages as a 500 × 420.3 dp card (9.7 dp corners) in the middle
 * of the screen, over the dimmed drawer: the title 68 dp in, close (×), a 2 dp line, then the rows.
 */
@Composable
private fun PageDialog(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(500.dp)
                .height(420.3.dp)
                .clip(RoundedCornerShape(9.7.dp))
                .background(Lc.bar)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Text(title, color = Lc.text, fontFamily = Inter, fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 68.dp).semantics { heading() })
                Box(Modifier.align(Alignment.CenterStart).offset(x = 406.4.dp)) {
                    BarButton(MdIcon.Close, "Close", size = 48.dp, iconSize = 22.5.dp, tint = Lc.text, onClick = onBack)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Lc.divider))
            CompositionLocalProvider(LocalInDialog provides true) {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp), content = content)
            }
        }
    }
}

/** In [PageDialog]: the groups span the card. */
private val LocalInDialog = staticCompositionLocalOf { false }

/** A group of a page's rows: `#1d1d1d` on black, 500 dp at most, 7.8 dp from the one above. */
@Composable
private fun PageGroup(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(7.8.dp))
    val margin = if (LocalInDialog.current) 0.dp else 12.6.dp
    Column(Modifier.padding(horizontal = margin).widthIn(max = 500.dp).fillMaxWidth().background(Lc.bar), content = content)
}

/** A setting with a switch, and what it does under it (the reference's: 16 sp, its note 13 sp). */
@Composable
private fun PageSwitch(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(start = 13.7.dp, end = 12.7.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = pageText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
            LcSwitch(checked)
        }
        note?.let {
            Text(it, color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

/** A heading in a group, in capitals (`IMAGE QUALITY`). */
@Composable
private fun PageHeading(text: String) {
    Text(text.uppercase(Locale.ROOT), color = pageText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(start = 13.7.dp, top = 14.dp, bottom = 6.dp))
}

/** A setting that opens more (the reference's: 45.2 dp, 16 sp, its value grey before a chevron). */
@Composable
private fun PageRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(45.2.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 13.7.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = pageText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.weight(1f))
        value?.let { Text(it, color = pageValue, fontFamily = Inter, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(start = 12.dp).widthIn(max = 200.dp)) }
        MdIcon(MdIcon.ChevronRight, null, tint = pageValue, size = 22.dp)
    }
}

private val pageValue = Color(0xFF848484)

/** A row of the raw defaults sheet, as the reference's: 48 dp, the label 16 dp in (17 sp), a tick or a chevron at the end. */
@Composable
private fun OptionRow(label: String, selected: Boolean = false, chevron: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Lc.choice else pageText, fontFamily = Inter, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        when {
            selected -> MdIcon(MdIcon.Check, "Selected", tint = Lc.check)
            chevron -> MdIcon(MdIcon.ChevronRight, null, tint = pageText)
        }
    }
}

/** Preferences: how photos come in, how the grid looks and how photos render. */
@Composable
fun PreferencesPage(vm: AppViewModel, onBack: () -> Unit) {
    var gpu by remember { mutableStateOf<Boolean?>(null) }
    var sub by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { gpu = vm.gpuEnabled() }
    if (sub == "import") {
        ImportOptionsPage(vm) { sub = null }
        return
    }
    if (sub == "folder") {
        FolderLibraryPage(vm) { sub = null }
        return
    }
    if (sub == "cloud") {
        CloudLibraryPage(vm) { sub = null }
        return
    }
    DrawerPage("Preferences", onBack) {
        // the reference's rows that open pages come first
        PageGroup {
            PageRow("Photo import options", null) { sub = "import" }
            PageRow("Library folder", if (vm.folderLibrary.enabled) "On" else "Off") { sub = "folder" }
            PageRow("Cloud library", if (vm.cloudConfigured) "On" else "Off") { sub = "cloud" }
        }
        gpu?.let { on ->
            PageGroup {
                PageHeading("Rendering")
                PageSwitch("Render with the GPU", "Faster where the phone's graphics driver can run LightCraft's shaders. Turn it off if photos look wrong.", on) { v ->
                    gpu = v
                    vm.setGpu(v)
                }
            }
        }
    }
}

/**
 * Library folder: edit the photos of a folder where they are, instead of in LightCraft's own copy.
 * The edits are saved in XMP sidecars next to the photos, which other apps and sync tools see.
 * Needs "All files access" (the system's page for it opens from the switch) and a folder.
 */
@Composable
private fun FolderLibraryPage(vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val f = vm.folderLibrary
    // turning on takes up to two steps away from the page (the permission, the folder): the switch carries on after each
    var turningOn by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(LibraryFolder.allowed()) }
    val carryOn = {
        when {
            !LibraryFolder.allowed() -> Unit
            vm.folderLibrary.folder == null -> Unit
            else -> {
                turningOn = false
                vm.setFolderLibrary(true)
            }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // a folder picked from the Folder row while the switch is off is only remembered
        if (uri != null && vm.chooseLibraryFolder(uri) && turningOn) carryOn() else turningOn = false
    }
    // back from the system's page for All files access
    LifecycleResumeEffect(Unit) {
        allowed = LibraryFolder.allowed()
        if (turningOn) {
            // not given: the switch goes back off
            if (!allowed) turningOn = false else if (vm.folderLibrary.folder == null) pickFolder.launch(null) else carryOn()
        }
        onPauseOrDispose {}
    }
    DrawerPage("Library folder", onBack) {
        PageGroup {
            PageSwitch(
                "Edit photos in place in a folder",
                "Photos stay in the folder and LightCraft saves its edits in XMP sidecars next to them, where other apps and sync tools see them. " +
                    "Turning it off keeps the photos in the library and deletes nothing.",
                f.enabled || turningOn,
            ) { on ->
                when {
                    !on -> {
                        turningOn = false
                        vm.setFolderLibrary(false)
                    }
                    !LibraryFolder.supported -> vm.tell("Editing in place needs Android 11 or later")
                    !allowed -> {
                        turningOn = true
                        openAllFilesAccess(context)
                    }
                    f.folder == null -> {
                        turningOn = true
                        pickFolder.launch(null)
                    }
                    else -> vm.setFolderLibrary(true)
                }
            }
        }
        PageGroup {
            PageRow("Folder", f.folder?.let { it.substringAfterLast('/') } ?: "Choose") { pickFolder.launch(null) }
            f.folder?.let { Text(it, color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.fillMaxWidth().padding(start = 13.7.dp, end = 12.7.dp, bottom = 12.dp)) }
            if (f.enabled) PageRow("Synchronize folder", if (vm.folderSyncing) "Working…" else null) { vm.syncFolder(manual = true) }
        }
        val note = when {
            f.enabled && !allowed -> "LightCraft no longer has All files access, so the folder isn't read. Turn it back on in the system's settings."
            f.enabled && f.syncedAt > 0L -> "Synchronized ${DateUtils.getRelativeTimeSpanString(f.syncedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}: ${f.result.orEmpty()}"
            f.enabled -> f.result
            else -> "Synchronizing adds the folder's new photos and reads edits other apps saved to sidecars. It runs whenever LightCraft opens."
        }
        note?.let {
            Text(it, color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 26.3.dp, vertical = 10.dp).widthIn(max = 474.dp))
        }
    }
}

private val pageError = Color(0xFFE57373)

/** A row that does something (blue text, 45.2 dp), greyed while it can't. */
@Composable
private fun PageAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(45.2.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 13.7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (enabled) Lc.selected else pageValue, fontFamily = Inter, fontSize = 16.sp)
    }
}

/** A fact and its value, side by side (the connected cloud library's page). */
@Composable
private fun PageFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 13.7.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pageText, fontFamily = Inter, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(value, color = pageValue, fontFamily = Inter, fontSize = 16.sp, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp).widthIn(max = 240.dp))
    }
}

@Composable
private fun PageNote(text: String, color: Color = pageNote) {
    Text(text, color = color, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 26.3.dp, vertical = 10.dp).widthIn(max = 474.dp))
}

@Composable
private fun PageBusy(text: String) {
    Row(Modifier.padding(horizontal = 26.3.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Lc.highlight, trackColor = Lc.divider, strokeWidth = 2.dp)
        Text(text, color = pageText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp))
    }
}

private val cacheChoices = listOf(1L, 2L, 5L, 10L)

/**
 * Cloud library: the photos a PC published to cloud storage (docs/cloud-library.md), edited here and
 * sent back. Not connected: pick the rclone config file, the remote in it, the folder on it and this
 * phone's name. Connected: what is there and what is waiting, the size of the preview cache,
 * Refresh now and Disconnect.
 */
@Composable
private fun CloudLibraryPage(vm: AppViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshCloudStatus() }
    val st = vm.cloud
    DrawerPage("Cloud library", onBack) {
        if (st != null && vm.cloudConfigured) CloudConnected(vm, st) else CloudConnect(vm)
    }
}

@Composable
private fun ColumnScope.CloudConnect(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var remotes by remember { mutableStateOf<List<String>>(emptyList()) }
    var remote by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf("") }
    var device by remember { mutableStateOf(CloudConfig.defaultDeviceName()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val busy = vm.cloudBusy
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { vm.importCloudConfig(uri) }
                    .onSuccess {
                        remotes = it
                        remote = it.firstOrNull()
                        problem = if (it.isEmpty()) "That file has no remote in it. Pick the rclone config file made for the phone." else null
                    }
                    .onFailure { problem = "Couldn't read that file: ${it.message}" }
            }
        }
    }
    PageGroup {
        PageNote("Show the photos a PC published to cloud storage, edit them here and send the edits back. It needs an rclone config file for a phone-only remote; see the cloud library notes on how to make one.")
        PageAction(if (remotes.isEmpty()) "Connect…" else "Choose another config file…", enabled = busy == null) {
            runCatching { pick.launch(arrayOf("*/*")) }.onFailure { vm.tell("There's no app to pick files with.") }
        }
    }
    if (remotes.isNotEmpty()) {
        PageGroup {
            if (remotes.size > 1) {
                PageHeading("Remote")
                remotes.forEach { OptionRow(it, selected = it == remote) { remote = it } }
            } else {
                PageFact("Remote", remotes.first())
            }
            Column(Modifier.padding(horizontal = 13.7.dp, vertical = 6.dp)) {
                OutlinedTextField(
                    folder,
                    { folder = it },
                    singleLine = true,
                    enabled = busy == null,
                    label = { Text("Folder on the remote", fontFamily = Inter) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Leave empty if the remote is already limited to the library folder, else e.g. LightCraft Cloud.",
                    color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                OutlinedTextField(
                    device,
                    { device = it },
                    singleLine = true,
                    enabled = busy == null,
                    isError = device.isNotEmpty() && !CloudConfig.validDevice(device.trim()),
                    label = { Text("Name of this phone", fontFamily = Inter) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The PC files this phone's edits under it: letters, digits, _ - and . (up to 64).",
                    color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
            }
            PageAction("Connect", enabled = busy == null && remote != null && CloudConfig.validDevice(device.trim())) {
                problem = null
                vm.connectCloud(remote ?: return@PageAction, folder, device)
            }
        }
    }
    busy?.let { PageBusy(it) }
    (problem ?: vm.cloudError)?.let { PageNote(it, pageError) }
}

@Composable
private fun ColumnScope.CloudConnected(vm: AppViewModel, st: CloudStatus) {
    var confirm by remember { mutableStateOf(false) }
    val busy = vm.cloudBusy
    PageGroup {
        PageFact("Remote", st.remote)
        PageFact("This phone", st.device)
        PageFact("Photos", st.photos.toString())
        PageFact("Edits waiting to be sent", st.pendingUploads.toString())
        PageFact("Previews on the phone", "${gb(st.cacheBytes.toLong())} of ${gb(st.cacheCap.toLong())}")
        PageFact("Last refresh", relativeTime(st.lastRefresh) ?: "Never")
    }
    st.lastError?.let { PageNote("Last error: $it", pageError) }
    // (an automatic refresh that failed said nothing at the time)
    vm.cloudAutoError?.takeIf { it != st.lastError }?.let { PageNote("Last refresh failed: $it", pageError) }
    if (!st.connected) PageNote("The cloud library couldn't be reached when LightCraft started. Refresh now tries again; Disconnect lets you set it up again.", pageError)
    PageGroup {
        PageHeading("Preview cache size")
        cacheChoices.forEach { g ->
            val bytes = g shl 30
            OptionRow("$g GB", selected = st.cacheCap.toLong() == bytes) { vm.setCloudCacheCap(bytes) }
        }
    }
    PageNote("The least recently opened previews are deleted first and downloaded again when you open the photo.")
    PageGroup {
        PageAction(busy ?: "Refresh now", enabled = busy == null) { vm.cloudRefreshNow() }
        PageAction("Disconnect", enabled = busy == null) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Lc.dialog,
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier.width(320.dp),
            title = { Text("Disconnect?", fontFamily = Inter, color = Lc.text) },
            text = {
                Text(
                    "LightCraft stops syncing with the cloud library. The photos, their thumbnails, the downloaded previews and your edits stay on the phone, but edits not sent yet won't be sent (Refresh now first, to send them). Nothing on the cloud storage changes.",
                    fontFamily = Inter, color = Lc.text, fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.disconnectCloud() }) { Text("Disconnect", fontFamily = Inter, color = Lc.selected) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", fontFamily = Inter, color = Lc.text) } },
        )
    }
}

/** "5 minutes ago" for a UTC time like `2026-10-27T09:42:15Z`; null when there is none or it can't be read. */
private fun relativeTime(iso: String?): String? {
    val at = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    return DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** The system's page where the person gives LightCraft "All files access" (this app's own page, else the list of apps). */
private fun openAllFilesAccess(context: Context) {
    val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(own) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

/**
 * Photo import options, as the reference's: the raw defaults (LightCraft's own, or a preset of
 * yours or LightCraft's, on a sheet as the reference's) and a copyright given to photos that come in
 * without one. The library keeps them; they apply as photos are added.
 */
@Composable
private fun ImportOptionsPage(vm: AppViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadImportDefaults() }
    val d = vm.importDefaults
    val rawPreset = d?.optString("rawPreset")?.takeIf { it.isNotBlank() && it != "null" }
    val presetName = rawPreset?.let { id -> vm.presets.firstOrNull { it.id == id }?.name }
    val copyright = d?.optString("copyright").orEmpty()
    var sheet by remember { mutableStateOf<String?>(null) }
    var editingCopyright by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        DrawerPage("Photo import options", onBack) {
            PageGroup {
                PageRow("Raw defaults", if (rawPreset == null) "LightCraft default" else presetName ?: "Preset") { sheet = "raw" }
                PageRow("Add copyright", copyright.ifBlank { "Off" }) { editingCopyright = true }
            }
            Text(
                "Raw defaults are how raw photos look as they come in; the copyright goes to photos that don't carry one.",
                color = pageNote,
                fontFamily = Inter,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 26.3.dp, vertical = 10.dp).widthIn(max = 474.dp),
            )
        }
        OverlayHost(sheet) { which ->
            LcSheet({ sheet = null }) {
                if (which == "raw") {
                    // the reference's sheet: its default or a preset (ticked), then Choose preset
                    Spacer(Modifier.height(10.dp))
                    OptionRow("LightCraft default", selected = rawPreset == null) {
                        sheet = null
                        vm.changeImportDefaults(JSONObject().put("rawPreset", "default"))
                    }
                    OptionRow(presetName?.let { "Preset: $it" } ?: "Preset", selected = rawPreset != null) { sheet = "presets" }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF171717)))
                    OptionRow("Choose preset", chevron = true) { sheet = "presets" }
                    Spacer(Modifier.height(8.dp))
                } else {
                    SheetHeading("Presets")
                    vm.presets.groupBy { it.group }.forEach { (group, presets) ->
                        Text(group, color = pageNote, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
                        presets.forEach { p ->
                            OptionRow(p.name, selected = p.id == rawPreset) {
                                sheet = null
                                vm.changeImportDefaults(JSONObject().put("rawPreset", p.id))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (editingCopyright) {
        NameDialog("Add copyright", "Done", initial = copyright, allowBlank = true, fieldLabel = "Copyright (empty: none)", onDismiss = { editingCopyright = false }) { text ->
            editingCopyright = false
            vm.changeImportDefaults(JSONObject().put("copyright", text.trim()))
        }
    }
}

/** Local storage, as the reference's page: the phone's space used, then what LightCraft's library takes. */
@Composable
fun StoragePage(vm: AppViewModel, onBack: () -> Unit) {
    var info by remember { mutableStateOf<StorageInfo?>(null) }
    LaunchedEffect(Unit) { info = vm.storageInfo() }
    var details by remember { mutableStateOf(true) }
    DrawerPage("Local storage", onBack) {
        val s = info
        Column(Modifier.padding(horizontal = 32.6.dp).widthIn(max = 460.dp).fillMaxWidth()) {
            Spacer(Modifier.height(27.7.dp))
            Text("Storage used", color = pageNote, fontFamily = Inter, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))
            Text("Phone storage", color = pageText, fontFamily = Inter, fontSize = 15.sp)
            Spacer(Modifier.height(36.dp))
            Text("SPACE USED", color = pageNote, fontFamily = Inter, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            // the phone's space: used, of which LightCraft's library in blue
            val total = s?.deviceTotal?.coerceAtLeast(1L) ?: 1L
            Box(Modifier.fillMaxWidth().height(7.8.dp).background(Lc.divider)) {
                if (s != null) {
                    Box(Modifier.fillMaxWidth(((s.deviceTotal - s.deviceFree).toFloat() / total).coerceIn(0f, 1f)).height(7.8.dp).background(Lc.textDim))
                    Box(Modifier.fillMaxWidth((s.libraryBytes.toFloat() / total).coerceIn(0f, 1f)).height(7.8.dp).background(Lc.highlight))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(s?.let { "${gb(it.deviceTotal - it.deviceFree)} of ${gb(it.deviceTotal)} used" } ?: "…", color = pageText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(s?.let { "${gb(it.deviceFree)} free" } ?: "", color = pageText, fontFamily = Inter, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().height(47.7.dp).clickable(role = Role.Button) { details = !details },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(7.8.dp))
                MdIcon(if (details) MdIcon.ExpandMore else MdIcon.ChevronRight, null, tint = pageText, size = 16.dp)
                Spacer(Modifier.width(12.2.dp))
                Text("Details", color = pageText, fontFamily = Inter, fontSize = 15.sp)
            }
            if (details && s != null) {
                DetailRow(Lc.highlight, "LightCraft's library (${s.photos} photos)", gb(s.libraryBytes))
                DetailRow(Lc.textDim, "Everything else", gb((s.deviceTotal - s.deviceFree - s.libraryBytes).coerceAtLeast(0L)))
                DetailRow(Lc.divider, "Free", gb(s.deviceFree))
                Spacer(Modifier.height(12.dp))
                Text(s.libraryPath, color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun DetailRow(colour: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.2.dp)) {
        Box(Modifier.size(10.2.dp).background(colour))
        Text(label, color = pageText, fontFamily = Inter, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = pageText, fontFamily = Inter, fontSize = 15.sp)
    }
}

private fun gb(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    else -> String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024))
}

/** About LightCraft, laid out as the reference's page: the app and its version, then what it's made of, in our words. */
@Composable
fun AboutPage(vm: AppViewModel, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { text = vm.about() }
    val uri = LocalUriHandler.current
    DrawerPage("About LightCraft", onBack) {
        Column(Modifier.padding(horizontal = 28.7.dp).widthIn(max = 468.dp).fillMaxWidth()) {
            Spacer(Modifier.height(23.8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("LIGHTCRAFT ANDROID", color = pageText, fontFamily = Inter, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("v${engineVersion()}", color = pageText, fontFamily = Inter, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Open source under the MIT or Apache-2.0 licence. Your photos stay on this phone: LightCraft sends nothing anywhere.", color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(16.dp))
            Text(text, color = pageNote, fontFamily = Inter, fontSize = 13.sp, lineHeight = 16.sp)
            AboutLink("SOURCE CODE", HELP_URL) { runCatching { uri.openUri(HELP_URL) } }
            AboutLink("THIRD-PARTY LICENCES", "$HELP_URL/blob/main/apps/lightcraft-android/README.md") {
                runCatching { uri.openUri("$HELP_URL/blob/main/apps/lightcraft-android/README.md") }
            }
        }
    }
}

@Composable
private fun AboutLink(heading: String, link: String, onClick: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(heading, color = pageNote, fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Text(
        link.removePrefix("https://"),
        color = Lc.highlight,
        fontFamily = Inter,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp),
    )
}
