package ai.storyteller.lightcraft.ui

import ai.storyteller.lightcraft.LightcraftApplication
import ai.storyteller.lightcraft.core.AlbumInfo
import ai.storyteller.lightcraft.core.Choice
import ai.storyteller.lightcraft.core.CropFrame
import ai.storyteller.lightcraft.core.DevelopControl
import ai.storyteller.lightcraft.core.DevelopState
import ai.storyteller.lightcraft.core.EditHistory
import ai.storyteller.lightcraft.core.FolderSync
import ai.storyteller.lightcraft.core.ImageHistogram
import ai.storyteller.lightcraft.core.ImageSize
import ai.storyteller.lightcraft.core.ImportReport
import ai.storyteller.lightcraft.core.LibraryOverview
import ai.storyteller.lightcraft.core.MaskInfo
import ai.storyteller.lightcraft.core.MaskOverlay
import ai.storyteller.lightcraft.core.MobileSession
import ai.storyteller.lightcraft.core.PhotoFlag
import ai.storyteller.lightcraft.core.PhotoInfo
import ai.storyteller.lightcraft.core.PresetInfo
import ai.storyteller.lightcraft.core.ProfileEntry
import ai.storyteller.lightcraft.core.SpotInfo
import ai.storyteller.lightcraft.core.ToneCurveInfo
import ai.storyteller.lightcraft.core.ViewGeometry
import ai.storyteller.lightcraft.core.engineVersion
import ai.storyteller.lightcraft.core.whiteBalanceModes
import ai.storyteller.lightcraft.core.CloudStatus
import ai.storyteller.lightcraft.engine.CloudConfig
import ai.storyteller.lightcraft.engine.DeviceAccess
import ai.storyteller.lightcraft.engine.DeviceMedia
import ai.storyteller.lightcraft.engine.DevicePhoto
import ai.storyteller.lightcraft.engine.Engine
import ai.storyteller.lightcraft.engine.LibraryFolder
import ai.storyteller.lightcraft.engine.PhotoFiles
import ai.storyteller.lightcraft.engine.toImageBitmap
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.os.StatFs
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A snackbar message, with an optional action. */
class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/**
 * The editor's main tools along the bottom, the reference app's row. Labelled, a tool is as wide as
 * the reference's (its label and 17.5 dp, at least 53.5 dp), so they land on its bounds.
 */
enum class MainTool(val label: String, val icon: Glyph, val labelledWidth: Float = 53.5f) {
    ACTIONS("Actions", MdIcon.AutoFixHigh),
    PRESETS("Presets", LcIcon.ToolPresets),
    CROP("Crop", MdIcon.CropRotate),
    EDIT("Edit", LcIcon.Sliders),
    MASKING("Masking", LcIcon.ToolMasking, 55.9f),
    REMOVE("Remove", LcIcon.ToolRemove, 55.4f),
}

/**
 * Actions' row, where Edit's sections go: one-tap edits. The reference's others (enhance, subject,
 * background) need AI models the phone doesn't have; rating is More › Info and rating, as there.
 */
enum class QuickAction(val label: String, val icon: Glyph, val width: Float = 53.5f) {
    AUTO("Auto", LcIcon.SectionAuto),
    FIX_ANGLE("Fix angle", LcIcon.AngleGrid, 57.4f),
}

/** Edit's sections, the row above the tools when Edit is open. */
enum class EditSection(val label: String, val icon: Glyph, val sections: List<String>) {
    AUTO("Auto", LcIcon.SectionAuto, emptyList()),
    LIGHT("Light", MdIcon.LightMode, listOf("light", "curve")),
    COLOR("Color", MdIcon.Thermostat, listOf("color", "mixer", "bwMix", "grading")),
    EFFECTS("Effects", LcIcon.SectionEffects, listOf("effects", "vignette", "grain")),
    DETAIL("Detail", LcIcon.SectionDetail, listOf("detail")),
    OPTICS("Optics", LcIcon.SectionOptics, listOf("optics")),
    PROFILES("Profiles", MdIcon.AutoAwesomeMotion, listOf("profile")),
}

/** A photo opened from the phone's gallery: which, and whether this visit added its copy to the library. */
class DeviceOrigin(val photo: DevicePhoto, val added: Boolean)

/** The editor, for the photo being edited ([device]: opened from the Device tab). */
@Stable
/** Apply from previous's Basics: the basic adjustments (white balance, tone and presence), as settings groups. */
private val BASIC_GROUPS = listOf("treatment", "profile", "whiteBalance", "light", "color", "effects")

/** The long edge of a choice's preview in the Reset and Apply from previous menus (px). */
private const val CHOICE_EDGE = 180

class EditorState(val photoId: ULong, info: PhotoInfo, val device: DeviceOrigin? = null) {
    var info by mutableStateOf(info)
    var controls by mutableStateOf<List<DevelopControl>>(emptyList())
    var state by mutableStateOf<DevelopState?>(null)

    /** The open main tool (none: just the photo and the tool row). */
    var tool by mutableStateOf<MainTool?>(null)

    /** Actions' open panel (Rate's), if any. */
    var action by mutableStateOf<QuickAction?>(null)

    /** Edit's open section (none: just its row of sections). */
    var section by mutableStateOf<EditSection?>(null)

    /** Profiles is a mode with Cancel and Apply: the profile it started from, to go back to. */
    var profileFrom by mutableStateOf<String?>(null)

    /** A panel's sub-panel: `curve` (Light), `mix` and `grading` (Color). */
    var sub by mutableStateOf<String?>(null)
    var detailTab by mutableStateOf("sharpening")

    /** Light › Curve: the channel shown (`parametric`, `master`, `red`, `green`, `blue`), the curves, the points while a finger moves one. */
    var curveChannel by mutableStateOf("master")
    var curve by mutableStateOf<ToneCurveInfo?>(null)
    var liveCurve by mutableStateOf<List<Pair<Double, Double>>?>(null)
    var effectsTab by mutableStateOf("effects")

    /** The white balance selector is armed: the next tap on the photo picks a neutral spot. */
    var pickingWb by mutableStateOf(false)

    /** Targeted adjustment is on: a vertical drag on the photo moves `curve` (Light › Curve) or the mixer's `hue`, `sat` or `lum` (Color › Mix) there. */
    var targeting by mutableStateOf<String?>(null)

    /** Crop mode: the crop as the engine has it and the panel's tab. */
    var crop by mutableStateOf<CropFrame?>(null)
    var cropTab by mutableStateOf("aspect")

    /**
     * A crop frame waiting for its picture: after a turn, flip or Upright the frame takes the view
     * render asked for at [pendingCropSeq] or later, so the two show together in one frame (an old
     * picture under the new frame showed at the wrong size for a moment).
     */
    var pendingCrop: CropFrame? = null
    var pendingCropSeq = 0L

    /** The social format last picked (`ig.story`…): its name is the pill's while the crop keeps its shape. */
    var cropFormat by mutableStateOf<String?>(null)

    /** Where the history was as a mode (Crop, Profiles) opened: its Cancel takes back every step after it. */
    internal var modeMark: ULong? = null

    /** The tool open before Crop (a mode): Crop's Apply and Cancel go back to it, as the reference's. */
    internal var toolBeforeCrop: MainTool? = null

    /** Guided Upright's guides as the crop view shows them (x0, y0, x1, y1 each, normalized). */
    var guides by mutableStateOf<List<Double>>(emptyList())

    /** Remove: the spots, the one picked, the view ↔ photo map and the brush. */
    var spots by mutableStateOf<List<SpotInfo>>(emptyList())
    var selectedSpot by mutableStateOf<Int?>(null)

    /** The picked spot's menu (a long press on a spot, or a tap on the picked one). */
    var spotMenu by mutableStateOf(false)
    var geometry by mutableStateOf<ViewGeometry?>(null)
    var removeMode by mutableStateOf("remove")
    var brushSize by mutableFloatStateOf(0.025f)
    var brushFeather by mutableFloatStateOf(50f)
    var brushOpacity by mutableFloatStateOf(100f)

    /** Masking: the masks, the one being edited, what its panel shows (`new`, `mask`, or a section: `light`…), the brush. */
    var masks by mutableStateOf<List<MaskInfo>>(emptyList())
    var selectedMask by mutableStateOf<UInt?>(null)

    /** The part of the edited mask its handles and tools edit (an index of its parts; −1: the mask itself). */
    var maskPart by mutableIntStateOf(0)
    var maskSection by mutableStateOf<String?>(null)
    var maskErase by mutableStateOf(false)
    var maskBrushSize by mutableFloatStateOf(0.03f)
    // the reference's brush defaults: fully feathered, half flow
    var maskBrushFeather by mutableFloatStateOf(100f)
    var maskBrushFlow by mutableFloatStateOf(50f)

    /** The mask tinted over the photo (the top bar's toggle). */
    var showMaskOverlay by mutableStateOf(false)

    /** The masking options sheet (the top bar's More while Masking is open). */
    var maskOptions by mutableStateOf(false)

    /** Masking's sheet over the editor: `new` (create a mask) or `list` (the photo's masks), if open. */
    var maskPicker by mutableStateOf<String?>(null)

    /** The colour or luminance range picker, while it's open (its sheet takes the rows' place). */
    var rangePicker by mutableStateOf<RangePicker?>(null)

    /** The pill under the top bar while a value's slider beside the mask part's bar is open ("Refine: +50"). */
    var barPill by mutableStateOf<String?>(null)

    /** The masks' pictures in Masking's column: what each selects, white on black (by mask id). */
    var maskThumbs by mutableStateOf<Map<UInt, ImageBitmap>>(emptyMap())

    /** A luminance range's four lightnesses while the picker's slider moves them (before the engine has them back). */
    var liveStops by mutableStateOf<List<Double>?>(null)

    /** Where the history was as Masking opened: its Cancel takes back every step after it. */
    internal var maskingMark: ULong? = null

    /** Masking's Cancel is asking whether to discard its changes. */
    var discardMasking by mutableStateOf(false)

    /** There are steps since Masking opened (its Cancel would take them back). */
    var maskingEdited by mutableStateOf(false)

    /** Masking is a mode (its Cancel and Apply where the tools were): it has masks, or changes to discard. */
    val maskingMode: Boolean get() = tool == MainTool.MASKING && (masks.isNotEmpty() || maskingEdited)

    /** Values while a finger moves them (before the engine has them back): local sliders, a shape's numbers, a dragged shape. */
    var liveLocal by mutableStateOf<Map<String, Double>>(emptyMap())
    var liveShape by mutableStateOf<Map<String, Double>>(emptyMap())
    var liveShapeJson by mutableStateOf<String?>(null)

    /** The profile browser's sheet over the editor: `options` (its More) or `manage` (Manage Profiles), if open. */
    var profileSheet by mutableStateOf<String?>(null)

    /** The view shows the photo uncropped (the crop tool's): its frame is drawn over it. */
    var showingCropView by mutableStateOf(false)
    var presetTab by mutableStateOf("lightcraft")
    var mixerBand by mutableIntStateOf(0)
    var gradingRange by mutableStateOf("shadows")
    var lastPreset by mutableStateOf<PresetInfo?>(null)
    var presetAmount by mutableFloatStateOf(100f)
    var image by mutableStateOf<ImageBitmap?>(null)

    /**
     * Zoomed in, the part of the photo on screen rendered sharp, drawn over [image] (the reference's
     * foreground layer over its whole-photo one): only while it belongs to the [image] shown.
     */
    var detail by mutableStateOf<ViewDetail?>(null)
    var histogram by mutableStateOf<ImageHistogram?>(null)
    var showingBefore by mutableStateOf(false)

    /** View options' sheet is open over the editor. */
    var viewOptions by mutableStateOf(false)

    /** The photo's whole develop settings (JSON) as the editor opened it: Reset › To Open. */
    var openSettings: String? = null

    /** Info and rating's page is open over the editor (its More menu). */
    var infoPage by mutableStateOf(false)

    /** The preset options sheet (the top bar's More while Presets is open), Manage Presets, and the preset held. */
    var presetOptions by mutableStateOf(false)
    var managePresets by mutableStateOf(false)
    var presetHeld by mutableStateOf<PresetInfo?>(null)

    /** Versions' page is open over the editor (the undo card's). */
    var versionsPage by mutableStateOf(false)

    /** Copy Settings' page of settings groups is open over the editor (its More menu). */
    var copyPicker by mutableStateOf(false)

    /** Organize's album list is open over the editor (its More menu). */
    var organize by mutableStateOf(false)

    /** The photo alone on the screen (a tap on it): no bars, panel or rows, the system's bars hidden. */
    var immersive by mutableStateOf(false)
    var history by mutableStateOf(EditHistory(null, null))
    var exporting by mutableStateOf(false)

    /** A cloud photo's preview or original is coming down: what the pill over the photo says (null: nothing is). */
    var downloading by mutableStateOf<String?>(null)
    var rendering by mutableStateOf(false)
    internal var viewport = IntSize.Zero
    internal var zoom = 1f

    /** How far the zoomed photo is moved from the middle of the view (px). */
    internal var pan = Offset.Zero

    /** The view has the photo's own render (not a stand-in). */
    internal var rendered = false

    /** The control being dragged: an engine interaction is open for it (one undo step). */
    internal var adjusting: String? = null

    /** The crop a straighten gesture started from (rect, angle): every move works from it (issue 8). */
    internal var straightenFrom: Pair<DoubleArray, Double>? = null

    fun control(id: String): DevelopControl? = controls.firstOrNull { it.id == id }
}

/**
 * [image]: the `window` (in pixels of the zoomed frame) of the view as it would look rendered whole
 * at `frame` (the photo fitted to that size), sharp where the whole-photo render [base] is
 * magnified.
 */
class ViewDetail(val image: ImageBitmap, val frame: IntSize, val window: IntRect, val base: ImageBitmap)

private fun IntRect.covers(r: IntRect) = r.left >= left && r.top >= top && r.right <= right && r.bottom <= bottom

/** Rendered images by key, at most [bytes] of them (grid thumbnails, preset and profile looks). */
class BitmapCache(bytes: Int) {
    private val cache = object : LruCache<String, ImageBitmap>(bytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(key: String): ImageBitmap? = cache.get(key)

    suspend fun load(key: String, render: suspend () -> ImageBitmap?): ImageBitmap? = peek(key) ?: render()?.also { cache.put(key, it) }

    fun clear() = cache.evictAll()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val lightcraft = app as LightcraftApplication

    var engine by mutableStateOf<Engine?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var photos by mutableStateOf<List<PhotoInfo>>(emptyList())
        private set
    var importing by mutableStateOf(false)
        private set
    var editor by mutableStateOf<EditorState?>(null)
        private set
    var presets by mutableStateOf<List<PresetInfo>>(emptyList())
        private set
    var profiles by mutableStateOf<List<ProfileEntry>>(emptyList())
        private set
    val wbModes: List<Choice> = runCatching { whiteBalanceModes() }.getOrDefault(emptyList())

    private val prefs = app.getSharedPreferences("library", Application.MODE_PRIVATE)

    /** The cloud library's state (Preferences ▸ Cloud library), as of the last [refreshCloudStatus]; null before it is read. */
    var cloud by mutableStateOf<CloudStatus?>(null)
        private set

    /** What the cloud library is doing for the person ("Connecting…", "Refreshing…"), null when idle. */
    var cloudBusy by mutableStateOf<String?>(null)
        private set

    /** Why the last Connect failed, for the page. */
    var cloudError by mutableStateOf<String?>(null)
        private set

    /** The photos that are the cloud's, and those whose smart preview is on the phone (the others show their thumbnail until opened). */
    var cloudIds by mutableStateOf<Set<ULong>>(emptySet())
        private set
    var cloudPreviews by mutableStateOf<Set<ULong>>(emptySet())
        private set

    /** What the phone shows of each cloud photo (thumbnail and applied sidecar versions, from the library's state): part of its thumbnail's key. */
    private var cloudVersions by mutableStateOf<Map<ULong, String>>(emptyMap())

    /** Cloud photos whose preview was downloaded since the grid last read the versions: part of their thumbnail's key. */
    private var cloudThumbVersion by mutableStateOf<Map<ULong, Int>>(emptyMap())

    /** Why the last automatic refresh failed (null: it didn't); shown on the Cloud library page, not as a toast. */
    var cloudAutoError by mutableStateOf<String?>(null)
        private set

    /** The partners of raw+JPEG pairs, left out of [photos]. */
    private var cloudHidden: Set<ULong> = prefs.getStringSet("cloudHidden", emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toULongOrNull() }
    private var cloudSyncedAt = 0L
    private var cloudPushJob: Job? = null

    /** A cloud library is set up (even when it could not load at launch: the page then shows why). */
    val cloudConfigured: Boolean get() = cloud?.let { it.connected || it.remote.isNotEmpty() } == true

    /** What the grid shows (the title's menu). */
    var view by mutableStateOf(LibraryView.ALL)
        private set

    /** The filter sheet's choices and the search words. */
    var filter by mutableStateOf(LibraryFilter())
        private set

    /** A search is being typed (its sent words are the filter's; the Gallery tab has no search). */
    var searching by mutableStateOf(false)

    /** The sort sheet's key (as the engine names it) and direction. */
    var sortKey by mutableStateOf("captureDate")
        private set
    var newestFirst by mutableStateOf(true)
        private set

    /** View options, kept between launches: a justified or a square grid, and its segments. */
    var justified by mutableStateOf(prefs.getBoolean("justified", true))
        private set
    var segmentBy by mutableStateOf(prefs.getString("segmentBy", "none") ?: "none")
        private set

    /** The profiles starred as favourites (by id), kept between launches: the reference's Favorites group. */
    var favoriteProfiles by mutableStateOf(prefs.getStringSet("favoriteProfiles", null)?.toSet() ?: setOf("lc.color", "lc.mono"))
        private set

    fun toggleFavoriteProfile(id: String) {
        val next = if (id in favoriteProfiles) favoriteProfiles - id else favoriteProfiles + id
        favoriteProfiles = next
        prefs.edit().putStringSet("favoriteProfiles", next).apply()
    }

    /** What the editor shows over the photo (its View options): `none`, `info` (where the photo is, its name and date) or `histogram`, kept between launches. */
    var viewOverlay by mutableStateOf(prefs.getString("viewOverlay", "none") ?: "none")
        private set

    fun showOverlay(overlay: String) {
        viewOverlay = overlay
        prefs.edit().putString("viewOverlay", overlay).apply()
    }

    /** Which profiles the browser shows (its More sheet): `all`, `color` or `bw`, kept between launches. */
    var profileFilter by mutableStateOf(prefs.getString("profileFilter", "all") ?: "all")
        private set

    fun filterProfiles(filter: String) {
        profileFilter = filter
        prefs.edit().putString("profileFilter", filter).apply()
    }

    /** The profile groups Manage Profiles hides from the browser, kept between launches. */
    var hiddenProfileGroups by mutableStateOf(prefs.getStringSet("hiddenProfileGroups", null)?.toSet() ?: emptySet())
        private set

    /** Preset groups hidden by Manage Presets, kept between launches. */
    var hiddenPresetGroups by mutableStateOf(prefs.getStringSet("hiddenPresetGroups", null)?.toSet() ?: emptySet())
        private set

    fun hidePresetGroups(groups: Set<String>) {
        hiddenPresetGroups = groups
        prefs.edit().putStringSet("hiddenPresetGroups", groups).apply()
    }

    fun hideProfileGroups(groups: Set<String>) {
        hiddenProfileGroups = groups
        prefs.edit().putStringSet("hiddenProfileGroups", groups).apply()
    }

    /** How large the grid's photos are: one of [GRID_SIZES] (pinched in or out a step at a time), kept between launches. */
    var gridSize by mutableIntStateOf(prefs.getInt("gridSize", 2).coerceIn(0, GRID_SIZES.lastIndex))
        private set

    fun stepGridSize(by: Int) {
        val next = (gridSize + by).coerceIn(0, GRID_SIZES.lastIndex)
        if (next == gridSize) return
        gridSize = next
        prefs.edit().putInt("gridSize", next).apply()
    }

    /** Ratings, flags and the edited mark on the grid's cells: off, clean cells, as the reference app (it has no switch for them). */
    val showBadges = false

    /** What Save a copy and Share write (the export sheet's choices). */
    var exportChoice by mutableStateOf(loadExportChoice())
        private set

    private fun loadExportChoice(): ExportChoice = ExportChoice.fromPrefs(prefs.getString("export", null))

    /** [ed]'s photo exported small as [choice] asks (its watermark, say), for a preview (null: it failed). */
    suspend fun exportPreview(ed: EditorState, choice: ExportChoice): ImageBitmap? {
        val e = engine ?: return null
        val options = JSONObject(choice.copy(format = "jpeg", dimensions = "custom", customEdge = 1200, quality = 85).json()).toString()
        return runCatching {
            val file = withContext(e.viewRenders) { e.session.export(ed.photoId, options) }
            withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)?.asImageBitmap() }
        }.getOrNull()
    }

    /** The export presets: LightCraft's and yours (`export.presets`): name, built in, and their `app.export` params. */
    suspend fun exportPresets(): List<Triple<String, Boolean, JSONObject>> {
        val e = engine ?: return emptyList()
        return runCatching {
            val a = JSONArray(e.command { execute("export.presets", "{}") })
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map { Triple(it.optString("name"), it.optBoolean("builtin"), it.optJSONObject("params") ?: JSONObject()) }
        }.getOrDefault(emptyList())
    }

    /** Save the export page's settings as preset [name] (one of yours of that name is replaced). */
    fun saveExportPreset(name: String, choice: ExportChoice, done: () -> Unit) {
        val e = engine ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching { e.command { execute("export.savePreset", JSONObject().put("name", name.trim()).put("params", JSONObject(choice.json())).toString()) } }
                .onSuccess {
                    say("Preset “${name.trim()}” saved")
                    done()
                }
                .onFailure { say(it.message) }
        }
    }

    fun deleteExportPreset(name: String, done: () -> Unit) {
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute("export.deletePreset", JSONObject().put("name", name).toString()) } }
                .onSuccess { done() }
                .onFailure { say(it.message) }
        }
    }

    /** A watermark graphic from the phone, copied into the app's files; [done] gets its path. */
    fun stageWatermark(uri: Uri, done: (String) -> Unit) {
        viewModelScope.launch {
            val dir = File(getApplication<Application>().filesDir, "watermarks")
            val file = withContext(Dispatchers.IO) { PhotoFiles.stage(getApplication(), listOf(uri), dir).firstOrNull() }
            if (file == null) say("That picture couldn't be read") else done(file.absolutePath)
        }
    }

    /** Counts of the library's views, the albums and what the filter can pick from. */
    var overview by mutableStateOf<LibraryOverview?>(null)
        private set

    /** The home screen's tab: `device` (the phone's gallery) or `library` (LightCraft's), kept between launches. */
    var homeTab by mutableStateOf(prefs.getString("homeTab", "library") ?: "library")
        private set

    /** What the Device tab may see of the gallery, its photos, and the album (folder) it shows (null: all). */
    var deviceAccess by mutableStateOf(DeviceAccess.NONE)
        private set
    var devicePhotos by mutableStateOf<List<DevicePhoto>>(emptyList())
        private set
    var deviceAlbum by mutableStateOf<String?>(null)
        private set

    /** The Gallery tab's kinds of photo shown (`jpg`: JPEGs, PNGs and the like; `raw`): both at first, and again each launch. */
    var deviceKinds by mutableStateOf(deviceKindsAll)
        private set

    /** The Gallery tab's order by the date taken: oldest first, else newest first (kept between launches). */
    var deviceAscending by mutableStateOf(prefs.getBoolean("deviceAscending", false))
        private set

    /** The Gallery tab's headings: `none`, `year`, `month`, `day` (at first) or `hour` (kept between launches). */
    var deviceSegment by mutableStateOf(prefs.getString("deviceSegment", "day")?.takeIf { u -> segmentUnits.any { it.first == u } } ?: "day")
        private set

    /** How large the Gallery tab's photos are (pinched, as the library's grid), kept between launches. */
    var deviceGridSize by mutableIntStateOf(prefs.getInt("deviceGridSize", 2).coerceIn(0, GRID_SIZES.lastIndex))
        private set

    /** A kind of photo is left out. */
    val deviceFiltered: Boolean get() = deviceKinds != deviceKindsAll

    private val shownDevice = derivedStateOf {
        val inAlbum = deviceAlbum?.let { a -> devicePhotos.filter { it.album == a } } ?: devicePhotos
        val kinds = deviceKinds
        val shown = if (kinds == deviceKindsAll) inAlbum else inAlbum.filter { deviceKind(it) in kinds }
        if (deviceAscending) shown.asReversed() else shown
    }

    /** The Gallery tab's photos: its folder's, of the kinds shown, by the date taken. */
    val shownDevicePhotos: List<DevicePhoto> get() = shownDevice.value

    /** Show or leave out a kind of photo ([deviceKindsAll]); the last one shown stays. */
    fun toggleDeviceKind(kind: String) {
        val next = if (kind in deviceKinds) deviceKinds - kind else deviceKinds + kind
        if (next.isNotEmpty()) deviceKinds = next
    }

    fun clearDeviceFilter() {
        deviceKinds = deviceKindsAll
    }

    fun toggleDeviceOrder() {
        deviceAscending = !deviceAscending
        prefs.edit().putBoolean("deviceAscending", deviceAscending).apply()
    }

    fun setDeviceSegments(unit: String) {
        deviceSegment = unit
        prefs.edit().putString("deviceSegment", unit).apply()
    }

    fun stepDeviceGridSize(by: Int) {
        val next = (deviceGridSize + by).coerceIn(0, GRID_SIZES.lastIndex)
        if (next == deviceGridSize) return
        deviceGridSize = next
        prefs.edit().putInt("deviceGridSize", next).apply()
    }

    /** The gallery's thumbnails. */
    private val deviceThumbs = BitmapCache(32 shl 20)

    /** Select mode (batch edit): the photos picked, by id. */
    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<ULong>>(emptySet())
        private set

    /** Photos (and looks, and sizes) already prepared for the editor. */
    private val prefetched = HashSet<String>()

    /** Grid thumbnails by photo and look (an edit is a new key). */
    val thumbnails = BitmapCache(48 shl 20)

    /** Preset and profile previews of the edited photo. */
    private val looks = BitmapCache(32 shl 20)
    val messages = Channel<UiMessage>(Channel.BUFFERED)

    private data class ViewRequest(val photo: ULong, val width: Int, val height: Int, val draft: Boolean, val before: Boolean, val crop: Boolean, val mask: MaskOverlay?, val seq: Long)

    /** The part of the zoomed view to render sharp over [base] (see [ViewDetail]). */
    private class DetailRequest(val photo: ULong, val frame: IntSize, val window: IntRect, val before: Boolean, val mask: MaskOverlay?, val base: ImageBitmap)

    /** The latest view the editor wants: a busy render loop skips straight to the newest. */
    private val viewRequests = MutableStateFlow<ViewRequest?>(null)
    private var requestSeq = 0L

    /** The latest sharp part a zoomed view wants (null: none). */
    private val detailRequests = MutableStateFlow<DetailRequest?>(null)

    init {
        viewModelScope.launch {
            lightcraft.engine.await()
                .onSuccess { e ->
                    engine = e
                    e.problem?.let { say("The library couldn't be opened, so this is the demo (nothing is saved): $it") }
                    // the order the library was left in (the engine keeps it with the library)
                    runCatching { JSONObject(e.command { execute("library.state", "") }).optJSONObject("sort") }.getOrNull()?.let {
                        newestFirst = !it.optBoolean("ascending", false)
                        sortKey = it.optString("key", "captureDate").takeIf { k -> sortKeys.any { (id, _) -> id == k } } ?: "captureDate"
                    }
                    refreshPhotos()
                    refreshCloudStatus()
                    cloudSync(manual = false)
                    runCatching { e.command { presets() to profiles() } }.onSuccess { (pr, pf) ->
                        presets = pr
                        profiles = pf
                    }
                    launch { renderDetails(e) }
                    renderViews(e)
                }
                .onFailure { loadError = it.message ?: it.toString() }
        }
    }

    private fun say(text: String?, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messages.trySend(UiMessage(text ?: "Something went wrong", actionLabel, action))
    }

    /**
     * [text] with an Undo for the step whose mark is [depth] (`historyDepth()` right after it): it
     * takes back that step, never a later one.
     */
    private fun sayUndoable(text: String, depth: ULong) {
        val e = engine ?: return
        say(text, "Undo") {
            viewModelScope.launch {
                val undone = runCatching { e.command { undoAt(depth) } }.getOrDefault(false)
                if (!undone) say("Can't undo that now: other changes came after it")
                refreshPhotos()
            }
        }
    }

    private suspend fun refreshPhotos() {
        val e = engine ?: return
        runCatching { e.command { Triple(photos(), libraryOverview(), cloudHiddenPhotos() to cloudPhotos()) } }.onSuccess { (all, o, cloudNow) ->
            val (hiddenNow, cloudPhotos) = cloudNow
            // the partners of raw+JPEG pairs are not shown. Once disconnected the session no longer lists them: keep what it last said
            if (cloudPhotos.ids.isNotEmpty()) {
                cloudHidden = hiddenNow.toSet()
                prefs.edit().putStringSet("cloudHidden", cloudHidden.mapTo(HashSet()) { it.toString() }).apply()
            }
            cloudIds = cloudPhotos.ids.toSet()
            cloudPreviews = cloudPhotos.previews.toSet()
            cloudVersions = cloudPhotos.versions
            val p = if (cloudHidden.isEmpty()) all else all.filter { it.id !in cloudHidden }
            photos = p
            overview = o
            // photos that left the view leave the selection
            if (selected.isNotEmpty()) selected = selected.intersect(p.map { it.id }.toSet())
        }
    }

    /** The size [ed]'s photo exports at with [choice], its crop and size applied (null: unknown). */
    suspend fun exportSize(ed: EditorState, choice: ExportChoice): ImageSize? {
        val e = engine ?: return null
        return runCatching { e.command { exportSize(ed.photoId, choice.json()) } }.getOrNull()
    }

    /** A photo by id (an album's cover, say), when the library has it. */
    suspend fun photoInfo(id: ULong): PhotoInfo? {
        val e = engine ?: return null
        return runCatching { e.command { photo(id) } }.getOrNull()
    }

    fun thumbnailKey(p: PhotoInfo) = "${p.id}:${p.settingsHash}" + (cloudVersions[p.id]?.let { ":v$it" } ?: "") + (cloudThumbVersion[p.id]?.let { ":c$it" } ?: "")

    suspend fun thumbnail(photo: PhotoInfo): ImageBitmap? {
        val e = engine ?: return null
        return thumbnails.load(thumbnailKey(photo)) {
            withContext(e.thumbnails) {
                runCatching { e.session.renderThumbnail(photo.id, THUMB_EDGE.toUInt()).toImageBitmap() }.getOrNull()
            }
        }
    }

    fun presetKey(p: PhotoInfo, preset: String) = "preset:${p.id}:${p.settingsHash}:$preset"

    fun profileKey(p: PhotoInfo, profile: String) = "profile:${p.id}:${p.settingsHash}:$profile"

    fun peekLook(key: String): ImageBitmap? = looks.peek(key)

    /** The edited photo with preset [preset] applied, as the preset browser shows it. */
    suspend fun presetPreview(photo: PhotoInfo, preset: String): ImageBitmap? {
        val e = engine ?: return null
        return looks.load(presetKey(photo, preset)) {
            withContext(e.looks) {
                runCatching { e.session.renderPresetThumbnail(photo.id, preset, LOOK_EDGE.toUInt()).toImageBitmap() }.getOrNull()
            }
        }
    }

    /** The edited photo with profile [profile] instead of its own. */
    suspend fun profilePreview(photo: PhotoInfo, profile: String): ImageBitmap? {
        val e = engine ?: return null
        return looks.load(profileKey(photo, profile)) {
            withContext(e.looks) {
                runCatching { e.session.renderProfileThumbnail(photo.id, profile, LOOK_EDGE.toUInt()).toImageBitmap() }.getOrNull()
            }
        }
    }

    // ------------------------------------------------------------------ library

    /** Import picked or shared documents: copied next to the library, then moved into it. */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importFiles { e -> PhotoFiles.stage(getApplication(), uris, e.stagingDir) }
    }

    /**
     * Profiles picked from the phone's files (`.cube` looks, or zips of them): copied into the
     * library's profiles (the engine's `profile.import`), each in its group, then listed.
     */
    fun importProfiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            val dir = File(getApplication<Application>().cacheDir, "profile-import")
            val result = runCatching {
                val files = withContext(Dispatchers.IO) { PhotoFiles.stage(getApplication(), uris, dir) }
                if (files.isEmpty()) error("the selected files couldn't be read")
                try {
                    JSONObject(e.command { execute("profile.import", JSONObject().put("paths", JSONArray(files.map { it.absolutePath })).toString()) })
                } finally {
                    withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                }
            }
            result.onSuccess { r ->
                runCatching { e.command { profiles() } }.onSuccess { profiles = it }
                val done = r.optJSONArray("imported")?.length() ?: 0
                val failed = r.optJSONArray("failed")?.length() ?: 0
                say(
                    when {
                        done == 0 -> "No profiles imported: the files aren't .cube looks"
                        failed > 0 -> "Imported ${plural(done, "profile")}; $failed couldn't be read"
                        else -> "Imported ${plural(done, "profile")}"
                    },
                )
            }.onFailure { say("Import failed: ${it.message}") }
        }
    }

    /** Import Presets: preset files (and zips and folders of them) staged and read in, in their own groups. */
    fun importPresets(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            val dir = File(getApplication<Application>().cacheDir, "preset-import")
            val result = runCatching {
                val files = withContext(Dispatchers.IO) { PhotoFiles.stage(getApplication(), uris, dir) }
                if (files.isEmpty()) error("the selected files couldn't be read")
                try {
                    JSONObject(e.command { execute("preset.import", JSONObject().put("paths", JSONArray(files.map { it.absolutePath })).toString()) })
                } finally {
                    withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                }
            }
            result.onSuccess { r ->
                runCatching { e.command { presets() } }.onSuccess { presets = it }
                val done = r.optJSONArray("imported")?.length() ?: 0
                val failed = r.optJSONArray("failed")?.length() ?: 0
                say(
                    when {
                        done == 0 -> "No presets imported: the files aren't presets"
                        failed > 0 -> "Imported ${plural(done, "preset")}; $failed couldn't be read"
                        else -> "Imported ${plural(done, "preset")}"
                    },
                )
            }.onFailure { say("Import failed: ${it.message}") }
        }
    }

    /** A preset command (`preset.update`, `.rename`, `.move`, `.delete`), then the presets read again. */
    private fun presetCommand(command: String, params: JSONObject, done: String? = null) {
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute(command, params.toString()); presets() } }
                .onSuccess {
                    presets = it
                    done?.let { text -> say(text) }
                }
                .onFailure { say(it.message) }
        }
    }

    fun updatePreset(id: String) = presetCommand("preset.update", JSONObject().put("id", id), "Preset updated")

    fun renamePreset(id: String, name: String) {
        if (name.isNotBlank()) presetCommand("preset.rename", JSONObject().put("id", id).put("name", name.trim()))
    }

    fun movePreset(id: String, group: String) {
        if (group.isNotBlank()) presetCommand("preset.move", JSONObject().put("id", id).put("group", group.trim()))
    }

    fun deletePreset(id: String) = presetCommand("preset.delete", JSONObject().put("id", id), "Preset deleted")

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    /** A photo the camera app has just written to [file] (one of ours): moved into the library. */
    fun importShot(file: File) = importFiles { listOf(file) }

    /** The Gallery tab's photos chosen in select mode (the tab's own choice, by the gallery's ids). */
    var deviceSelected by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** A long press on a gallery photo: select mode, with it chosen. */
    fun startDeviceSelecting(p: DevicePhoto) {
        selecting = true
        deviceSelected = setOf(p.id)
    }

    fun toggleDevice(p: DevicePhoto) {
        deviceSelected = if (p.id in deviceSelected) deviceSelected - p.id else deviceSelected + p.id
    }

    /** Select every gallery photo shown (the select bar's overflow). */
    fun selectAllDevice() {
        selecting = true
        deviceSelected = shownDevicePhotos.map { it.id }.toSet()
    }

    fun selectNoDevice() {
        deviceSelected = emptySet()
    }

    /** A heading tapped while choosing: its photos chosen, or (all of them chosen) not. */
    fun toggleDeviceGroup(photos: List<DevicePhoto>) {
        val ids = photos.map { it.id }.toSet()
        deviceSelected = if (deviceSelected.containsAll(ids)) deviceSelected - ids else deviceSelected + ids
    }

    /** Grid Settings' Select: select mode with nothing chosen yet. */
    fun startDeviceSelectingNone() {
        selecting = true
        deviceSelected = emptySet()
    }

    private fun chosenDevicePhotos() = shownDevicePhotos.filter { it.id in deviceSelected }

    /** Whether Import asks where to (the library, or an album too): only once the library has an album. */
    val importAsksAlbum: Boolean get() = overview?.albums.orEmpty().any { !it.smart && !it.folder }

    /** Select mode's Import on the Gallery tab: the chosen photos into the library (and into [album]). */
    fun importDeviceSelected(album: AlbumInfo? = null) = importDevice(chosenDevicePhotos()) { album?.id }

    /** Import into a new album named [name]: the photos first (and the duplicate question), then the album. */
    fun importDeviceSelectedToNewAlbum(name: String) {
        if (name.isBlank()) return
        importDevice(chosenDevicePhotos()) { e ->
            val made = runCatching { JSONObject(e.command { execute("album.create", JSONObject().put("name", name.trim()).toString()) }).optLong("id", -1L) }.getOrDefault(-1L)
            if (made < 0) {
                say("The album couldn't be made")
                null
            } else {
                made.toULong()
            }
        }
    }

    /** The question Import asks when some chosen photos are in the library already: those photos, and whether all are. */
    class DuplicateQuestion(val photos: List<DevicePhoto>, val all: Boolean, val answer: (Boolean?) -> Unit)

    var duplicateQuestion by mutableStateOf<DuplicateQuestion?>(null)
        private set

    /**
     * Import [photos] from the gallery: each is copied in; any the library has already (the same
     * file) are asked about once for all: import them as photos of their own, skip them (and import
     * the rest), or (when every one is) cancel. Then into the album [album] gives, if any.
     */
    private fun importDevice(photos: List<DevicePhoto>, album: suspend (Engine) -> ULong?) {
        if (photos.isEmpty() || importing) return
        endSelecting()
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            importing = true
            val staged = withContext(Dispatchers.IO) {
                photos.mapNotNull { p -> PhotoFiles.stage(getApplication(), listOf(p.uri), e.stagingDir).firstOrNull()?.let { p to it } }
            }
            // the copies the library has already (by content), by file name (each copy's is its own)
            val same = runCatching {
                val r = JSONObject(e.command { execute("library.importPreview", JSONObject().put("paths", JSONArray(staged.map { it.second.absolutePath })).toString()) })
                val c = r.optJSONArray("candidates") ?: JSONArray()
                (0 until c.length()).mapNotNull { i -> c.optJSONObject(i)?.takeIf { it.has("duplicate") }?.optString("path")?.let { File(it).name } }.toSet()
            }.getOrDefault(emptySet())
            importing = false
            val dups = staged.filter { it.second.name in same }
            val take: Boolean? = if (dups.isEmpty()) {
                false
            } else {
                kotlinx.coroutines.suspendCancellableCoroutine { k -> duplicateQuestion = DuplicateQuestion(dups.map { it.first }, dups.size == staged.size) { a -> if (k.isActive) k.resumeWith(Result.success(a)) } }
            }
            duplicateQuestion = null
            val files = when (take) {
                null -> emptyList()
                true -> staged.map { it.second }
                false -> staged.filter { it.second.name !in same }.map { it.second }
            }
            withContext(Dispatchers.IO) { (staged.map { it.second } - files.toSet()).forEach { it.delete() } }
            if (staged.isEmpty()) say("Import failed: the selected files couldn't be read")
            if (files.isEmpty()) return@launch
            importFiles(album(e), duplicates = take == true) { files }
        }
    }

    /** Select mode's Share on the Gallery tab: the chosen photos as they are, to one share sheet. */
    fun shareDeviceSelected() {
        val uris = chosenDevicePhotos().map { it.uri }
        if (uris.isEmpty()) return
        runCatching { PhotoFiles.shareMany(getApplication(), uris, "image/*") }.onFailure { say(it.message) }
    }

    /** Delete from the gallery without asking first (the question's "Don't ask again"), kept between launches. */
    var deviceDeleteUnasked by mutableStateOf(prefs.getBoolean("deviceDeleteUnasked", false))
        private set

    fun stopAskingBeforeDelete(on: Boolean) {
        deviceDeleteUnasked = on
        prefs.edit().putBoolean("deviceDeleteUnasked", on).apply()
    }

    /**
     * Deleting gallery photos: the photos, cut into the batches the system is asked about (up to
     * [DELETE_BATCH] each; Android 10 asks about each photo it won't let go of), how far it got, and
     * (once over) the outcome.
     */
    class DeviceDeletion(val uris: List<Uri>, val batches: List<List<Uri>>) {
        var batch by mutableIntStateOf(0)
        /** Answers so far (each asks for the next request). */
        var answers by mutableIntStateOf(0)
        /** Android 10: the photo of the batch the system is asking about. */
        var pending: Uri? = null
        var stopped by mutableStateOf(false)
        var finishing = false
        var done by mutableStateOf(false)
        var deleted by mutableIntStateOf(0)
    }

    var deviceDeletion by mutableStateOf<DeviceDeletion?>(null)
        private set

    /** Select mode's Delete on the Gallery tab (after its question, unless that's turned off): the chosen photos, batch by batch. */
    fun startDeviceDelete() {
        val uris = chosenDevicePhotos().map { it.uri }
        if (uris.isEmpty()) return
        deviceDeletion = DeviceDeletion(uris, uris.chunked(DELETE_BATCH))
    }

    /** The system's request for the deletion's next batch (null: nothing to ask; the deletion moves on by itself). */
    fun nextDeleteRequest(): android.content.IntentSender? {
        val d = deviceDeletion ?: return null
        val resolver = getApplication<Application>().contentResolver
        while (!d.finishing) {
            val batch = d.batches.getOrNull(d.batch) ?: return finishDelete(d)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                return runCatching { android.provider.MediaStore.createDeleteRequest(resolver, batch).intentSender }.getOrElse {
                    d.batch++
                    null
                } ?: continue
            }
            // Android 10: one by one, asking the person about each the system won't let go of
            var asked: android.content.IntentSender? = null
            for (uri in batch.dropWhile { d.pending != null && it != d.pending }) {
                try {
                    resolver.delete(uri, null, null)
                } catch (x: android.app.RecoverableSecurityException) {
                    d.pending = uri
                    asked = x.userAction.actionIntent.intentSender
                    break
                } catch (_: Exception) {
                }
            }
            if (asked != null) return asked
            d.pending = null
            d.batch++
        }
        return null
    }

    /** The system's answer about the current batch: on to the next, or (refused) stop there. */
    fun deleteBatchAnswered(ok: Boolean) {
        val d = deviceDeletion ?: return
        d.answers++
        if (!ok) {
            d.stopped = true
            finishDelete(d)
            return
        }
        // Android 10: the photo asked about may go now; the batch goes on from it
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        d.batch++
    }

    private fun finishDelete(d: DeviceDeletion): android.content.IntentSender? {
        if (d.finishing) return null
        d.finishing = true
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            // what's gone is what the gallery no longer has
            d.deleted = withContext(Dispatchers.IO) {
                d.uris.count { uri -> runCatching { resolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count == 0 } ?: true }.getOrDefault(false) }
            }
            d.done = true
            if (d.deleted > 0) endSelecting()
            refreshDevice()
        }
        return null
    }

    fun closeDeviceDeletion() {
        deviceDeletion = null
    }

    /** Open a photo picked with another app (or the system's picker) from the Gallery tab's folders, as a gallery photo. */
    fun openPickedPhoto(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "photo"
        openDevicePhoto(DevicePhoto(-1L, uri, name, System.currentTimeMillis(), 1.0, ""))
    }

    /** Something to tell the person, from outside the view model (the camera couldn't open, say). */
    fun tell(text: String) = say(text)

    private fun importFiles(album: ULong? = null, duplicates: Boolean = false, stage: (Engine) -> List<File>) {
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            importing = true
            val result = runCatching {
                val files = withContext(Dispatchers.IO) { stage(e) }
                if (files.isEmpty()) error("the selected files couldn't be read")
                try {
                    e.command { `import`(files.map { it.absolutePath }, true, duplicates) }
                } finally {
                    // duplicates and failures stay where they were copied: tidy up
                    withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                }
            }
            // into the album too: the new photos and the ones the library already had
            val added = result.getOrNull()?.let { r -> (r.imported + r.existing).distinct() }.orEmpty()
            if (album != null && added.isNotEmpty()) {
                val ids = JSONArray(added.map { it.toLong() })
                runCatching {
                    e.command {
                        execute("library.select", JSONObject().put("ids", ids).toString())
                        execute("album.addPhotos", JSONObject().put("id", album.toLong()).put("ids", ids).toString())
                    }
                }.onFailure { say(it.message) }
            }
            importing = false
            refreshPhotos()
            result.onSuccess { r ->
                val name = album?.let { id -> overview?.albums?.firstOrNull { it.id == id }?.name }
                say(importSummary(r) + (name?.let { " into $it" } ?: ""))
            }.onFailure { say("Import failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ folder library

    /**
     * The folder library (Preferences ▸ Library folder): while [enabled], the photos of [folder] are
     * edited where they are and the edits go to XMP sidecars next to them, so other apps and sync
     * tools see them. [folder] is kept when it is turned off (and the photos stay in the library).
     * [syncedAt] (epoch ms, 0: never) and [result] are of the last synchronization; [writesXmp]
     * says that we turned the library's automatic sidecar writing on, so turning this off turns it
     * off again (and leaves it alone where the person had it on already).
     */
    class FolderLibrary(val enabled: Boolean, val folder: String?, val syncedAt: Long, val result: String?, val writesXmp: Boolean)

    var folderLibrary by mutableStateOf(
        FolderLibrary(
            prefs.getBoolean("folderLibrary", false),
            prefs.getString("folderLibraryPath", null),
            prefs.getLong("folderSyncedAt", 0L),
            prefs.getString("folderSyncResult", null),
            prefs.getBoolean("folderWritesXmp", false),
        ),
    )
        private set

    /** A synchronization of the folder is running. */
    var folderSyncing by mutableStateOf(false)
        private set

    private fun saveFolderLibrary(f: FolderLibrary) {
        folderLibrary = f
        prefs.edit()
            .putBoolean("folderLibrary", f.enabled)
            .putString("folderLibraryPath", f.folder)
            .putLong("folderSyncedAt", f.syncedAt)
            .putString("folderSyncResult", f.result)
            .putBoolean("folderWritesXmp", f.writesXmp)
            .apply()
    }

    /** The folder a tree [uri] from the system's picker is becomes the library folder (and, if the folder library is on, is switched to). */
    fun chooseLibraryFolder(uri: Uri): Boolean {
        val path = LibraryFolder.pathOf(getApplication(), uri)
        if (path == null) {
            say("LightCraft can only use a folder on the phone's storage or an SD card")
            return false
        }
        if (folderLibrary.enabled) startFolderLibrary(path) else saveFolderLibrary(FolderLibrary(false, path, 0L, null, folderLibrary.writesXmp))
        return true
    }

    /** Turn the folder library on (for the chosen folder) or off (the photos stay in the library, nothing on disk is deleted). */
    fun setFolderLibrary(on: Boolean) {
        val f = folderLibrary
        if (!on) return stopFolderLibrary()
        val folder = f.folder ?: return
        if (!f.enabled) startFolderLibrary(folder)
    }

    /**
     * Add the photos of [folder] (and below it) where they are, watch it for new ones and write the
     * edits into sidecars from now on. The sidecars come after the import: it is not an edit, and
     * the photos it adds should not each get one.
     */
    private fun startFolderLibrary(folder: String) {
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            if (!LibraryFolder.allowed()) return@launch say("LightCraft needs All files access to edit photos in place")
            importing = true
            val result = runCatching {
                e.command {
                    execute("library.autoImport", JSONObject().put("folder", folder).put("copy", false).toString())
                    val report = `import`(listOf(folder), false, false)
                    val wasOn = JSONObject(execute("library.xmpPreferences", "")).optBoolean("autoWrite")
                    if (!wasOn) execute("library.xmpPreferences", JSONObject().put("autoWrite", true).toString())
                    report to !wasOn
                }
            }
            importing = false
            refreshPhotos()
            result.onSuccess { (report, turnedOn) ->
                // a switch from one folder to another keeps what the first one turned on
                val summary = importSummary(report)
                saveFolderLibrary(FolderLibrary(true, folder, System.currentTimeMillis(), summary, turnedOn || folderLibrary.writesXmp))
                say(summary)
            }.onFailure { say("Couldn't use the folder: ${it.message}") }
        }
    }

    private fun stopFolderLibrary() {
        val f = folderLibrary
        saveFolderLibrary(FolderLibrary(false, f.folder, f.syncedAt, f.result, false))
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            runCatching {
                e.command {
                    execute("library.autoImport", JSONObject().put("folder", JSONObject.NULL).toString())
                    if (f.writesXmp) execute("library.xmpPreferences", JSONObject().put("autoWrite", false).toString())
                }
            }.onFailure { say(it.message) }
        }
    }

    /**
     * Bring the library up to date with its folder: new photos are added and edits another app or a
     * sync tool wrote to sidecars are read. Run when the app comes back to the front, and from
     * Preferences ([manual]: then it tells the result even when nothing changed).
     */
    fun syncFolder(manual: Boolean = false) {
        val f = folderLibrary
        val folder = f.folder?.takeIf { f.enabled } ?: return
        if (folderSyncing || importing) return
        viewModelScope.launch {
            val e = lightcraft.engine.await().getOrNull() ?: return@launch
            if (!LibraryFolder.allowed()) {
                if (manual) say("LightCraft needs All files access to read the folder")
                return@launch
            }
            folderSyncing = true
            val result = runCatching { e.command { syncFolder(folder) } }
            folderSyncing = false
            result.onSuccess { r ->
                refreshPhotos()
                // the open photo's edits came from the sidecar: show them
                if (editor?.photoId in r.updated) edit(after = ::refreshToolState) {}
                val summary = syncSummary(r)
                saveFolderLibrary(FolderLibrary(true, folder, System.currentTimeMillis(), summary, folderLibrary.writesXmp))
                if (manual || r.added.isNotEmpty() || r.updated.isNotEmpty() || r.failed.isNotEmpty()) say(summary)
            }.onFailure {
                val why = "Couldn't synchronize: ${it.message}"
                saveFolderLibrary(FolderLibrary(true, folder, folderLibrary.syncedAt, why, folderLibrary.writesXmp))
                if (manual) say(why)
            }
        }
    }

    private fun syncSummary(r: FolderSync): String = buildString {
        val parts = listOfNotNull(
            r.added.size.takeIf { it > 0 }?.let { "$it new" },
            r.updated.size.takeIf { it > 0 }?.let { if (it == 1) "1 edit updated" else "$it edits updated" },
        )
        append(if (parts.isEmpty()) "Up to date" else parts.joinToString(", "))
        if (r.failed.isNotEmpty()) append(". Couldn't read ${r.failed.joinToString("; ")}")
    }

    // ------------------------------------------------------------------ cloud library

    suspend fun refreshCloudStatus() {
        val e = engine ?: return
        runCatching { e.command { cloudStatus() } }.onSuccess { cloud = it }
    }

    /** The picked rclone config file copied into the app; its remotes' names. Throws when it can't be read. */
    suspend fun importCloudConfig(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val file = CloudConfig.import(getApplication(), uri)
        CloudConfig.remotes(file.readText())
    }

    /** Connect to the cloud library on [remote] (an rclone remote of the copied config) under [folder], then fetch its photos. */
    fun connectCloud(remote: String, folder: String, device: String) {
        val e = engine ?: return
        if (cloudBusy != null) return
        cloudBusy = "Connecting…"
        cloudError = null
        viewModelScope.launch {
            val config = CloudConfig.file(getApplication()).absolutePath
            val connected = runCatching { withContext(Dispatchers.IO) { e.session.cloudConnect(config, CloudConfig.remotePath(remote, folder), device.trim()) } }
            connected.onFailure { cloudError = it.message ?: it.toString() }
            if (connected.isSuccess) {
                cloudBusy = "Loading the photos…"
                cloudSyncedAt = SystemClock.elapsedRealtime()
                val refreshed = runCatching { withContext(Dispatchers.IO) { e.session.cloudRefresh() } }
                refreshed.onFailure { say("Connected, but the photos couldn't be loaded: ${it.message}") }
                refreshed.onSuccess { cloudRefreshed(it, manual = true) }
                refreshPhotos()
            }
            cloudBusy = null
            refreshCloudStatus()
        }
    }

    /**
     * Forget the connection. The photos stay in the library (and their thumbnails and downloaded previews
     * on the phone), edits not sent yet stay in their sidecars; nothing on the remote changes.
     */
    fun disconnectCloud() {
        val e = engine ?: return
        if (cloudBusy != null) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { e.session.cloudDisconnect() } }.onFailure { say("Couldn't disconnect: ${it.message}") }
            refreshPhotos()
            refreshCloudStatus()
        }
    }

    fun setCloudCacheCap(bytes: Long) {
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { e.session.cloudSetCacheCap(bytes.toULong()) } }.onFailure { say("Couldn't change the cache size: ${it.message}") }
            refreshCloudStatus()
        }
    }

    /** Back in the app: send the edits and fetch what changed, at most once every 15 seconds. */
    fun cloudSyncOnResume() = cloudSync(manual = false)

    /** Refresh now (Preferences): send, fetch and tell what came of it. */
    fun cloudRefreshNow() = cloudSync(manual = true)

    private fun cloudSync(manual: Boolean) {
        val e = engine ?: return
        if (cloudBusy != null || (!manual && !cloudConfigured && cloud != null)) return
        val now = SystemClock.elapsedRealtime()
        if (!manual && cloudSyncedAt != 0L && now - cloudSyncedAt < CLOUD_SYNC_EVERY) return
        cloudSyncedAt = now
        cloudBusy = "Refreshing…"
        viewModelScope.launch {
            // (the status is read first when this is the launch's: nothing is connected, nothing to do)
            if (cloud == null) refreshCloudStatus()
            if (!cloudConfigured) {
                cloudBusy = null
                return@launch
            }
            val pushed = runCatching { withContext(Dispatchers.IO) { e.session.cloudPush() } }
            val refreshed = runCatching { withContext(Dispatchers.IO) { e.session.cloudRefresh() } }
            try {
                refreshed.onSuccess { cloudRefreshed(it, manual) }
            } finally {
                // whatever the refresh did before it stopped (thumbnails, sidecars) is in the library's state: the grid reads it
                refreshPhotos()
            }
            val failure = refreshed.exceptionOrNull() ?: pushed.exceptionOrNull()
            if (manual) {
                if (failure != null) say("Couldn't sync: ${failure.message}")
            } else if (failure != null) {
                Log.w(TAG, "cloud sync failed: ${failure.message}", failure)
            }
            cloudAutoError = if (!manual) refreshed.exceptionOrNull()?.let { it.message ?: it.toString() } else null
            cloudBusy = null
            refreshCloudStatus()
        }
    }

    /** A refresh's changes into the grid (and the open photo). */
    private suspend fun cloudRefreshed(r: ai.storyteller.lightcraft.core.CloudRefresh, manual: Boolean) {
        // (the grid is read by the caller, success or not: its thumbnail keys come from the library's versions)
        // the open photo's edits came from the PC: show them
        if (editor?.photoId in r.applied) edit(after = ::refreshToolState) {}
        if (manual) {
            val parts = listOfNotNull(
                r.added.takeIf { it > 0u }?.let { "$it new" },
                r.updated.takeIf { it > 0u }?.let { "$it updated" },
                r.removed.takeIf { it > 0u }?.let { "$it removed" },
                r.applied.size.takeIf { it > 0 }?.let { "$it edit${if (it == 1) "" else "s"} from the PC" },
            )
            val blocked = if (r.blocked.isEmpty()) "" else ". ${r.blocked.size} PC change${if (r.blocked.size == 1) "" else "s"} wait for your edits to be sent"
            val failed = if (r.failures.isEmpty()) "" else ". ${r.failures.size} couldn't be updated: ${r.failures.first()}"
            say((if (parts.isEmpty()) "Up to date" else parts.joinToString(", ")) + blocked + failed)
        }
    }

    /** After leaving a photo: send the edits, a few seconds later (a swipe through photos sends once). */
    private fun cloudPushSoon() {
        val e = engine ?: return
        if (!cloudConfigured) return
        cloudPushJob?.cancel()
        cloudPushJob = viewModelScope.launch {
            delay(CLOUD_PUSH_DELAY)
            runCatching { withContext(Dispatchers.IO) { e.session.cloudPush() } }
            refreshCloudStatus()
        }
    }

    /** Download the preview of the cloud photo [ed] shows (if it isn't on the phone), then draw the photo from it. */
    private fun openCloudPhoto(ed: EditorState) {
        val e = engine ?: return
        val id = ed.photoId
        val waiting = id !in cloudPreviews
        if (waiting) ed.downloading = "Downloading…"
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { e.session.cloudOpen(id) } }
            ed.downloading = null
            result.onSuccess {
                if (it.downloaded || waiting) {
                    cloudPreviews = cloudPreviews + id
                    cloudThumbVersion = cloudThumbVersion + (id to (cloudThumbVersion[id] ?: 0) + 1)
                    if (editor === ed) requestView()
                }
            }.onFailure {
                // (an old preview that is there already never fails: this is about a download)
                if (editor === ed) say("Couldn't download the preview: ${it.message}. The thumbnail stays; your edits are kept.", "Retry") { if (editor === ed) openCloudPhoto(ed) }
            }
        }
    }

    /** A full-size (or raw, or original-file) export of a cloud photo renders from the original; smaller ones from the preview. */
    private fun needsCloudOriginal(id: ULong, choice: ExportChoice): Boolean =
        id in cloudIds && (choice.format == "original" || choice.format == "dng" || choice.longEdge == 0 || choice.longEdge > CLOUD_PREVIEW_EDGE)

    // ------------------------------------------------------------------ slideshow

    /** The slideshow's photo (an index into [photos]), or null when it isn't running; what it shows. */
    var slideshow by mutableStateOf<Int?>(null)
        private set
    var slideshowImage by mutableStateOf<ImageBitmap?>(null)
        private set
    private var slideSize = IntSize.Zero

    /** The grid's photos full screen, from the first (more › slideshow), or from [photo] (the editor's Start Slideshow). */
    fun startSlideshow(photo: ULong? = null) {
        if (photos.isEmpty()) return
        endSelecting()
        val first = photo?.let { id -> photos.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        slideshowImage = null
        slideshow = first
        showSlide(first)
    }

    fun stopSlideshow() {
        slideshow = null
        slideshowImage = null
    }

    /** The next (+1) or previous (−1) photo, wrapping. */
    fun stepSlideshow(step: Int) {
        val i = slideshow ?: return
        val n = photos.size
        if (n == 0) return stopSlideshow()
        val next = ((i + step) % n + n) % n
        slideshow = next
        showSlide(next)
    }

    fun onSlideshowSize(size: IntSize) {
        if (size == slideSize) return
        slideSize = size
        slideshow?.let { showSlide(it) }
    }

    /** Photo [i]: its grid thumbnail at once, then its render at the screen's size; the next one rendered ahead. */
    private fun showSlide(i: Int) {
        val e = engine ?: return
        val p = photos.getOrNull(i) ?: return
        thumbnails.peek(thumbnailKey(p))?.let { slideshowImage = it }
        val size = slideSize.takeIf { it.width > 0 && it.height > 0 } ?: return
        viewModelScope.launch {
            val img = withContext(e.viewRenders) {
                runCatching { e.session.renderView(p.id, size.width.toUInt(), size.height.toUInt(), false, false, null).toImageBitmap() }.getOrNull()
            }
            if (slideshow == i && img != null) slideshowImage = img
            photos.getOrNull((i + 1) % photos.size)?.let { n ->
                launch(e.prefetch) { runCatching { e.session.prefetchView(n.id, size.width.toUInt(), size.height.toUInt()) } }
            }
        }
    }

    // ------------------------------------------------------------------ the phone's gallery

    fun showHomeTab(tab: String) {
        homeTab = tab
        prefs.edit().putString("homeTab", tab).apply()
        endSelecting()
        if (tab == "device") refreshDevice()
    }

    fun showDeviceAlbum(album: String?) {
        deviceAlbum = album
    }

    /** Read the gallery again: when the tab shows, after the permission changes, back in the app. */
    fun refreshDevice() {
        val app = getApplication<Application>()
        val access = DeviceMedia.access(app)
        deviceAccess = access
        if (access == DeviceAccess.NONE) {
            devicePhotos = emptyList()
            return
        }
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { DeviceMedia.load(app) }.getOrDefault(emptyList()) }
            devicePhotos = list
            if (deviceAlbum != null && list.none { it.album == deviceAlbum }) deviceAlbum = null
        }
    }

    fun peekDeviceThumbnail(p: DevicePhoto): ImageBitmap? = deviceThumbs.peek("${p.id}")

    suspend fun deviceThumbnail(p: DevicePhoto): ImageBitmap? =
        deviceThumbs.load("${p.id}") { withContext(Dispatchers.IO) { DeviceMedia.thumbnail(getApplication(), p, THUMB_EDGE)?.asImageBitmap() } }

    /**
     * Open a photo of the phone's gallery in the editor. LightCraft edits what's in its library, so
     * a copy goes in first (opened again, the same copy is found); left untouched, that copy is
     * taken out again, as the reference app keeps a gallery photo only once it's edited.
     */
    fun openDevicePhoto(p: DevicePhoto) {
        val e = engine ?: return
        if (importing) return
        viewModelScope.launch {
            importing = true
            val result = runCatching {
                val files = withContext(Dispatchers.IO) { PhotoFiles.stage(getApplication(), listOf(p.uri), e.stagingDir) }
                if (files.isEmpty()) error("it couldn't be read")
                try {
                    e.command { `import`(files.map { it.absolutePath }, true, false) }
                } finally {
                    withContext(Dispatchers.IO) { files.forEach { it.delete() } }
                }
            }
            importing = false
            val report = result.getOrElse {
                say("Can't open ${p.name}: ${it.message}")
                return@launch
            }
            val added = report.imported.firstOrNull()
            val id = added ?: report.existing.firstOrNull()
            if (id == null) {
                say("Can't open ${p.name}: ${report.failed.firstOrNull() ?: "LightCraft can't read it"}")
                return@launch
            }
            refreshPhotos()
            val info = runCatching { e.command { photo(id) } }.getOrNull() ?: return@launch
            openEditor(info, DeviceOrigin(p, added != null))
        }
    }

    /**
     * A photo another app opened or edited with LightCraft: into the editor as a gallery photo is (a
     * copy goes into the library, taken out again if it's left untouched), once the engine is up.
     */
    fun openSharedPhoto(uri: Uri) {
        viewModelScope.launch {
            lightcraft.engine.await().getOrNull() ?: return@launch
            // the view model takes the engine a moment after it's up
            repeat(100) { if (engine == null) kotlinx.coroutines.delay(50) }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "photo"
            showHomeTab("library")
            openDevicePhoto(DevicePhoto(-1L, uri, name, System.currentTimeMillis(), 1.0, ""))
        }
    }

    /** Leaving [ed]: a gallery photo's copy that this visit added and that wasn't touched goes again. */
    private suspend fun discardUntouched(ed: EditorState) {
        val origin = ed.device ?: return
        if (!origin.added) return
        val e = engine ?: return
        val info = runCatching { e.command { photo(ed.photoId) } }.getOrNull() ?: return
        if (info.edited || info.rating > 0u || info.flag != PhotoFlag.UNFLAGGED) return
        runCatching { e.command { discardPhoto(ed.photoId) } }
        refreshPhotos()
    }

    /** Show [v] (a view of the library or an album); the filter stays. */
    fun showView(v: LibraryView, afterLoad: (() -> Unit)? = null) {
        val e = engine ?: return
        view = v
        endSelecting()
        val source = JSONObject().put(
            "kind",
            when (v.kind) {
                "recentlyAdded" -> "recentlyAdded"
                "deleted" -> "recentlyDeleted"
                "album" -> "album"
                else -> "all"
            },
        )
        v.albumId?.let { source.put("id", it.toLong()) }
        viewModelScope.launch {
            runCatching { e.command { execute("library.source", source.toString()) } }.onFailure { say(it.message) }
            applyFilter()
            afterLoad?.invoke()
        }
    }

    fun updateFilter(f: LibraryFilter) {
        filter = f
        viewModelScope.launch { applyFilter() }
    }

    /** The filter sheet's choices, the search words and the view's own condition (edited or not), as one engine filter. */
    private suspend fun applyFilter() {
        val e = engine ?: return
        val f = filter
        val json = JSONObject()
        if (f.rating > 0) json.put("rating", f.rating).put("ratingOp", f.ratingOp)
        f.flag?.let { json.put("flag", it) }
        f.kind?.let { json.put("kind", it) }
        f.camera?.let { json.put("camera", it) }
        f.person?.let { json.put("person", it) }
        f.place?.let { json.put("place", it) }
        f.keyword?.let { json.put("keyword", it) }
        if (f.text.isNotBlank()) json.put("text", f.text.trim())
        val edited = f.edited ?: when (view.kind) {
            "edited" -> true
            "unedited" -> false
            else -> null
        }
        edited?.let { json.put("edited", it) }
        runCatching {
            e.command {
                execute("library.clearFilter", "{}")
                if (json.length() > 0) execute("library.filter", json.toString())
            }
        }.onFailure { say(it.message) }
        refreshPhotos()
    }

    /** Sort by [key] (the sort sheet's keys), keeping the direction. */
    fun sortBy(key: String) {
        sortKey = key
        applySort()
    }

    /** The sort sheet's Reverse order. */
    fun reverseSort() {
        newestFirst = !newestFirst
        applySort()
    }

    private fun applySort() {
        val e = engine ?: return
        val params = JSONObject().put("key", sortKey).put("ascending", !newestFirst).put("group", "none").toString()
        viewModelScope.launch {
            runCatching { e.command { execute("library.sort", params) } }.onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    fun setJustifiedGrid(on: Boolean) {
        justified = on
        prefs.edit().putBoolean("justified", on).apply()
    }

    /** `none`, `year`, `month`, `day` or `hour`. */
    fun setSegments(unit: String) {
        segmentBy = unit
        prefs.edit().putString("segmentBy", unit).apply()
    }


    /** A new album named [name], with the selected photos in it when selecting. */
    fun createAlbum(name: String) {
        val e = engine ?: return
        if (name.isBlank()) return
        val ids = selected.toList()
        viewModelScope.launch {
            runCatching {
                e.command {
                    val made = JSONObject(execute("album.create", JSONObject().put("name", name.trim()).toString()))
                    val id = made.optLong("id", -1L)
                    if (ids.isNotEmpty() && id >= 0) {
                        val list = JSONArray(ids.map { it.toLong() })
                        execute("library.select", JSONObject().put("ids", list).toString())
                        execute("album.addPhotos", JSONObject().put("id", id).put("ids", list).toString())
                    }
                }
            }.onSuccess {
                // made from the selection: done with it, as when adding to an album
                if (ids.isNotEmpty()) endSelecting()
                say("Album “${name.trim()}” created")
            }.onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** Rename album [a]; the title follows when it's the album on show. */
    fun renameAlbum(a: AlbumInfo, name: String) {
        val e = engine ?: return
        val n = name.trim()
        if (n.isEmpty() || n == a.name) return
        viewModelScope.launch {
            runCatching { e.command { execute("album.rename", JSONObject().put("id", a.id.toLong()).put("name", n).toString()) } }
                .onSuccess { if (view.albumId == a.id) view = LibraryView.album(a.id, n) }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /**
     * Delete album [a] (a folder: with everything in it): the photos stay in the library, and Undo
     * brings it all back. When the album on show goes, the view goes to all photos.
     */
    fun deleteAlbum(a: AlbumInfo) {
        val e = engine ?: return
        val gone = albumTree(a.id)
        viewModelScope.launch {
            runCatching {
                e.command {
                    execute("album.delete", JSONObject().put("id", a.id.toLong()).put("contents", a.folder).toString())
                    historyDepth()
                }
            }
                .onSuccess { depth ->
                    if (view.albumId in gone) showView(LibraryView.ALL)
                    sayUndoable("Deleted “${a.name}”", depth)
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** The open photo's metadata for Info and rating (null until read). */
    var photoMeta by mutableStateOf<PhotoMeta?>(null)
        private set

    /** The library's import defaults (`library.preferences ▸ import`): a raw preset, a copyright; null until read. */
    var importDefaults by mutableStateOf<JSONObject?>(null)
        private set

    fun loadImportDefaults() = importPreferences(JSONObject())

    /** Change the import defaults: [import] holds what changes (`rawPreset`: an id or `default`, `copyright`). */
    fun changeImportDefaults(import: JSONObject) = importPreferences(JSONObject().put("import", import))

    private fun importPreferences(params: JSONObject) {
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { JSONObject(e.command { execute("library.preferences", params.toString()) }) }
                .onSuccess { importDefaults = it.optJSONObject("import") ?: JSONObject() }
                .onFailure { say(it.message) }
        }
    }

    /** The photo [photoMeta] is of. */
    var photoMetaOf by mutableStateOf<ULong?>(null)
        private set

    /** Which of File Info's five sets of lines shows (a tap on it goes to the next), kept between launches. */
    var fileInfoVariant by mutableStateOf(prefs.getInt("fileInfoVariant", 0).coerceIn(0, 4))
        private set

    fun nextFileInfo() {
        fileInfoVariant = (fileInfoVariant + 1) % 5
        prefs.edit().putInt("fileInfoVariant", fileInfoVariant).apply()
    }

    fun loadPhotoMeta() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute("photo.inspect", JSONObject().put("id", ed.photoId.toLong()).toString()) } }
                .onSuccess { json ->
                    if (editor === ed) {
                        photoMeta = parsePhotoMeta(json)
                        photoMetaOf = ed.photoId
                    }
                }
                .onFailure { say(it.message) }
        }
    }

    /** Info and rating's metadata: the open photo's [field] (`title`, `caption` or `copyright`) set to [value]. */
    fun setPhotoMeta(field: String, value: String) = writePhotoMeta(JSONObject().put(field, value.trim()))

    /** The open photo's keywords replaced by [keywords]. */
    fun setPhotoKeywords(keywords: List<String>) = writePhotoMeta(JSONObject().put("keywords", JSONArray(keywords)))

    private fun writePhotoMeta(params: JSONObject) {
        val ed = editor ?: return
        val e = engine ?: return
        params.put("ids", JSONArray(listOf(ed.photoId.toLong())))
        viewModelScope.launch {
            runCatching { e.command { execute("photo.setMeta", params.toString()) } }
                .onSuccess { loadPhotoMeta() }
                .onFailure { say(it.message) }
        }
    }

    /** Organize: the open photo added to [album] (one undo step). */
    fun addOpenPhotoToAlbum(album: AlbumInfo) {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching {
                e.command {
                    execute("album.addPhotos", JSONObject().put("id", album.id.toLong()).put("ids", JSONArray(listOf(ed.photoId.toLong()))).toString())
                    history()
                }
            }
                .onSuccess {
                    ed.history = it
                    say("Added to ${album.name}")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** Organize's Move to album: the open photo out of the album on show and into [album] (one step). */
    fun moveOpenPhotoToAlbum(album: AlbumInfo) {
        val ed = editor ?: return
        val e = engine ?: return
        val from = view.albumId ?: return addOpenPhotoToAlbum(album)
        viewModelScope.launch {
            runCatching {
                e.command {
                    val ids = JSONArray(listOf(ed.photoId.toLong()))
                    beginAdjustment("Move to Album")
                    try {
                        execute("album.addPhotos", JSONObject().put("id", album.id.toLong()).put("ids", ids).toString())
                        execute("album.removePhotos", JSONObject().put("id", from.toLong()).put("ids", ids).toString())
                    } finally {
                        endAdjustment()
                    }
                    history()
                }
            }
                .onSuccess {
                    ed.history = it
                    say("Moved to ${album.name}")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** Album or folder [id] and everything inside it. */
    fun albumTree(id: ULong): Set<ULong> {
        val all = overview?.albums.orEmpty()
        val tree = linkedSetOf(id)
        var i = 0
        while (i < tree.size) {
            val parent = tree.elementAt(i++)
            all.filter { it.parent == parent }.forEach { tree += it.id }
        }
        return tree
    }

    /** The photo in the editor becomes the cover of the album on show. */
    fun useAsAlbumCover() {
        val ed = editor ?: return
        val e = engine ?: return
        val album = view.albumId ?: return
        viewModelScope.launch {
            runCatching {
                e.command {
                    execute("album.setCover", JSONObject().put("id", album.toLong()).put("photo", ed.photoId.toLong()).toString())
                    history()
                }
            }
                .onSuccess {
                    ed.history = it
                    say("Album cover set")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    // select mode

    fun startSelecting(first: ULong? = null) {
        selecting = true
        selected = setOfNotNull(first)
    }

    fun endSelecting() {
        selecting = false
        selected = emptySet()
        deviceSelected = emptySet()
        addTarget = null
    }

    /** The album or folder whose menu is open (a long press in the albums), and the dialog it led to. */
    var albumMenu by mutableStateOf<AlbumInfo?>(null)
    var albumDialog by mutableStateOf<AlbumDialog?>(null)

    /** An album's Add photos: picking from All photos, then ADD puts them in this album. */
    var addTarget by mutableStateOf<AlbumInfo?>(null)
        private set

    fun startAddingTo(album: AlbumInfo) {
        showView(LibraryView.ALL)
        selecting = true
        selected = emptySet()
        addTarget = album
    }

    /** ADD: the chosen photos into the album being added to, which then shows. */
    fun addChosenToTarget() {
        val album = addTarget ?: return
        val n = selected.size
        onSelected("album.addPhotos", JSONObject().put("id", album.id.toLong())) {
            say(if (n == 1) "Added 1 photo to ${album.name}" else "Added $n photos to ${album.name}")
            showView(LibraryView.album(album.id, album.name))
        }
    }

    /** How the albums are listed (`time`: as made, `title`, `count`) and whether upward, kept between launches. */
    var albumSort by mutableStateOf(prefs.getString("albumSort", "title") ?: "title")
        private set
    var albumSortUp by mutableStateOf(prefs.getBoolean("albumSortUp", true))
        private set

    /** SORT BY: [by] the albums' order; chosen again, its direction turns. */
    fun sortAlbums(by: String) {
        albumSortUp = if (by == albumSort) !albumSortUp else by == "title"
        albumSort = by
        prefs.edit().putString("albumSort", by).putBoolean("albumSortUp", albumSortUp).apply()
    }

    /** A new album or ([folder]) folder named [name], in folder [parent] (none: the top level). */
    fun createAlbumIn(name: String, parent: ULong?, folder: Boolean) {
        val e = engine ?: return
        if (name.isBlank()) return
        val params = JSONObject().put("name", name.trim()).put("folder", folder)
        parent?.let { params.put("parent", it.toLong()) }
        viewModelScope.launch {
            runCatching { e.command { execute("album.create", params.toString()) } }
                .onSuccess { say(if (folder) "Folder “${name.trim()}” created" else "Album “${name.trim()}” created") }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** Move to: album or folder [a] into folder [parent] (none: the top level). */
    fun moveAlbum(a: AlbumInfo, parent: ULong?) {
        val e = engine ?: return
        val params = JSONObject().put("id", a.id.toLong())
        parent?.let { params.put("parent", it.toLong()) }
        viewModelScope.launch {
            runCatching { e.command { execute("album.move", params.toString()) } }.onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /** An album's Slideshow: the album shows, then its photos play. */
    fun albumSlideshow(a: AlbumInfo) = showView(LibraryView.album(a.id, a.name)) { startSlideshow() }

    fun toggleSelected(id: ULong) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll() {
        selected = if (selected.size == photos.size) emptySet() else photos.map { it.id }.toSet()
    }

    /** Select mode's Select None: nothing chosen, still selecting. */
    fun selectNone() {
        selected = emptySet()
    }

    /**
     * Run [command] with the selected photos' ids added to [params], then leave select mode if [done];
     * [after] gets the step's mark (for an Undo, see [sayUndoable]).
     */
    private fun onSelected(command: String, params: JSONObject = JSONObject(), done: Boolean = true, after: ((ULong) -> Unit)? = null) {
        val e = engine ?: return
        if (selected.isEmpty()) return
        val ids = JSONArray(selected.map { it.toLong() })
        params.put("ids", ids)
        viewModelScope.launch {
            runCatching {
                e.command {
                    // the engine's selection too: its photo commands need one
                    execute("library.select", JSONObject().put("ids", ids).toString())
                    execute(command, params.toString())
                    historyDepth()
                }
            }
                .onSuccess { depth ->
                    if (done) endSelecting()
                    after?.invoke(depth)
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    fun rateSelected(stars: Int) = onSelected("photo.rate", JSONObject().put("rating", stars), done = false)

    /** Select mode's Share is exporting (its button waits). */
    var sharingSelected by mutableStateOf(false)
        private set

    /** Select mode's Share: the selected photos exported as the last export was, to one share sheet. */
    fun shareSelected() {
        val e = engine ?: return
        val ids = selected.toList()
        if (ids.isEmpty() || sharingSelected) return
        sharingSelected = true
        val choice = exportChoice
        val options = choice.json()
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = runCatching {
                withContext(Dispatchers.IO) { ids.map { id -> if (needsCloudOriginal(id, choice)) e.session.cloudDownloadOriginal(id); e.session.export(id, options).let { f -> PhotoFiles.shareable(app, f) to f.mimeType } } }
            }
            sharingSelected = false
            result
                .onSuccess { files -> PhotoFiles.shareMany(app, files.map { it.first }, files.map { it.second }.distinct().singleOrNull() ?: "image/*") }
                .onFailure { say("Export failed: ${it.message}") }
        }
    }

    /** Select mode's Move to: the selected photos out of the album on show and into [album]. */
    fun moveSelectedToAlbum(album: AlbumInfo) {
        val from = view.albumId ?: return addSelectedToAlbum(album)
        val n = selected.size
        onSelected("album.addPhotos", JSONObject().put("id", album.id.toLong()), done = false) {
            onSelected("album.removePhotos", JSONObject().put("id", from.toLong())) { say(if (n == 1) "Moved 1 photo to ${album.name}" else "Moved $n photos to ${album.name}") }
        }
    }

    /** Select mode's Set As Cover: the one selected photo becomes the album's cover. */
    fun setSelectedAsCover() {
        val album = view.albumId ?: return
        val photo = selected.singleOrNull() ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute("album.setCover", JSONObject().put("id", album.toLong()).put("photo", photo.toLong()).toString()) } }
                .onSuccess {
                    endSelecting()
                    say("Album cover set")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    /**
     * Select mode's Edit Info: on every selected photo, the title, caption and copyright given (empty
     * clears them), the [rating] and the [flag] (`pick`, `reject`, `none`); null leaves one as it is.
     */
    fun editSelectedInfo(meta: Map<String, String>, rating: Int?, flag: String?) {
        val e = engine ?: return
        val ids = JSONArray(selected.map { it.toLong() })
        if (selected.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                e.command {
                    execute("library.select", JSONObject().put("ids", ids).toString())
                    if (meta.isNotEmpty()) execute("photo.setMeta", JSONObject(meta).put("ids", ids).toString())
                    rating?.let { execute("photo.rate", JSONObject().put("ids", ids).put("rating", it).toString()) }
                    flag?.let { execute("photo.flag", JSONObject().put("ids", ids).put("flag", it).toString()) }
                }
            }
                .onSuccess {
                    endSelecting()
                    say("Info updated")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    fun flagSelected(flag: String) = onSelected("photo.flag", JSONObject().put("flag", flag), done = false)

    fun pasteToSelected() = onSelected("develop.paste") { say("Settings pasted") }

    fun addSelectedToAlbum(album: AlbumInfo) =
        onSelected("album.addPhotos", JSONObject().put("id", album.id.toLong())) { say("Added to ${album.name}") }

    /** Move the selected photos to Recently Deleted (Undo brings them back). */
    fun deleteSelected() {
        val n = selected.size
        onSelected("photo.delete") { depth -> sayUndoable(if (n == 1) "Removed 1 photo" else "Removed $n photos", depth) }
    }

    /** An album of your own is on show (not a smart one, whose photos follow its rules): Delete asks there. */
    fun inPlainAlbum(): Boolean = view.albumId?.let { id -> overview?.albums?.find { it.id == id } }?.let { !it.smart } == true

    /** In an album: take the selected photos out of it (they stay in the library; Undo puts them back). */
    fun removeSelectedFromAlbum() {
        val album = view.albumId ?: return
        val name = view.name
        onSelected("album.removePhotos", JSONObject().put("id", album.toLong())) { depth -> sayUndoable("Removed from $name", depth) }
    }

    fun restoreSelected() = onSelected("photo.restore") { say("Restored") }

    /** Deleted's Restore All. */
    fun restoreAll() {
        selected = photos.map { it.id }.toSet()
        restoreSelected()
    }

    /** Deleted's Permanently Delete All (asked first). */
    fun deleteAllPermanently() {
        selected = photos.map { it.id }.toSet()
        deleteSelectedPermanently()
    }

    /** How the slideshow moves from photo to photo (`none`, `fade`, `wipe`, `flip`) and how long each stays (s), kept between launches. */
    var slideTransition by mutableStateOf(prefs.getString("slideTransition", "fade") ?: "fade")
        private set
    var slideSeconds by mutableStateOf(prefs.getInt("slideSeconds", 4))
        private set

    fun setSlideshow(transition: String = slideTransition, seconds: Int = slideSeconds) {
        slideTransition = transition
        slideSeconds = seconds.coerceIn(1, 10)
        prefs.edit().putString("slideTransition", slideTransition).putInt("slideSeconds", slideSeconds).apply()
    }

    /** Deleted's Delete: the photos go for good, the library's copies of them too (nothing on the phone takes it back). */
    fun deleteSelectedPermanently() {
        val e = engine ?: return
        val ids = selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { e.command { deletePermanently(ids) } }
                .onSuccess {
                    endSelecting()
                    say("Deleted permanently")
                }
                .onFailure { say(it.message) }
            refreshPhotos()
        }
    }

    private fun importSummary(r: ImportReport): String = buildString {
        val n = r.imported.size
        append(if (n == 1) "Imported 1 photo" else "Imported $n photos")
        if (r.duplicates > 0u) append(", ${r.duplicates} already in the library")
        if (r.failed.isNotEmpty()) append(". Couldn't import ${r.failed.joinToString("; ")}")
    }

    /** Whether the engine may render on the GPU (null while it loads). */
    suspend fun gpuEnabled(): Boolean? {
        val e = engine ?: return null
        return runCatching { JSONObject(e.command { execute("app.gpu", "") }).optBoolean("enabled", true) }.getOrNull()
    }

    fun setGpu(on: Boolean) {
        val e = engine ?: return
        viewModelScope.launch { runCatching { e.command { execute("app.gpu", JSONObject().put("enabled", on).toString()) } }.onFailure { say(it.message) } }
    }

    /** The phone's space and what the library takes, for Local storage. */
    suspend fun storageInfo(): StorageInfo = withContext(Dispatchers.IO) {
        val library = engine?.libraryDir
        val stat = runCatching { StatFs(getApplication<Application>().filesDir.path) }.getOrNull()
        StorageInfo(
            deviceTotal = stat?.totalBytes ?: 0L,
            deviceFree = stat?.availableBytes ?: 0L,
            libraryBytes = library?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L,
            photos = photos.size,
            libraryPath = library?.absolutePath ?: "The demo library (in memory)",
        )
    }

    /** Engine version, GPU, memory and licences, for About. */
    suspend fun about(): String {
        val e = engine ?: return loadError ?: "The engine is still loading."
        val gpu = runCatching { JSONObject(e.command { execute("app.gpu", "") }) }.getOrNull()
        val memory = runCatching { JSONObject(e.command { execute("library.memory", "") }) }.getOrNull()
        return buildString {
            appendLine("LightCraft ${engineVersion()}, the pure-Rust engine of the desktop app, running on this phone.")
            appendLine()
            if (gpu != null) {
                val adapter = gpu.optString("adapter").takeIf { it.isNotBlank() && it != "null" }
                val reason = gpu.optString("reason").takeIf { it.isNotBlank() && it != "null" }
                appendLine(if (gpu.optBoolean("available")) "GPU rendering: ${adapter ?: "on"}" else "GPU rendering: off (${reason ?: "no GPU"})")
            }
            memory?.let {
                val used = it.optLong("engineBytes", it.optLong("engine_bytes"))
                appendLine("Memory: ${used shr 20} MB cached of a ${it.optLong("budget") shr 20} MB budget")
            }
            appendLine("${photos.size} photos in the library")
            e.problem?.let { appendLine("Library problem: $it") }
            appendLine()
            append("Inter font: SIL Open Font License 1.1. UniFFI: MPL-2.0. JNA: Apache-2.0.")
        }.trim()
    }

    // ------------------------------------------------------------------ editor

    fun openEditor(p: PhotoInfo, device: DeviceOrigin? = null) {
        val e = engine ?: return
        val ed = EditorState(p.id, p, device)
        // stepping from photo to photo: same view, same tool and section
        editor?.let {
            if (it.photoId != p.id) {
                viewModelScope.launch { discardUntouched(it) }
                cloudPushSoon()
            }
            ed.viewport = it.viewport
            ed.tool = it.tool.takeIf { t -> t != MainTool.CROP }
            ed.section = it.section.takeIf { s -> s != EditSection.PROFILES }
            ed.effectsTab = it.effectsTab
            ed.detailTab = it.detailTab
            ed.presetTab = it.presetTab
            ed.cropTab = it.cropTab
        }
        ed.image = thumbnails.peek(thumbnailKey(p)) // a stand-in until the view is rendered
        editor = ed
        // what the desktop loupe shows meanwhile: the last view render, a raw's embedded JPEG or a thumbnail
        viewModelScope.launch {
            val metrics = getApplication<Application>().resources.displayMetrics
            val edge = max(metrics.widthPixels, metrics.heightPixels).coerceAtMost(2560).toUInt()
            val quick = withContext(e.looks) { runCatching { e.session.renderQuickView(p.id, edge) }.getOrNull()?.let { it to it.toImageBitmap() } }
            if (quick != null && editor === ed && !ed.rendered) {
                ed.image = quick.second
                ed.histogram = quick.first.histogram
            }
        }
        if (p.id in cloudIds) openCloudPhoto(ed)
        viewModelScope.launch {
            runCatching {
                e.command {
                    openPhoto(p.id)
                    // its settings as opened, for Reset › To Open
                    ed.openSettings = runCatching { JSONObject(execute("photo.inspect", JSONObject().put("id", p.id.toLong()).toString())).optJSONObject("develop")?.toString() }.getOrNull()
                    Snapshot(developControls(), developState(), history(), photo(p.id))
                }
            }
                .onSuccess { ed.update(it) }
                .onFailure { say("Can't open ${p.fileName}: ${it.message}") }
            requestView()
        }
    }

    /** What an edit can change, read back from the engine in one go. */
    private class Snapshot(val controls: List<DevelopControl>, val state: DevelopState?, val history: EditHistory, val info: PhotoInfo?)

    private fun MobileSession.snapshot(id: ULong) = Snapshot(developControls(), developState(), history(), photo(id))

    private fun EditorState.update(s: Snapshot) {
        controls = s.controls
        state = s.state
        history = s.history
        s.info?.let { info = it }
    }

    /** Back: out of Profiles, a range picker or Masking (as Cancel), out of a sub-panel, off the white balance selector, then out of the editor. */
    fun back() {
        val ed = editor ?: return
        when {
            ed.immersive -> ed.immersive = false
            ed.tool == MainTool.CROP -> cancelCrop()
            ed.rangePicker != null -> cancelRangePicker()
            ed.maskingMode -> cancelMasking()
            ed.section == EditSection.PROFILES && ed.tool == MainTool.EDIT -> cancelProfiles()
            ed.targeting != null -> ed.targeting = null
            ed.sub != null -> ed.sub = null
            ed.pickingWb -> ed.pickingWb = false
            else -> closeEditor()
        }
    }

    fun closeEditor() {
        val ed = editor ?: return
        editor = null
        val e = engine ?: return
        viewModelScope.launch {
            runCatching {
                e.command {
                    // the history's floor and the view first: nothing after may leave them set
                    this.closeEditor()
                    releaseView()
                    if (ed.adjusting != null) endAdjustment()
                }
            }
            discardUntouched(ed)
            refreshPhotos()
        }
        cloudPushSoon()
    }

    fun onViewport(size: IntSize) {
        val ed = editor ?: return
        if (size == ed.viewport) return
        ed.viewport = size
        requestView()
    }

    /**
     * The photo settled at [zoom], moved by [pan] (a pinch, a drag or a double-tap ended): zoomed in,
     * the part on screen is rendered sharp over the whole photo (the reference's two layers), so a
     * zoomed view costs what the screen shows, whatever the zoom.
     */
    fun onViewMoved(zoom: Float, pan: Offset) {
        val ed = editor ?: return
        ed.zoom = zoom.coerceAtLeast(1f)
        ed.pan = pan
        requestDetail()
    }

    fun showBefore(before: Boolean) {
        val ed = editor ?: return
        if (ed.showingBefore == before) return
        ed.showingBefore = before
        requestView()
    }

    private fun requestView(draft: Boolean = false) {
        val ed = editor ?: return
        val vp = ed.viewport
        if (vp.width <= 0 || vp.height <= 0) return
        // the whole photo at the view's size (zoomed in, [requestDetail] adds the part on screen);
        // drafts (slider drags) at 0.6 of it, like the desktop loupe
        val scale = if (draft) 0.6f else 1f
        val w = (vp.width * scale).roundToInt().coerceAtLeast(1)
        val h = (vp.height * scale).roundToInt().coerceAtLeast(1)
        val mask = viewMask(ed)
        // Crop's Aspect shows the whole photo under its frame; its Geometry, the photo as it comes out
        // Guided Upright draws its guides on the photo as the crop tool shows it
        val cropView = ed.tool == MainTool.CROP && (ed.cropTab != "geometry" || ed.state?.upright == "guided")
        viewRequests.value = ViewRequest(ed.photoId, w, h, draft, ed.showingBefore && ed.tool != MainTool.CROP, cropView, mask, ++requestSeq)
    }

    /** The mask the view shows over the photo, as the masking options ask (none outside Masking). */
    private fun viewMask(ed: EditorState): MaskOverlay? = ed.selectedMask.takeIf { ed.tool == MainTool.MASKING && ed.showMaskOverlay }
        // Color Overlay draws as the reference's does (the engine's `tint`)
        ?.let { MaskOverlay(it, if (maskOverlayMode == "color") "tint" else maskOverlayMode, maskOverlayColor.toUInt(), maskOverlayOpacity.toUByte()) }

    /**
     * Zoomed in, ask for the part of the photo on screen at the zoom (never past the photo's own
     * pixels: beyond 100 % it is magnified), to draw sharp over the whole-photo render.
     */
    private fun requestDetail() {
        val ed = editor ?: return
        val base = ed.image
        val vp = ed.viewport
        val wanted = base?.takeIf { !ed.showingCropView && ed.tool != MainTool.CROP }
            ?.let { detailWindow(vp, IntSize(it.width, it.height), ed.info.width.toInt(), engine?.zoomEdge ?: 2560, ed.zoom, ed.pan) }
        if (base == null || wanted == null) {
            detailRequests.value = null
            ed.detail = null
            return
        }
        val (frame, rect) = wanted
        val current = ed.detail
        // what is on screen is already there
        if (current != null && current.base === base && current.frame == frame && current.window.covers(rect)) return
        detailRequests.value = DetailRequest(ed.photoId, frame, rect, ed.showingBefore, viewMask(ed), base)
    }

    private suspend fun renderDetails(e: Engine) {
        detailRequests.filterNotNull().collect { req ->
            val ed = editor?.takeIf { it.photoId == req.photo && it.image === req.base } ?: return@collect
            val w = req.window
            val image = withContext(e.viewRenders) {
                runCatching {
                    e.session.renderViewWindow(
                        req.photo, req.frame.width.toUInt(), req.frame.height.toUInt(),
                        w.left.toUInt(), w.top.toUInt(), w.width.toUInt(), w.height.toUInt(), req.before, req.mask,
                    )?.toImageBitmap()
                }.getOrNull()
            }
            // (a failed or refused window leaves the whole photo, magnified: never a blank view)
            if (editor === ed && ed.image === req.base && detailRequests.value === req) {
                ed.detail = image?.let { ViewDetail(it, req.frame, IntRect(w.left, w.top, w.left + it.width, w.top + it.height), req.base) }
            }
        }
    }

    private suspend fun renderViews(e: Engine) {
        viewRequests.filterNotNull().collect { req ->
            val ed = editor?.takeIf { it.photoId == req.photo } ?: return@collect
            ed.rendering = true
            val result = withContext(e.viewRenders) {
                runCatching {
                    val img = if (req.crop) {
                        e.session.renderCropView(req.photo, req.width.toUInt(), req.height.toUInt(), req.draft)
                    } else {
                        e.session.renderView(req.photo, req.width.toUInt(), req.height.toUInt(), req.draft, req.before, req.mask)
                    }
                    img.histogram to img.toImageBitmap()
                }
            }
            if (editor === ed) {
                result
                    .onSuccess { (histogram, bitmap) ->
                        ed.image = bitmap
                        // zoomed in, the part on screen again, of this render (a draft's comes with the next)
                        if (!req.draft) requestDetail()
                        ed.histogram = histogram
                        ed.rendered = true
                        ed.showingCropView = req.crop
                        takePendingCrop(ed, req.seq)
                        if (!req.draft && !req.before && !req.crop && ed.adjusting == null) prefetchNeighbours(e, ed, req.width, req.height)
                    }
                    .onFailure {
                        takePendingCrop(ed, req.seq)
                        // a cloud photo shows its thumbnail until the preview is down (the download says if it fails)
                        val waiting = ed.photoId in cloudIds && ed.photoId !in cloudPreviews
                        if (!req.draft && !waiting) say("Can't show ${ed.info.fileName}: ${it.message}")
                    }
                ed.rendering = false
            }
        }
    }

    /**
     * Once a photo is on screen, get its neighbours in grid order ready in the background (source
     * decoded, view rendered and cached), as the desktop does, so swiping to them is instant.
     */
    private fun prefetchNeighbours(e: Engine, ed: EditorState, width: Int, height: Int) {
        val i = photos.indexOfFirst { it.id == ed.photoId }
        if (i < 0) return
        for (n in listOfNotNull(photos.getOrNull(i + 1), photos.getOrNull(i - 1))) {
            if (!prefetched.add("${n.id}:${n.settingsHash}:${width}x$height")) continue
            viewModelScope.launch(e.prefetch) { runCatching { e.session.prefetchView(n.id, width.toUInt(), height.toUInt()) } }
        }
    }

    /** The library photo a swipe in the editor would open (+1 next, −1 previous), for its preview; none for a gallery photo. */
    fun neighbourInfo(step: Int): PhotoInfo? {
        val ed = editor ?: return null
        if (ed.device != null) return null
        val i = photos.indexOfFirst { it.id == ed.photoId }
        return if (i < 0) null else photos.getOrNull(i + step)
    }

    /** There's a photo a swipe that way would open (+1 next, −1 previous). */
    fun hasNeighbour(step: Int): Boolean {
        val ed = editor ?: return false
        val origin = ed.device
        if (origin != null) {
            val shown = shownDevicePhotos
            val i = shown.indexOfFirst { it.id == origin.photo.id }
            return i >= 0 && shown.getOrNull(i + step) != null
        }
        return neighbourInfo(step) != null
    }

    /** Swipe in the editor: the next (+1) or previous (−1) photo of the grid (of the gallery's, for a gallery photo). */
    fun showNeighbour(step: Int) {
        val ed = editor ?: return
        val origin = ed.device
        if (origin != null) {
            val shown = shownDevicePhotos
            val i = shown.indexOfFirst { it.id == origin.photo.id }
            val next = shown.getOrNull(i + step) ?: return
            if (i < 0 || importing) return
            if (ed.adjusting != null) {
                ed.adjusting = null
                engine?.let { e -> viewModelScope.launch { runCatching { e.command { endAdjustment() } } } }
            }
            openDevicePhoto(next)
            return
        }
        val i = photos.indexOfFirst { it.id == ed.photoId }
        if (i < 0) return
        val next = photos.getOrNull(i + step) ?: return
        val e = engine ?: return
        if (ed.adjusting != null) {
            ed.adjusting = null
            viewModelScope.launch { runCatching { e.command { endAdjustment() } } }
        }
        openEditor(next)
    }

    /** A main tool: opens it, or closes it when it is open (back to just the photo and the row). Crop is a mode. */
    fun selectTool(tool: MainTool) {
        val ed = editor ?: return
        if (ed.section == EditSection.PROFILES || ed.tool == MainTool.CROP) return
        ed.sub = null
        ed.pickingWb = false
        ed.targeting = null
        if (tool == MainTool.CROP) {
            ed.toolBeforeCrop = ed.tool
            ed.tool = tool
            markMode(ed)
            ed.zoom = 1f
            refreshCrop()
            requestView()
            return
        }
        if (tool == MainTool.MASKING) {
            ed.tool = if (ed.tool == tool) null else tool
            ed.maskPicker = null
            ed.rangePicker = null
            if (ed.tool == tool) {
                ed.maskSection = null
                ed.maskingMark = null
                ed.maskingEdited = false
                engine?.let { e -> viewModelScope.launch { runCatching { e.command { historyDepth() } }.onSuccess { ed.maskingMark = it } } }
                refreshMasks()
            }
            requestView()
            return
        }
        if (tool == MainTool.REMOVE) {
            ed.tool = if (ed.tool == tool) null else tool
            ed.selectedSpot = null
            if (ed.tool == tool) {
                ed.zoom = 1f
                refreshSpots()
            }
            requestView()
            return
        }
        ed.tool = if (ed.tool == tool) null else tool
    }

    /** Crop's tab: Aspect (the frame over the whole photo) or Geometry (the photo alone, as the reference's). */
    fun setCropTab(tab: String) {
        val ed = editor ?: return
        if (ed.cropTab == tab) return
        ed.cropTab = tab
        requestView()
    }

    private fun refreshCrop() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch { runCatching { e.command { cropFrame(ed.photoId) } }.onSuccess { ed.crop = it } }
    }

    private fun refreshSpots() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { Triple(spots(ed.photoId), selectedSpot(), viewGeometry(ed.photoId)) } }.onSuccess { (sp, sel, g) ->
                ed.spots = sp
                ed.selectedSpot = sel?.toInt()?.takeIf { it < sp.size }
                ed.geometry = g
            }
        }
    }

    /** Read the photo's masks again ([lastPart]: edit the edited mask's newest part). */
    private fun refreshMasks(lastPart: Boolean = false) {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { Triple(masks(ed.photoId), selectedMask(), viewGeometry(ed.photoId)) to historyDepth() } }.onSuccess { (t, depth) ->
                val (ms, sel, g) = t
                ed.maskingEdited = ed.maskingMark.let { it != null && it != depth }
                ed.masks = ms
                ed.selectedMask = sel?.takeIf { s -> ms.any { it.id == s } } ?: ms.firstOrNull()?.id
                ed.geometry = g
                if (ms.isEmpty() && ed.maskSection != null) ed.maskSection = null
                val parts = ms.firstOrNull { it.id == ed.selectedMask }?.components?.size ?: 0
                ed.maskPart = when {
                    lastPart -> (parts - 1).coerceAtLeast(0)
                    // the mask itself stays edited
                    ed.maskPart < 0 -> -1
                    else -> ed.maskPart.coerceIn(0, (parts - 1).coerceAtLeast(0))
                }
                refreshMaskThumbs(ed)
            }
        }
    }

    private var maskThumbRound = 0
    private val MASK_THUMB_EDGE = 104

    /** Render the masks' pictures again (the latest call's only), as the reference's column shows them. */
    private fun refreshMaskThumbs(ed: EditorState) {
        val e = engine ?: return
        val round = ++maskThumbRound
        val ids = ed.masks.map { it.id }
        viewModelScope.launch {
            val thumbs = withContext(e.looks) {
                ids.mapNotNull { id ->
                    if (round != maskThumbRound) return@withContext null
                    runCatching { e.session.renderMaskThumbnail(ed.photoId, id, MASK_THUMB_EDGE.toUInt()).toImageBitmap() }.getOrNull()?.let { id to it }
                }
            } ?: return@launch
            if (round == maskThumbRound && editor === ed) ed.maskThumbs = thumbs.toMap()
        }
    }

    /** Masking's round button: no masks yet, create one; else the photo's masks. */
    fun openMaskPicker() {
        val ed = editor ?: return
        ed.maskPicker = if (ed.masks.isEmpty()) "new" else "list"
    }

    /** A section of Masking's row: the edited mask's sliders of it (again: closed); no mask yet, create one. */
    fun selectMaskSection(section: String) {
        val ed = editor ?: return
        if (ed.selectedMask == null) {
            ed.maskPicker = "new"
            return
        }
        ed.maskSection = if (ed.maskSection == section) null else section
    }

    /** Edit mask [id] itself (its picture in the column; its parts are picked under it). */
    fun selectMask(id: UInt) {
        val ed = editor ?: return
        val e = engine ?: return
        ed.selectedMask = id
        ed.maskPart = -1
        viewModelScope.launch {
            runCatching { e.command { execute("mask.select", JSONObject().put("id", id.toLong()).toString()) } }
            if (ed.showMaskOverlay) requestView()
        }
    }

    /** A shape of [kind] placed in the middle of the view (radial and linear; the others are painted or picked on the photo). */
    private fun shapeParams(ed: EditorState, kind: String): JSONObject {
        val toPhoto = ed.geometry?.let { Affine.of(it.toPhoto) }
        fun at(u: Double, v: Double): org.json.JSONArray {
            val (x, y) = toPhoto?.apply(u, v) ?: (u to v)
            return org.json.JSONArray().put(x).put(y)
        }
        val params = JSONObject().put("kind", kind)
        when (kind) {
            "radial" -> params.put("center", at(0.5, 0.5)).put("rx", 0.2).put("ry", 0.15)
            "linear" -> params.put("start", at(0.5, 0.25)).put("end", at(0.5, 0.6))
        }
        return params
    }

    /** A new mask of [kind] (brush, linear, radial, placed in the middle of the view; a colour or luminance range, picked next). */
    fun addMask(kind: String) {
        val ed = editor ?: return
        if (kind == "colorRange" || kind == "luminanceRange") return startRangeMask(kind, null)
        val params = shapeParams(ed, kind)
        ed.maskPicker = null
        ed.maskPart = 0
        edit(after = {
            refreshMasks()
            showNewMask(ed)
        }) { it.execute("mask.add", params.toString()) }
    }

    /** A part of [kind] added to the edited mask ([op] `add`) or taken from it (`subtract`); then it's the part edited. */
    fun addMaskPart(op: String, kind: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (kind == "colorRange" || kind == "luminanceRange") return startRangeMask(kind, op)
        val params = shapeParams(ed, kind).put("id", mask.toLong()).put("op", op)
        ed.maskPicker = null
        edit(after = {
            refreshMasks(lastPart = true)
            showNewMask(ed)
        }) { it.execute("mask.addComponent", params.toString()) }
    }

    /**
     * A new colour or luminance range mask ([op] null) or part (`add`, `subtract`), then its picker,
     * as the reference's: the part and what's picked for it are one step, the picker's Cancel takes
     * them back.
     */
    private fun startRangeMask(kind: String, op: String?) {
        val ed = editor ?: return
        val e = engine ?: return
        val params = JSONObject().put("kind", kind)
        // a luminance range starts as the whole scale (0 / 0 to 100 / 100), ranked: by rank in the
        // photo's own luminance (both as the reference's)
        if (kind == "luminanceRange") params.put("lo", 0.0).put("hi", 1.0).put("loFeather", 0.0).put("hiFeather", 0.0).put("ranked", true)
        // a colour range selects by hue first (lightness counting little), as the reference's
        if (kind == "colorRange") params.put("hueFirst", true)
        if (op != null) params.put("id", (ed.selectedMask ?: return).toLong()).put("op", op)
        ed.maskPicker = null
        viewModelScope.launch {
            runCatching {
                e.command {
                    val mark = historyDepth()
                    beginAdjustment(kindLabel(kind))
                    execute(if (op == null) "mask.add" else "mask.addComponent", params.toString())
                    Triple(mark, masks(ed.photoId), selectedMask() to viewGeometry(ed.photoId))
                }
            }.onSuccess { (mark, ms, sg) ->
                ed.masks = ms
                ed.geometry = sg.second
                val m = ms.firstOrNull { it.id == sg.first } ?: return@onSuccess
                val part = m.components.size - 1
                ed.selectedMask = m.id
                ed.maskPart = part
                // in the mode last used, as the reference's
                ed.rangePicker = RangePicker(kind, m.id, part, creating = true, mode = pickerMode, ring = null, area = null).also { it.mark = mark }
                requestView()
            }.onFailure { say(it.message) }
        }
    }

    /** The edited part's Pick: its picker again, at the point or over the area it last picked; Cancel puts it back as it was. */
    fun repickPart() {
        val ed = editor ?: return
        val e = engine ?: return
        val mask = ed.masks.firstOrNull { it.id == ed.selectedMask } ?: return
        val i = ed.maskPart
        val part = mask.components.getOrNull(i)?.takeIf { it.kind == "colorRange" || it.kind == "luminanceRange" } ?: return
        val picked = runCatching { JSONObject(part.shape).optJSONArray("picked") }.getOrNull()
        val pts = (0 until (picked?.length() ?: 0)).mapNotNull { k -> picked?.optJSONObject(k)?.let { it.optDouble("x") to it.optDouble("y") } }
        val area = pts.takeIf { it.size == 2 }?.let { listOf(it[0].first, it[0].second, it[1].first, it[1].second) }
        // an area's ring (should the mode change) is in its middle
        val ring = area?.let { ((it[0] + it[2]) / 2) to ((it[1] + it[3]) / 2) } ?: pts.firstOrNull()
        val picker = RangePicker(part.kind, mask.id, i, creating = false, mode = if (area != null) "area" else "point", ring = ring, area = area)
        ed.rangePicker = picker
        ed.maskSection = null
        viewModelScope.launch {
            runCatching {
                e.command {
                    val mark = historyDepth()
                    beginAdjustment(kindLabel(part.kind))
                    mark
                }
            }.onSuccess { picker.mark = it }
        }
        showNewMask(ed)
    }

    /** The range picker's Mode last chosen (`point` or `area`): a new picker opens in it, as the reference's. */
    private var pickerMode = prefs.getString("pickerMode", "point") ?: "point"

    /** The picker's Mode: the ring (`point`) or an area (`area`); kept for the next picker. */
    fun setPickerMode(mode: String) {
        val p = editor?.rangePicker ?: return
        p.mode = mode
        pickerMode = mode
        prefs.edit().putString("pickerMode", mode).apply()
    }

    private var pickBusy = false
    private var pickPending: JSONObject? = null

    /** The ring moved to [x], [y] of the photo: it samples there (the latest place only while one is under way). */
    fun pickAt(x: Double, y: Double) {
        val p = editor?.rangePicker ?: return
        p.ring = x to y
        samplePicked(JSONObject().put("x", x).put("y", y))
    }

    /** An area dragged on the photo, corners [x0], [y0] and [x1], [y1]: it samples its colours (or lightnesses). */
    fun pickArea(x0: Double, y0: Double, x1: Double, y1: Double) {
        val p = editor?.rangePicker ?: return
        p.area = listOf(x0, y0, x1, y1)
        p.ring = ((x0 + x1) / 2) to ((y0 + y1) / 2)
        samplePicked(JSONObject().put("x0", x0).put("y0", y0).put("x1", x1).put("y1", y1))
    }

    private fun samplePicked(at: JSONObject) {
        val ed = editor ?: return
        val e = engine ?: return
        val p = ed.rangePicker ?: return
        if (pickBusy) {
            pickPending = at
            return
        }
        pickBusy = true
        val params = JSONObject(at.toString()).put("id", p.mask.toLong()).put("component", p.part)
        val command = if (p.color) "mask.sampleColor" else "mask.sampleLuminance"
        viewModelScope.launch {
            val r = runCatching { e.command { execute(command, params.toString()); masks(ed.photoId) } }
            pickBusy = false
            r.onSuccess { ms ->
                if (ed.rangePicker !== p) return@onSuccess
                ed.masks = ms
                ed.liveStops = null
                p.sampled = true
                // the mask shows as it's picked, as the reference's
                if (!ed.showMaskOverlay) ed.showMaskOverlay = true
                requestView(draft = true)
            }
            pickPending?.let {
                pickPending = null
                samplePicked(it)
            }
        }
    }

    /** The ring let go: the photo at its full size. */
    fun pickDone() = requestView()

    /** The picker's Refine (`refine`) moving. */
    fun pickerShapeValue(key: String, value: Double) {
        val ed = editor ?: return
        val p = ed.rangePicker ?: return
        val mask = ed.masks.firstOrNull { it.id == p.mask } ?: return
        ed.liveShape = ed.liveShape + (key to value)
        val shape = partShape(mask, p.part).put(key, value)
        sendPickerShape(p, shape)
    }

    /** The picker's luminance range moving: its four lightnesses. */
    fun pickerStops(stops: List<Double>) {
        val ed = editor ?: return
        val p = ed.rangePicker ?: return
        val mask = ed.masks.firstOrNull { it.id == p.mask } ?: return
        ed.liveStops = stops
        sendPickerShape(p, luminanceShape(partShape(mask, p.part), stops))
    }

    private fun sendPickerShape(p: RangePicker, shape: JSONObject) {
        val ed = editor ?: return
        val e = engine ?: return
        val params = JSONObject().put("id", p.mask.toLong()).put("component", p.part).put("shape", shape).toString()
        viewModelScope.launch {
            runCatching { e.command { execute("mask.update", params); masks(ed.photoId) } }.onSuccess { ms ->
                if (ed.rangePicker === p) ed.masks = ms
                requestView(draft = true)
            }
        }
    }

    /** A picker's slider let go: the photo at its full size, the values the engine's. */
    fun pickerSliderDone() {
        val ed = editor ?: return
        ed.liveShape = emptyMap()
        ed.liveStops = null
        requestView()
    }

    /** The picker's Apply: what was picked is one step; the part stays edited (the masks' pictures follow). */
    fun applyRangePicker() {
        val ed = editor ?: return
        val e = engine ?: return
        val p = ed.rangePicker?.takeIf { it.sampled } ?: return
        ed.rangePicker = null
        ed.liveShape = emptyMap()
        ed.liveStops = null
        ed.maskPart = p.part
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) } }.onSuccess { ed.update(it) }
            refreshMasks()
            requestView()
        }
    }

    /** The picker's Cancel: a part it made goes (its mask too, if it was the first), one picked again is put back. */
    fun cancelRangePicker() {
        val ed = editor ?: return
        val p = ed.rangePicker ?: return
        ed.rangePicker = null
        ed.liveShape = emptyMap()
        ed.liveStops = null
        val mark = p.mark
        if (p.creating) ed.maskPart = (p.part - 1).coerceAtLeast(0)
        edit(after = { refreshMasks() }) { if (mark != null) it.discardTo(mark) else it.endAdjustment() }
    }

    /** Edit part [i] of the edited mask (its handles on the photo, its tools on the bar). */
    fun selectMaskPart(i: Int) {
        val ed = editor ?: return
        ed.maskPart = i
    }

    /** A part's `invert`, `hide` (again: show) or `delete` (the last one takes its mask with it): one step. */
    fun partCommand(i: Int, action: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (action == "delete") ed.maskPart = (i - 1).coerceAtLeast(0)
        val params = JSONObject().put("id", mask.toLong()).put("component", i).put("action", action)
        edit(after = { refreshMasks() }) { it.execute("mask.component", params.toString()) }
    }

    /** Part [i]'s copy, named [name], just above it (the newer) and edited next. */
    fun duplicatePart(i: Int, name: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        val params = JSONObject().put("id", mask.toLong()).put("component", i).put("action", "duplicate").put("name", name)
        ed.maskPart = i + 1
        edit(after = { refreshMasks() }) { it.execute("mask.component", params.toString()) }
    }

    /** Part [i] adding to its mask or subtracting from it ([op] `add`, `subtract`). */
    fun partOp(i: Int, op: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        val params = JSONObject().put("id", mask.toLong()).put("component", i).put("action", "op").put("op", op)
        edit(after = { refreshMasks() }) { it.execute("mask.component", params.toString()) }
    }

    /** Part [i]'s new name (a blank one is ignored). */
    fun renamePart(i: Int, name: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (name.isBlank()) return
        val params = JSONObject().put("id", mask.toLong()).put("component", i).put("action", "rename").put("name", name.trim())
        edit(after = { refreshMasks() }) { it.execute("mask.component", params.toString()) }
    }

    /** Masking's Cancel (its mode's X): with changes since it opened, asks first, as the reference's; else it just closes. */
    fun cancelMasking() {
        val ed = editor ?: return
        val e = engine ?: return
        val mark = ed.maskingMark ?: return closeMasking(ed)
        viewModelScope.launch {
            runCatching { e.command { historyDepth() } }.onSuccess { depth ->
                ed.discardMasking = depth != mark
                if (depth == mark) closeMasking(ed)
            }
        }
    }

    /** The discard question's Discard changes: every step since Masking opened, taken back for good, and Masking closed. */
    fun discardMaskingChanges() {
        val ed = editor ?: return
        ed.discardMasking = false
        val mark = ed.maskingMark
        closeMasking(ed)
        if (mark != null) edit(after = ::refreshToolState) { s -> s.discardTo(mark) }
    }

    /** Masking's Apply (its mode's ✓): its changes kept, Masking closed. */
    fun applyMasking() {
        val ed = editor ?: return
        closeMasking(ed)
    }

    private fun closeMasking(ed: EditorState) {
        if (ed.tool != MainTool.MASKING) return
        ed.maskingEdited = false
        ed.rangePicker = null
        ed.maskPicker = null
        ed.maskSection = null
        ed.maskingMark = null
        ed.tool = null
        requestView()
    }

    /** A command on the edited mask (`mask.invert`, `mask.duplicate`, `mask.delete`). */
    fun maskCommand(id: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (id == "mask.delete") {
            ed.selectedMask = null
            ed.maskSection = null
        }
        edit(after = { refreshMasks() }) { it.execute(id, JSONObject().put("id", mask.toLong()).toString()) }
    }

    /** The edited mask's new name (its menu's Rename); a blank one is ignored. */
    fun renameMask(name: String) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (name.isBlank()) return
        edit(after = { refreshMasks() }) { it.execute("mask.rename", JSONObject().put("id", mask.toLong()).put("name", name.trim()).toString()) }
    }

    /** The edited mask's effect hidden, or shown again (its menu's Hide and Show). */
    fun toggleMaskVisible() = maskCommand("mask.visible")

    /** A copy of the edited mask that selects the rest of the photo, edited next (its menu's Duplicate and invert). */
    fun duplicateInvertedMask() {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        edit(after = { refreshMasks() }) { it.execute("mask.duplicate", JSONObject().put("id", mask.toLong()).put("invert", true).toString()) }
    }

    private fun beginMaskChange() {
        val ed = editor ?: return
        val e = engine ?: return
        if (ed.adjusting == "mask") return
        ed.adjusting = "mask"
        viewModelScope.launch { runCatching { e.command { beginAdjustment("Mask") } } }
    }

    private fun endMaskChange(after: () -> Unit) {
        val ed = editor ?: return
        val e = engine ?: return
        if (ed.adjusting != "mask") return after()
        ed.adjusting = null
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) } }.onSuccess { ed.update(it) }
            refreshMasks()
            after()
            requestView()
        }
    }

    /** A local slider moving: [key] of the edited mask (`exposure`…) to [value]. */
    fun setLocal(key: String, value: Double) {
        val ed = editor ?: return
        val e = engine ?: return
        val mask = ed.selectedMask ?: return
        ed.liveLocal = ed.liveLocal + (key to value)
        // the overlay goes as an adjustment begins, unless it's shown always (the reference's)
        if (ed.showMaskOverlay && !maskOverlayAlways) ed.showMaskOverlay = false
        beginMaskChange()
        val params = JSONObject().put("id", mask.toLong()).put("values", JSONObject().put(key, value)).toString()
        viewModelScope.launch {
            runCatching { e.command { execute("mask.adjust", params) } }.onSuccess { requestView(draft = true) }
        }
    }

    fun localDone() = endMaskChange { editor?.liveLocal = emptyMap() }

    /** The edited mask's shape with [key] (`feather`, `refine`, `lo`, `hi`) at [value]. */
    fun setShapeValue(key: String, value: Double) {
        val ed = editor ?: return
        val mask = ed.masks.firstOrNull { it.id == ed.selectedMask } ?: return
        ed.liveShape = ed.liveShape + (key to value)
        val shape = partShape(mask, ed.maskPart)
        ed.liveShape.forEach { (k, v) -> shape.put(k, v) }
        beginMaskChange()
        sendShape(shape)
    }

    fun shapeDone() = endMaskChange { editor?.liveShape = emptyMap() }

    fun beginShapeDrag() = beginMaskChange()

    /** A radial's or linear's handle moving: the whole new shape. */
    fun dragShape(shape: JSONObject) {
        val ed = editor ?: return
        ed.liveShapeJson = shape.toString()
        sendShape(shape)
    }

    fun endShapeDrag() = endMaskChange { editor?.liveShapeJson = null }

    private fun sendShape(shape: JSONObject) {
        val ed = editor ?: return
        val e = engine ?: return
        val mask = ed.selectedMask ?: return
        val params = JSONObject().put("id", mask.toLong()).put("component", ed.maskPart).put("shape", shape).toString()
        viewModelScope.launch {
            runCatching { e.command { execute("mask.update", params) } }.onSuccess { requestView(draft = true) }
        }
    }

    /** A brush stroke over [points] (the photo's 0–1): painting into the mask, or erasing. */
    fun brushStroke(points: List<Pair<Double, Double>>) {
        val ed = editor ?: return
        val mask = ed.selectedMask ?: return
        if (points.isEmpty()) return
        val params = JSONObject()
            .put("id", mask.toLong())
            .put("points", org.json.JSONArray(points.map { (x, y) -> org.json.JSONArray().put(x).put(y) }))
            .put("size", ed.maskBrushSize.toDouble())
            .put("feather", ed.maskBrushFeather.toDouble())
            .put("flow", ed.maskBrushFlow.toDouble())
            .put("erase", ed.maskErase)
            // painted as the reference's brush: the feather spreads past the radius, flow builds up
            .put("spread", true)
        edit(after = { refreshMasks() }) { it.execute("mask.brushStroke", params.toString()) }
    }

    /**
     * The masking options, as the reference's, kept between launches: the mask overlay shown always
     * (else it shows for a new mask and goes as an adjustment begins), its mode (`color`, `colorOnBw`,
     * `imageOnBw`, `imageOnBlack`, `imageOnWhite`, `whiteOnBlack`), colour (`0xRRGGBB`) and opacity
     * (0..100); and the tool overlay (a shape's lines and handles) shown always, else while a finger is
     * on the photo.
     */
    var maskOverlayAlways by mutableStateOf(prefs.getBoolean("maskOverlayAlways", false))
        private set
    var maskOverlayMode by mutableStateOf(prefs.getString("maskOverlayMode", "color") ?: "color")
        private set
    var maskOverlayColor by mutableStateOf(prefs.getInt("maskOverlayColor", 0xFF0000))
        private set
    var maskOverlayOpacity by mutableStateOf(prefs.getInt("maskOverlayOpacity", 50))
        private set
    var toolOverlayAlways by mutableStateOf(prefs.getBoolean("toolOverlayAlways", true))
        private set

    fun setMaskOptions(
        always: Boolean = maskOverlayAlways,
        mode: String = maskOverlayMode,
        color: Int = maskOverlayColor,
        opacity: Int = maskOverlayOpacity,
        tools: Boolean = toolOverlayAlways,
    ) {
        maskOverlayAlways = always
        maskOverlayMode = mode
        maskOverlayColor = color
        maskOverlayOpacity = opacity.coerceIn(0, 100)
        toolOverlayAlways = tools
        prefs.edit().putBoolean("maskOverlayAlways", always).putString("maskOverlayMode", mode).putInt("maskOverlayColor", color)
            .putInt("maskOverlayOpacity", maskOverlayOpacity).putBoolean("toolOverlayAlways", tools).apply()
        val ed = editor ?: return
        // shown always: on now; a change of look shows itself
        if (always) ed.showMaskOverlay = true
        if (ed.showMaskOverlay) requestView()
    }

    /** The mask overlay for a new mask or part (the reference's), unless it's hidden for good by the toggle. */
    private fun showNewMask(ed: EditorState) {
        if (!ed.showMaskOverlay) {
            ed.showMaskOverlay = true
            requestView()
        }
    }

    /** The mask tinted over the photo, or not. */
    fun toggleMaskOverlay() {
        val ed = editor ?: return
        ed.showMaskOverlay = !ed.showMaskOverlay
        requestView()
    }

    // ------------------------------------------------------------------ targeted adjustment

    /** Targeted adjustment on or off ([target]: `curve`, `hue`, `sat`, `lum`; null: off). */
    fun setTargeting(target: String?) {
        val ed = editor ?: return
        ed.targeting = target
        // the view ↔ photo map, for where the finger is
        if (target != null) engine?.let { e -> viewModelScope.launch { runCatching { e.command { viewGeometry(ed.photoId) } }.onSuccess { ed.geometry = it } } }
    }

    /** A finger on the photo for targeted adjustment: what it does until it lifts is one step. */
    fun beginTargeted() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = "targeted"
        viewModelScope.launch { runCatching { e.command { beginAdjustment("Targeted Adjustment") } } }
    }

    /** [delta] slider units (point curves: output levels) at [x], [y] of the view (0–1). */
    fun targeted(x: Float, y: Float, delta: Double) {
        val ed = editor ?: return
        val e = engine ?: return
        val target = ed.targeting ?: return
        val (px, py) = ed.geometry?.let { g -> Affine.of(g.toPhoto)?.apply(x.toDouble(), y.toDouble()) } ?: (x.toDouble() to y.toDouble())
        val params = JSONObject().put("target", target).put("x", px).put("y", py).put("delta", delta)
        if (target == "curve" && ed.curveChannel != "parametric") params.put("channel", ed.curveChannel)
        viewModelScope.launch {
            runCatching { e.command { execute("develop.targeted", params.toString()) } }.onSuccess { r ->
                // the sliders follow
                runCatching { JSONObject(r) }.getOrNull()?.let { changed ->
                    ed.controls = ed.controls.map { c -> if (changed.has(c.id)) c.copy(value = changed.optDouble(c.id, c.value)) else c }
                    // Mix shows the colour the finger moves most
                    val band = changed.keys().asSequence().filter { it.startsWith("mixer.") }.maxByOrNull { abs(changed.optDouble(it, 0.0)) }?.split('.')?.getOrNull(1)
                    bandIds.indexOf(band).takeIf { it >= 0 }?.let { ed.mixerBand = it }
                }
                if (target == "curve") refreshCurve()
                requestView(draft = true)
            }
        }
    }

    fun endTargeted() {
        val ed = editor ?: return
        val e = engine ?: return
        if (ed.adjusting != "targeted") return
        ed.adjusting = null
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) } }.onSuccess { ed.update(it) }
            if (ed.targeting == "curve") refreshCurve()
            requestView()
        }
    }

    // ------------------------------------------------------------------ grading wheels

    /** A finger on a grading wheel: what it does until it lifts is one step. */
    fun beginWheel(range: String) {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = "grading"
        val label = range.replaceFirstChar { it.uppercase() } + " Grading"
        viewModelScope.launch { runCatching { e.command { beginAdjustment(label) } } }
    }

    /** A grading wheel's point: [range]'s hue (degrees) and saturation (0–100) together. */
    fun dragWheel(range: String, hue: Double, sat: Double) {
        val ed = editor ?: return
        val e = engine ?: return
        val values = mapOf("grading.$range.hue" to hue, "grading.$range.sat" to sat)
        ed.controls = ed.controls.map { c -> values[c.id]?.let { c.copy(value = it) } ?: c }
        val params = JSONObject().put("values", JSONObject(values)).toString()
        viewModelScope.launch { runCatching { e.command { execute("develop.set", params) } }.onSuccess { requestView(draft = true) } }
    }

    fun endWheel() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = null
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) } }.onSuccess { ed.update(it) }
            requestView()
        }
    }

    // ------------------------------------------------------------------ curve

    /** Light › Curve: the curves over the photo, their channels and presets in the panel. */
    fun openCurve() {
        val ed = editor ?: return
        ed.sub = "curve"
        refreshCurve()
    }

    private fun refreshCurve() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch { runCatching { e.command { toneCurve(ed.photoId) } }.onSuccess { ed.curve = it } }
    }

    fun setCurveChannel(channel: String) {
        editor?.curveChannel = channel
    }

    /** A finger takes the curve: what it does until it lifts is one step. */
    fun beginCurveDrag() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = "curve"
        viewModelScope.launch { runCatching { e.command { beginAdjustment("Tone Curve") } } }
    }

    /** The open channel's points while a finger moves one. */
    fun dragCurve(points: List<Pair<Double, Double>>) {
        val ed = editor ?: return
        val e = engine ?: return
        ed.liveCurve = points
        val params = curveParams(ed.curveChannel, points)
        viewModelScope.launch { runCatching { e.command { execute("develop.curve", params) } }.onSuccess { requestView(draft = true) } }
    }

    fun endCurveDrag() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = null
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) to toneCurve(ed.photoId) } }.onSuccess { (snap, curve) ->
                ed.update(snap)
                ed.curve = curve
            }
            ed.liveCurve = null
            requestView()
        }
    }

    /** The open channel's points set at once (a point added or taken away): one step. */
    fun setCurvePoints(points: List<Pair<Double, Double>>) {
        val ed = editor ?: return
        val params = curveParams(ed.curveChannel, points)
        edit(after = ::refreshCurve) { it.execute("develop.curve", params) }
    }

    /** A point-curve preset on all four point curves (the parametric curve stays). */
    fun applyCurvePreset(name: String) = edit(after = ::refreshCurve) { it.execute("curve.applyPreset", JSONObject().put("name", name).toString()) }

    /** The open channel back to a straight line (the parametric one: its regions and splits back to theirs). */
    fun resetCurveChannel() {
        val ed = editor ?: return
        val channel = ed.curveChannel
        edit(after = ::refreshCurve) { it.execute("curve.reset", JSONObject().put("channel", channel).toString()) }
    }

    private fun curveParams(channel: String, points: List<Pair<Double, Double>>) =
        JSONObject().put("channel", channel).put("points", JSONArray(points.map { (x, y) -> JSONArray().put(x).put(y) })).toString()

    // ------------------------------------------------------------------ actions

    /** An action of Actions' row: Auto and Fix angle apply at once (one step each), Rate opens its panel (again: closes it). */
    fun runAction(a: QuickAction) {
        val ed = editor ?: return
        when (a) {
            QuickAction.AUTO -> edit(after = { say("Auto settings applied") }) { it.execute("develop.auto", "{}") }
            // Upright's automatic correction, cropped to leave no blank corners: one step
            QuickAction.FIX_ANGLE -> edit(after = { say("Angle fixed") }) { s ->
                s.beginAdjustment("Fix Angle")
                try {
                    s.execute("geometry.upright", JSONObject().put("mode", "auto").toString())
                    s.execute("develop.merge", JSONObject().put("settings", JSONObject().put("geometry", JSONObject().put("constrain_crop", true))).toString())
                } finally {
                    s.endAdjustment()
                }
            }
        }
    }

    /** Crop › Geometry's switch: crop the corners an Upright or transform leaves blank (one step Cancel takes back). */
    fun setConstrainCrop(on: Boolean) =
        cropCommand("develop.merge", JSONObject().put("settings", JSONObject().put("geometry", JSONObject().put("constrain_crop", on))).put("label", "Constrain Crop"))

    /** A spot over [points] (the photo's 0–1): one point a round spot, more a brushed path. */
    fun addSpot(points: List<Pair<Double, Double>>) {
        val ed = editor ?: return
        if (points.isEmpty()) return
        val params = JSONObject()
            .put("mode", ed.removeMode)
            .put("points", JSONArray(points.map { (x, y) -> JSONArray().put(x).put(y) }))
            .put("size", ed.brushSize.toDouble())
            .put("feather", ed.brushFeather.toDouble())
            .put("opacity", ed.brushOpacity.toDouble())
        edit(after = { refreshSpots() }) { it.execute("spot.add", params.toString()) }
    }

    /** Pick spot [index]: the panel's sliders become its settings. */
    fun selectSpot(index: Int) {
        val ed = editor ?: return
        val e = engine ?: return
        ed.selectedSpot = index
        ed.spots.getOrNull(index)?.let {
            ed.brushSize = it.size.toFloat()
            ed.brushFeather = it.feather.toFloat()
            ed.brushOpacity = it.opacity.toFloat()
        }
        viewModelScope.launch { runCatching { e.command { execute("spot.select", JSONObject().put("index", index).toString()) } } }
    }

    /** The picked spot moved by [dx], [dy] (the photo's 0–1); its source goes along (one step). */
    fun moveSpot(dx: Double, dy: Double) = updateSpot(JSONObject().put("move", JSONArray().put(dx).put(dy)))

    /** The picked spot's source moved by [dx], [dy] (one step). */
    fun moveSpotSource(dx: Double, dy: Double) = updateSpot(JSONObject().put("moveSource", JSONArray().put(dx).put(dy)))

    private fun updateSpot(params: JSONObject) {
        val ed = editor ?: return
        val i = ed.selectedSpot ?: return
        edit(after = { refreshSpots() }) { it.execute("spot.update", params.put("index", i).toString()) }
    }

    /** The spot menu's new source: the engine's next-best source for the picked spot (one step). */
    fun newSpotSource() {
        val ed = editor ?: return
        val i = ed.selectedSpot ?: return
        edit(after = { refreshSpots() }) { it.execute("spot.refreshSource", JSONObject().put("index", i).toString()) }
    }

    fun deleteSpot() {
        val ed = editor ?: return
        val i = ed.selectedSpot ?: return
        ed.selectedSpot = null
        edit(after = { refreshSpots() }) { it.execute("spot.delete", JSONObject().put("index", i).toString()) }
    }

    /** Remove, heal or clone: for the picked spot, else for the next ones. */
    fun setRemoveMode(mode: String) {
        val ed = editor ?: return
        val i = ed.selectedSpot
        if (i == null) {
            ed.removeMode = mode
            return
        }
        edit(after = { refreshSpots() }) { it.execute("spot.update", JSONObject().put("index", i).put("mode", mode).toString()) }
    }

    /** Read Guided Upright's guides again (after a change, on opening Geometry). */
    fun refreshGuides() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch { runCatching { e.command { uprightGuides(ed.photoId) } }.onSuccess { if (editor === ed) ed.guides = it } }
    }

    /** Guided Upright: the mode, which shows the photo without its crop to draw on (or back off). */
    fun setGuided(on: Boolean) {
        val mode = JSONObject().put("mode", if (on) "guided" else "off").toString()
        edit(after = {
            refreshCrop()
            refreshGuides()
        }) { it.execute("geometry.upright", mode) }
    }

    /** A guide drawn on the crop view from [a] to [b] (normalized): the fifth drops the oldest (one step). */
    fun addGuide(a: Pair<Double, Double>, b: Pair<Double, Double>) {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            val ends = runCatching { e.command { guidePoint(ed.photoId, a.first, a.second) to guidePoint(ed.photoId, b.first, b.second) } }.getOrNull() ?: return@launch
            val (p, q) = ends
            if (p.size != 2 || q.size != 2) return@launch
            val guide = JSONArray().put(JSONArray().put(p[0]).put(p[1]).put(q[0]).put(q[1]))
            val params = JSONObject().put("guides", guide).put("add", true).toString()
            edit(after = {
                refreshCrop()
                refreshGuides()
            }) { it.execute("geometry.guides", params) }
        }
    }

    /** Guided Upright's Clear: no guides (one step). */
    fun clearGuides() {
        val params = JSONObject().put("guides", JSONArray()).toString()
        edit(after = {
            refreshCrop()
            refreshGuides()
        }) { it.execute("geometry.guides", params) }
    }

    /** The brush's size (of the long edge), feather or opacity while a slider moves. */
    fun setBrush(size: Float? = null, feather: Float? = null, opacity: Float? = null) {
        val ed = editor ?: return
        size?.let { ed.brushSize = it.coerceIn(0.002f, 0.1f) }
        feather?.let { ed.brushFeather = it }
        opacity?.let { ed.brushOpacity = it }
    }

    /** A brush slider let go: the picked spot takes its settings (one step). */
    fun brushDone() {
        val ed = editor ?: return
        val i = ed.selectedSpot ?: return
        val params = JSONObject().put("index", i).put("size", ed.brushSize.toDouble()).put("feather", ed.brushFeather.toDouble()).put("opacity", ed.brushOpacity.toDouble())
        edit(after = { refreshSpots() }) { it.execute("spot.update", params.toString()) }
    }

    /** Leave Crop keeping what it did, back to the tool open before it. */
    fun applyCrop() {
        val ed = editor ?: return
        leaveCrop(ed)
        ed.modeMark = null
        requestView()
    }

    /** Leave Crop taking back everything done in it, back to the tool open before it. */
    fun cancelCrop() {
        val ed = editor ?: return
        leaveCrop(ed)
        // taken back for good: Redo doesn't bring a cancelled crop back
        discardMode(ed)
    }

    private fun leaveCrop(ed: EditorState) {
        ed.tool = ed.toolBeforeCrop
        ed.toolBeforeCrop = null
        ed.crop = null
        ed.pendingCrop = null
        when (ed.tool) {
            MainTool.MASKING -> refreshMasks()
            MainTool.REMOVE -> refreshSpots()
            else -> {}
        }
    }

    /** A crop command (`crop.reset`, `photo.rotateLeft`…) on the photo: one step Cancel can take back. */
    fun cropCommand(id: String, params: JSONObject = JSONObject(), photo: Boolean = false) {
        val ed = editor ?: return
        if (photo) params.put("ids", JSONArray().put(ed.photoId.toLong()))
        // a turn, flip or Upright changes the picture under the frame: the new frame waits for it
        if (!photo && id != "geometry.upright") {
            edit(after = { refreshCrop() }) { it.execute(id, params.toString()) }
            return
        }
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute(id, params.toString()); snapshot(ed.photoId) to cropFrame(ed.photoId) } }
                .onSuccess { (snap, frame) ->
                    ed.update(snap)
                    requestView()
                    if (ed.tool == MainTool.CROP) {
                        ed.pendingCrop = frame
                        ed.pendingCropSeq = requestSeq
                    }
                }
                .onFailure { say(it.message) }
        }
    }

    /** The waiting crop frame, once the render it waits for (request [seq] or later) is in. */
    private fun takePendingCrop(ed: EditorState, seq: Long) {
        val frame = ed.pendingCrop ?: return
        if (seq < ed.pendingCropSeq) return
        ed.pendingCrop = null
        if (ed.tool == MainTool.CROP) ed.crop = frame
    }

    /** [level]: straighten back to 0° too (the Original tile, as the reference's). */
    fun setCropAspect(aspect: String, level: Boolean = false) {
        editor?.cropFormat = null
        cropCommand("crop.aspect", JSONObject().put("aspect", aspect).put("level", level))
    }

    /** A social format's shape, the way round it has (a story is upright on any photo). */
    fun setCropFormat(format: CropFormat) {
        editor?.cropFormat = format.id
        cropCommand("crop.aspect", JSONObject().put("aspect", JSONArray().put(format.w).put(format.h)).put("asGiven", true))
    }

    /** A finger takes a crop handle: what it does until it lifts is one step. */
    fun beginCropDrag() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = "crop"
        viewModelScope.launch { runCatching { e.command { beginAdjustment("Crop") } } }
    }

    /** The crop while a handle moves ([rect]: `x0, y0, x1, y1` in the straightened frame). */
    fun dragCrop(rect: DoubleArray) {
        val e = engine ?: return
        val params = JSONObject().put("rect", JSONArray(rect.toList())).toString()
        viewModelScope.launch { runCatching { e.command { execute("crop.set", params) } } }
    }

    fun endCropDrag() {
        val ed = editor ?: return
        val e = engine ?: return
        ed.adjusting = null
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) to cropFrame(ed.photoId) } }.onSuccess { (snap, frame) ->
                ed.update(snap)
                ed.crop = frame
            }
        }
    }

    /** An Edit section: Auto applies at once, Profiles opens its mode, the others their panel (again: closes it). */
    fun selectSection(section: EditSection) {
        val ed = editor ?: return
        ed.sub = null
        ed.pickingWb = false
        ed.targeting = null
        when (section) {
            EditSection.AUTO -> edit(after = { say("Auto settings applied") }) { it.execute("develop.auto", "{}") }
            EditSection.PROFILES -> {
                ed.profileFrom = ed.state?.profile ?: "lc.color"
                ed.section = section
                markMode(ed)
            }
            else -> ed.section = if (ed.section == section) null else section
        }
    }

    /** Leave Profiles keeping the profile chosen. */
    fun applyProfiles() {
        val ed = editor ?: return
        ed.profileFrom = null
        ed.section = null
        ed.modeMark = null
    }

    /** Leave Profiles going back to the profile it started with (the profiles tried are taken back for good). */
    fun cancelProfiles() {
        val ed = editor ?: return
        ed.profileFrom = null
        ed.section = null
        discardMode(ed)
    }

    /** Note where the history is as a mode (Crop, Profiles) opens, for its Cancel. */
    private fun markMode(ed: EditorState) {
        val e = engine ?: return
        ed.modeMark = null
        // queued before any of the mode's own commands, so it is read first
        viewModelScope.launch { runCatching { e.command { historyDepth() } }.onSuccess { ed.modeMark = it } }
    }

    /** A mode's Cancel: every step since it opened, taken back for good (not left on Redo). */
    private fun discardMode(ed: EditorState) {
        val mark = ed.modeMark
        ed.modeMark = null
        if (mark == null) {
            requestView()
            return
        }
        edit(after = ::refreshToolState) { s -> s.discardTo(mark) }
    }

    fun toggleBlackAndWhite() = edit { it.execute("develop.treatment", "{}") }

    /** Settings were copied (the editor's Paste Settings can be used). */
    var hasCopiedSettings by mutableStateOf(false)
        private set

    /** Copy Settings: the edited photo's settings [groups] (the chosen ones are kept for next time). */
    fun copySettings(groups: Set<String> = copyGroups) {
        copyGroups = groups
        edit(render = false, after = {
            hasCopiedSettings = true
            say("Settings copied")
        }) { it.execute("develop.copy", JSONObject().put("groups", JSONArray(groups.toList())).toString()) }
    }

    fun pasteSettings() = edit(after = ::refreshToolState) { it.execute("develop.paste", "{}") }

    /** The edited photo's versions, newest first (Versions). */
    var versions by mutableStateOf<List<VersionInfo>>(emptyList())
        private set

    fun loadVersions() {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching { e.command { execute("photo.inspect", JSONObject().put("id", ed.photoId.toLong()).toString()) } }
                .onSuccess { if (editor === ed) versions = parseVersions(it) }
                .onFailure { say(it.message) }
        }
    }

    private fun versionCommand(command: String, params: JSONObject, render: Boolean = false, after: (() -> Unit)? = null) =
        edit(render = render, after = {
            loadVersions()
            after?.invoke()
        }) { it.execute(command, params.toString()) }

    fun createVersion(name: String) = versionCommand("version.create", JSONObject().put("name", name.trim().ifEmpty { "Version" })) { say("Version created") }

    /** The edited photo's settings become version [index]'s (one undo step). */
    fun restoreVersion(index: Int) = versionCommand("version.restore", JSONObject().put("index", index), render = true)

    fun renameVersion(index: Int, name: String) {
        if (name.isBlank()) return
        versionCommand("version.rename", JSONObject().put("index", index).put("name", name.trim()))
    }

    fun updateVersion(index: Int) = versionCommand("version.update", JSONObject().put("index", index)) { say("Version updated") }

    fun deleteVersion(index: Int) = versionCommand("version.delete", JSONObject().put("index", index))

    /** Versions' Delete: all autosaves ([auto]) or all named versions, as one undo step each. */
    fun deleteVersions(auto: Boolean) {
        // from the last, so the earlier indexes stay right
        val doomed = versions.filter { it.auto == auto }.map { it.index }.sortedDescending()
        if (doomed.isEmpty()) return
        edit(render = false, after = { loadVersions() }) { s -> doomed.forEach { i -> s.execute("version.delete", JSONObject().put("index", i).toString()) } }
    }

    /** The edited photo with whole develop [settings] (a version's), long edge [edge]. */
    suspend fun settingsPreview(settings: String, edge: Int): ImageBitmap? {
        val ed = editor ?: return null
        val e = engine ?: return null
        return withContext(e.looks) {
            runCatching { e.session.renderResetThumbnail(ed.photoId, "import", settings, edge.toUInt()).toImageBitmap() }.getOrNull()
        }
    }

    /** The settings groups the edited photo has changed (Copy Settings' "Modified"). */
    suspend fun modifiedGroups(): Set<String> {
        val ed = editor ?: return emptySet()
        val e = engine ?: return emptySet()
        return withContext(e.looks) { runCatching { e.session.modifiedGroups(ed.photoId).toSet() }.getOrDefault(emptySet()) }
    }

    /** The groups Copy Settings chose last (the engine's default ones at first). */
    var copyGroups by mutableStateOf(defaultSettingsGroups)
        private set

    /** Apply from previous: [basics] the previous photo's basic adjustments only (white balance, tone, presence), else all it copies. */
    fun applyFromPrevious(basics: Boolean = false) = edit(after = ::refreshToolState) {
        it.execute("develop.pastePrevious", if (basics) JSONObject().put("groups", JSONArray(BASIC_GROUPS)).toString() else "{}")
    }

    /** The edited photo with Apply from previous's [basics] or all applied (null: no previous photo). */
    suspend fun previousPreview(basics: Boolean): ImageBitmap? {
        val ed = editor ?: return null
        val e = engine ?: return null
        return withContext(e.looks) {
            runCatching { e.session.renderPreviousThumbnail(ed.photoId, if (basics) BASIC_GROUPS else emptyList(), CHOICE_EDGE.toUInt()).toImageBitmap() }.getOrNull()
        }
    }

    /**
     * Reset, as the reference offers it: [to] `adjustments` (the edits, keeping the crop), `all`, `import`
     * (as the photo came in) or `open` (as the editor opened it). One undo step.
     */
    fun resetTo(to: String) {
        val ed = editor ?: return
        if (to == "open") {
            val settings = ed.openSettings ?: return
            edit(after = ::refreshToolState) { it.execute("develop.merge", JSONObject().put("settings", JSONObject(settings)).put("label", "Reset to Open").toString()) }
        } else {
            // the open tool's masks, spots or crop follow (Reset takes them too)
            edit(after = ::refreshToolState) { it.execute("develop.reset", JSONObject().put("to", if (to == "all") "default" else to).toString()) }
        }
    }

    /** The edited photo as Reset's choice [to] would leave it. */
    suspend fun resetPreview(to: String): ImageBitmap? {
        val ed = editor ?: return null
        val e = engine ?: return null
        val settings = if (to == "open") ed.openSettings ?: return null else null
        return withContext(e.looks) {
            runCatching { e.session.renderResetThumbnail(ed.photoId, if (to == "all") "default" else to, settings, CHOICE_EDGE.toUInt()).toImageBitmap() }.getOrNull()
        }
    }

    /** Save the photo's settings [groups] as the user's preset [name] in preset group [group]. */
    fun createPreset(name: String, group: String = "User Presets", groups: Set<String> = defaultSettingsGroups) {
        val e = engine ?: return
        if (name.isBlank()) return
        edit(render = false, after = {
            viewModelScope.launch { runCatching { e.command { presets() } }.onSuccess { presets = it } }
            say("Preset “${name.trim()}” created")
        }) { it.execute("preset.create", JSONObject().put("name", name.trim()).put("group", group).put("groups", JSONArray(groups.toList())).toString()) }
    }

    fun onControlChange(control: DevelopControl, value: Double) {
        val ed = editor ?: return
        val e = engine ?: return
        ed.controls = ed.controls.map { if (it.id == control.id) it.copy(value = value) else it }
        val begin = ed.adjusting != control.id
        ed.adjusting = control.id
        val straighten = control.id == "crop.angle" && ed.tool == MainTool.CROP
        // a turn is one gesture: each move goes from the crop it started with, so the crop keeps its place
        // on the photo and grows back as the angle returns, instead of shrinking with every move
        if (straighten && begin) ed.straightenFrom = ed.crop?.let { it.rect.toDoubleArray() to it.angle }
        val from = if (straighten) ed.straightenFrom else null
        viewModelScope.launch {
            runCatching {
                e.command {
                    if (begin) beginAdjustment(control.label)
                    if (straighten) {
                        val params = JSONObject().put("angle", value)
                        from?.let { (rect, angle) -> params.put("from", JSONArray(rect.toList())).put("fromAngle", angle) }
                        execute("crop.straighten", params.toString())
                        cropFrame(ed.photoId)
                    } else {
                        setControl(control.id, value)
                        null
                    }
                }
            }
                .onSuccess { frame ->
                    if (frame != null) ed.crop = frame
                    // the crop view doesn't change with the crop's angle
                    if (!straighten) requestView(draft = true)
                    if (ed.sub == "curve" && control.id.startsWith("curve.")) refreshCurve()
                }
                .onFailure { say(it.message) }
        }
    }

    fun onControlChangeFinished() {
        val ed = editor ?: return
        val e = engine ?: return
        if (ed.adjusting == null) return
        ed.adjusting = null
        val cropping = ed.tool == MainTool.CROP
        viewModelScope.launch {
            runCatching { e.command { endAdjustment(); snapshot(ed.photoId) to (if (cropping) cropFrame(ed.photoId) else null) } }.onSuccess { (snap, frame) ->
                ed.update(snap)
                if (frame != null) ed.crop = frame
            }
            requestView()
        }
    }

    /** Run [block] on the engine, then read back what an edit changes (and render, if [render]). */
    private fun edit(render: Boolean = true, after: (() -> Unit)? = null, block: (MobileSession) -> Unit) {
        val ed = editor ?: return
        val e = engine ?: return
        viewModelScope.launch {
            runCatching {
                e.command {
                    block(this)
                    snapshot(ed.photoId)
                }
            }
                .onSuccess {
                    ed.update(it)
                    if (render) requestView()
                    after?.invoke()
                }
                .onFailure { say(it.message) }
        }
    }

    fun resetControl(control: DevelopControl) {
        edit(after = { refreshToolState() }) { it.resetControl(control.id) }
    }

    fun undo() = edit(after = ::refreshToolState) { it.undo() }

    fun redo() = edit(after = ::refreshToolState) { it.redo() }

    /** What the open tool shows of the photo (its masks, spots or crop), read again after an undo or redo. */
    private fun refreshToolState() {
        if (editor?.sub == "curve") refreshCurve()
        when (editor?.tool) {
            MainTool.MASKING -> refreshMasks()
            MainTool.REMOVE -> refreshSpots()
            MainTool.CROP -> refreshCrop()
            else -> {}
        }
    }

    fun resetAll() = edit { it.execute("develop.reset", "{}") }

    /** The undo card's Reset Geometry: develop section [section] back to its defaults (one step). */
    fun resetSectionNamed(section: String) = edit { it.execute("develop.resetSection", JSONObject().put("section", section).toString()) }

    /** The undo card's Reset Masking: every mask gone (one step). */
    fun deleteAllMasks() = edit(after = { refreshMasks() }) { it.execute("mask.deleteAll", "{}") }

    /** The undo card's Reset Remove: every spot removed, healed or cloned gone (one step). */
    fun deleteAllSpots() = edit(after = { refreshSpots() }) {
        it.execute("develop.merge", JSONObject().put("settings", JSONObject().put("spots", JSONArray())).put("label", "Reset Remove").toString())
    }

    fun setWhiteBalance(mode: String) = edit { it.execute("develop.wb", JSONObject().put("mode", mode).toString()) }

    /** White balance from a neutral spot of the photo, at [x], [y] across and down it (0–1). */
    fun pickWhiteBalance(x: Float, y: Float) {
        val ed = editor ?: return
        ed.pickingWb = false
        edit { s ->
            val (px, py) = s.viewGeometry(ed.photoId)?.let { g -> Affine.of(g.toPhoto)?.apply(x.toDouble(), y.toDouble()) } ?: (x.toDouble() to y.toDouble())
            s.execute("develop.wbPick", JSONObject().put("x", px).put("y", py).toString())
        }
    }

    fun setVignetteStyle(style: String) = edit {
        it.execute("develop.merge", JSONObject().put("settings", JSONObject().put("vignette", JSONObject().put("style", style))).put("label", "Vignette Style").toString())
    }

    /** An Optics switch: `remove_ca` or `lens_profile`. */
    fun setOptics(key: String, on: Boolean, label: String) = edit {
        it.execute("develop.merge", JSONObject().put("settings", JSONObject().put("optics", JSONObject().put(key, on))).put("label", label).toString())
    }

    fun setProfile(profile: String) = edit { it.execute("develop.profile", JSONObject().put("id", profile).toString()) }

    fun applyPreset(preset: PresetInfo) {
        val ed = editor ?: return
        ed.lastPreset = preset
        ed.presetAmount = 100f
        edit(after = { say("Preset: ${preset.name}") }) { it.applyPreset(preset.id, 100.0) }
    }

    /** The last applied preset at [amount] percent: taken back and applied again, as the desktop does. */
    fun setPresetAmount(amount: Float) {
        val ed = editor ?: return
        val preset = ed.lastPreset ?: return
        ed.presetAmount = amount
        edit { s ->
            if (s.history().undo == "Preset: ${preset.name}") s.undo()
            s.applyPreset(preset.id, amount.toDouble())
        }
    }

    /** Speed review's swipe on Info and rating: the next (+1) or previous (−1) photo, its page still open. */
    fun infoStep(step: Int) {
        val ed = editor ?: return
        if (ed.device != null || neighbourInfo(step) == null) return
        showNeighbour(step)
        editor?.infoPage = true
    }

    private fun ids(): JSONArray = JSONArray().put(editor?.photoId?.toLong() ?: 0L)

    fun setRating(stars: Int) = edit(render = false) {
        it.execute("photo.rate", JSONObject().put("ids", ids()).put("rating", stars).toString())
    }

    fun setFlag(flag: PhotoFlag) = edit(render = false) {
        val name = when (flag) {
            PhotoFlag.PICK -> "pick"
            PhotoFlag.REJECT -> "reject"
            PhotoFlag.UNFLAGGED -> "none"
        }
        it.execute("photo.flag", JSONObject().put("flag", name).put("ids", ids()).toString())
    }

    /** Move the photo to Recently Deleted (its file stays where it is); Undo brings it back. */
    fun removePhoto() {
        val ed = editor ?: return
        val e = engine ?: return
        val params = JSONObject().put("ids", ids()).toString()
        editor = null
        viewModelScope.launch {
            val result = runCatching {
                e.command {
                    releaseView()
                    this.closeEditor()
                    execute("photo.delete", params)
                    historyDepth()
                }
            }
            refreshPhotos()
            result
                .onSuccess { depth ->
                    sayUndoable("Removed ${ed.info.fileName}", depth)
                }
                .onFailure { say(it.message) }
        }
    }

    /**
     * Render the photo with its edits as [choice] says (the choice is kept for next time) into
     * Pictures/LightCraft; with [share], to the system's share sheet instead, leaving no copy in
     * the phone's photos.
     */
    fun export(choice: ExportChoice, share: Boolean = false) {
        val ed = editor ?: return
        val e = engine ?: return
        if (ed.exporting) return
        ed.exporting = true
        exportChoice = choice
        prefs.edit().putString("export", choice.prefs()).apply()
        val options = choice.json()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (needsCloudOriginal(ed.photoId, choice)) {
                        ed.downloading = "Downloading the original…"
                        try {
                            e.session.cloudDownloadOriginal(ed.photoId)
                        } finally {
                            ed.downloading = null
                        }
                    }
                    val file = e.session.export(ed.photoId, options)
                    val app = getApplication<Application>()
                    (if (share) PhotoFiles.shareable(app, file) else PhotoFiles.saveToPictures(app, file)) to file
                }
            }
            ed.exporting = false
            result
                .onSuccess { (uri, file) ->
                    if (share) {
                        PhotoFiles.share(getApplication(), uri, file.mimeType)
                    } else {
                        say("Saved ${file.fileName} (${file.width}×${file.height}) to Pictures/LightCraft", "Share") {
                            PhotoFiles.share(getApplication(), uri, file.mimeType)
                        }
                    }
                }
                .onFailure { say("Export failed: ${it.message}") }
        }
    }

    companion object {
        /** The app's log tag. */
        const val TAG = "Lightcraft"

        /** Long edge the grid asks for (the engine caches thumbnails at 128, 256, 384 and 512 px). */
        const val THUMB_EDGE = 384

        /** The grid's photo sizes, against the middle one, as the reference's pinch steps (0.15, 0.22, 0.30, 0.45, 0.90 of its scale). */
        val GRID_SIZES = listOf(0.5f, 0.733f, 1f, 1.5f, 3f)

        /** The most photos the system is asked to delete at once. */
        const val DELETE_BATCH = 500

        /** The most often the app syncs with the cloud on its own (ms). */
        const val CLOUD_SYNC_EVERY = 15_000L

        /** How long after leaving a photo its edits are sent (ms). */
        const val CLOUD_PUSH_DELAY = 5_000L

        /** The long side of a smart preview (px): exports up to this size need no original. */
        const val CLOUD_PREVIEW_EDGE = 2048

        /** Long edge of preset and profile previews. */
        const val LOOK_EDGE = 256
    }
}
