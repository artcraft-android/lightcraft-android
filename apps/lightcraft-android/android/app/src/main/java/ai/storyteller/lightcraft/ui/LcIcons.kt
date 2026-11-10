package ai.storyteller.lightcraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** An icon of either set: LightCraft's own ([LcIcon]) or Google's Material Icons ([MdIcon]). */
sealed interface Glyph

/** [glyph] at [size] (by default 22 dp for LightCraft's, 24 dp for Material's, their own sizes). */
@Composable
fun GlyphIcon(glyph: Glyph, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Lc.icon, size: Dp? = null) {
    when (glyph) {
        is LcIcon -> LcIcon(glyph, contentDescription, modifier, tint, size ?: 22.dp)
        is MdIcon -> MdIcon(glyph, contentDescription, modifier, tint, size ?: 24.dp)
    }
}

/**
 * LightCraft's own icons, drawn the way the desktop draws them (`crates/ui-egui/src/icons.rs`): in
 * code, on a 20×20 grid, with 1.5-unit strokes and round caps. Original work, not derived from any
 * icon set. The entries after [Sort] are drawn for the phone in the same style; a few are solid
 * shapes with parts cut out, as the reference's are where its icons are its own.
 */
enum class LcIcon : Glyph {
    Back, ChevronLeft, ChevronRight, ChevronDown, Share, More, Presets, Star, StarFilled, FlagPick, FlagReject, Curve, Radial,
    BeforeAfter, Info, Plus, Trash, Edited, Sliders, Check, Close, Crop, Photos, Folder, Picker, ProfileGrid, Invert, Versions,
    Filter, Sort,
    Undo, Redo, Auto, Light, Color, Effects, Detail, Optics, Geometry, Calibration, Menu, AddPhoto, Download,
    Pencil, PencilOff, Import, Search, Camera, Person, Tag, Film, Calendar, GridSquare, GridJustified, Segments, SelectBox,
    AtLeast, Exactly, AtMost, FlagNone, FlagPickSolid, FlagRejectSolid, Gear, Phone, Help, Az, RotateLeft, RotateRight, FlipH, FlipV, Heal,
    Actions, FixAngle, Clone, HealCross, Level, Vertical, FullUpright, Guided, Original, FreeAspect, Target, Play, Minus, PhotoStack,
    DevicePhotos, Library,

    // the editor's tools and sections that no Material icon fits: solid, like the reference's there
    ToolPresets, ToolMasking, ToolRemove, SectionDetail, SectionOptics,

    // the panels' buttons: a curve between its end points, three wheels, a dot that moves any way, a curve's bands
    CurveLine, Grading, TargetMove, Parametric,

    // the editor's menu: how it shows, a new preset, settings copied, pasted, taken from the last photo
    ViewOptions, CreatePreset, CopySettings, PasteSettings, ApplyPrevious,

    // Edit's Auto: a photo with a sparkle at its corner
    SectionAuto,

    // Crop's: the aspect locked or not, the ratios, the crop turned the other way round
    Lock, Unlock, Ratios, SwapAspect,

    // Actions' Fix angle: a grid seen at an angle (the photo's lines set straight)
    AngleGrid,

    // Masking's top bar: show or hide the mask over the photo (struck either way, as the reference's)
    MaskOverlay,

    // Edit's Effects: a frame darkened towards its middle's edge (a vignette)
    SectionEffects,
}

/** An icon; [weight] scales its strokes (thinner for the large ones, as an illustration). */
@Composable
fun LcIcon(icon: LcIcon, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Lc.icon, size: Dp = 22.dp, weight: Float = 1f) {
    val described = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    // its own layer: cut-outs clear the icon's pixels, not what's under it
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.then(described)) { Pen(this, tint, weight).draw(icon) }
}

private class Pen(private val scope: DrawScope, private val color: Color, weight: Float) {
    private val side = min(scope.size.width, scope.size.height)
    private val k = side / 20f
    private val ox = (scope.size.width - side) / 2f
    private val oy = (scope.size.height - side) / 2f
    private val stroke = Stroke(width = max(1.5f * k * weight, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)

    private fun pt(x: Float, y: Float) = Offset(ox + x * k, oy + y * k)

    private fun path(points: List<Pair<Float, Float>>, close: Boolean) = Path().apply {
        points.forEachIndexed { i, (x, y) ->
            val o = pt(x, y)
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
        if (close) close()
    }

    fun line(vararg p: Pair<Float, Float>) = scope.drawPath(path(p.toList(), false), color, style = stroke)

    fun closed(vararg p: Pair<Float, Float>) = scope.drawPath(path(p.toList(), true), color, style = stroke)

    fun fill(points: List<Pair<Float, Float>>) = scope.drawPath(path(points, true), color)

    fun circle(x: Float, y: Float, r: Float, c: Color = color) = scope.drawCircle(c, r * k, pt(x, y), style = stroke)

    fun dot(x: Float, y: Float, r: Float) = scope.drawCircle(color, r * k, pt(x, y))

    fun rect(x0: Float, y0: Float, x1: Float, y1: Float, round: Float) =
        scope.drawRoundRect(color, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), CornerRadius(round * k), style = stroke)

    fun rectFill(x0: Float, y0: Float, x1: Float, y1: Float, round: Float) =
        scope.drawRoundRect(color, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), CornerRadius(round * k))

    // cut-outs: clear what's drawn so far there (the icon's own layer)
    fun cutLine(vararg p: Pair<Float, Float>) = scope.drawPath(path(p.toList(), false), Color.Black, style = stroke, blendMode = BlendMode.Clear)

    fun cutRect(x0: Float, y0: Float, x1: Float, y1: Float, round: Float) =
        scope.drawRoundRect(Color.Black, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), CornerRadius(round * k), blendMode = BlendMode.Clear)

    fun cutDot(x: Float, y: Float, r: Float) = scope.drawCircle(Color.Black, r * k, pt(x, y), blendMode = BlendMode.Clear)

    fun cutFill(points: List<Pair<Float, Float>>) = scope.drawPath(path(points, true), Color.Black, blendMode = BlendMode.Clear)

    fun ovalFill(x0: Float, y0: Float, x1: Float, y1: Float) = scope.drawOval(color, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k))

    /**
     * Optics, as the reference draws it: a lens ring thick on its near side, the crescent of light
     * beside it, and the element inside. Traced from the reference's icon, on the 20 grid.
     */
    private fun optics() {
        // the barrel's near rim
        fill(
            listOf(
                12.93f to 17.00f, 9.60f to 16.92f, 8.66f to 16.53f, 7.43f to 14.90f,
                6.72f to 12.85f, 6.48f to 11.19f, 6.48f to 8.89f, 6.88f to 6.60f,
                7.35f to 5.25f, 8.06f to 4.07f, 8.97f to 3.24f, 9.76f to 3.00f,
                13.95f to 3.00f, 14.67f to 3.24f, 15.58f to 4.07f, 15.89f to 4.62f,
                16.60f to 6.68f, 16.92f to 8.50f, 17.00f to 10.47f, 16.84f to 12.29f,
                16.45f to 13.95f, 15.81f to 15.38f, 14.82f to 16.68f, 14.19f to 16.92f,
            ),
        )
        // its counter
        cutFill(
            listOf(
                14.15f to 16.17f, 14.43f to 16.13f, 15.02f to 15.46f, 15.89f to 13.48f,
                16.21f to 11.82f, 16.29f to 9.60f, 15.97f to 6.84f, 15.34f to 4.94f,
                15.02f to 4.38f, 14.35f to 3.71f, 13.80f to 3.63f, 12.73f to 4.78f,
                12.02f to 6.52f, 11.70f to 8.50f, 11.70f to 11.66f, 12.02f to 13.48f,
                12.65f to 15.14f, 13.40f to 16.05f,
            ),
        )
        // the crescent of light beside it
        fill(
            listOf(
                6.60f to 15.18f, 4.94f to 15.10f, 4.03f to 14.35f, 3.24f to 12.61f,
                3.00f to 11.19f, 3.00f to 8.73f, 3.16f to 7.71f, 3.55f to 6.52f,
                4.11f to 5.57f, 4.78f to 4.90f, 5.65f to 4.66f, 6.64f to 4.70f,
                5.77f to 6.84f, 5.45f to 8.89f, 5.45f to 11.11f, 5.61f to 12.37f,
            ),
        )
        // the glass inside
        fill(
            listOf(
                13.32f to 14.07f, 13.05f to 13.88f, 12.57f to 11.19f, 12.65f to 7.86f,
                13.24f to 5.85f, 13.92f to 6.36f, 14.39f to 7.07f, 14.79f to 7.86f,
                15.10f to 9.05f, 15.10f to 10.95f, 14.86f to 11.90f, 14.15f to 13.24f,
            ),
        )
    }

    /** A checkerboard of [cell]-unit squares over the grid, only those whose middle [inside] accepts. */
    fun checker(cell: Float, inside: (Float, Float) -> Boolean) {
        var row = 0
        var y = 0f
        while (y < 20f) {
            var col = 0
            var x = 0f
            while (x < 20f) {
                if ((row + col) % 2 == 0 && inside(x + cell / 2, y + cell / 2)) rectFill(x, y, x + cell, y + cell, 0f)
                x += cell
                col++
            }
            y += cell
            row++
        }
    }

    /** A solid disc set off by a gap, for what's drawn in it as cut-outs (a menu icon's plus or arrow). */
    fun badge(x: Float, y: Float) {
        cutDot(x, y, 5.6f)
        dot(x, y, 4.5f)
    }

    fun cutOval(x0: Float, y0: Float, x1: Float, y1: Float) = scope.drawOval(Color.Black, pt(x0, y0), Size((x1 - x0) * k, (y1 - y0) * k), blendMode = BlendMode.Clear)

    /** Arc from angle [a0] to [a1] in degrees (y down, as egui), as 24 segments like the desktop. */
    fun arc(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) = line(*arcPoints(cx, cy, r, a0, a1).toTypedArray())

    private fun arcPoints(cx: Float, cy: Float, r: Float, a0: Float, a1: Float) = (0..24).map { i ->
        val a = Math.toRadians((a0 + (a1 - a0) * i / 24f).toDouble())
        (cx + r * cos(a).toFloat()) to (cy + r * sin(a).toFloat())
    }

    /** A four-pointed sparkle, its waist a quarter of its reach. */
    private fun sparkle(cx: Float, cy: Float, r: Float) = (0 until 8).map { i ->
        val a = Math.toRadians(-90.0 + i * 45.0)
        val rr = if (i % 2 == 0) r else r * 0.25f
        (cx + rr * cos(a).toFloat()) to (cy + rr * sin(a).toFloat())
    }

    private fun starPoints(cx: Float, cy: Float, r: Float) = (0 until 10).map { i ->
        val a = Math.toRadians(-90.0 + i * 36.0)
        val rr = if (i % 2 == 0) r else r * 0.45f
        (cx + rr * cos(a).toFloat()) to (cy + rr * sin(a).toFloat())
    }

    fun draw(icon: LcIcon) {
        when (icon) {
            LcIcon.Back -> {
                line(16f to 10f, 4f to 10f)
                line(9f to 5f, 4f to 10f, 9f to 15f)
            }
            LcIcon.ChevronLeft -> line(12.5f to 5f, 7.5f to 10f, 12.5f to 15f)
            LcIcon.ChevronRight -> line(7.5f to 5f, 12.5f to 10f, 7.5f to 15f)
            LcIcon.ChevronDown -> line(5f to 7.5f, 10f to 12.5f, 15f to 7.5f)
            LcIcon.Share -> {
                line(6f to 8f, 4f to 8f, 4f to 17f, 16f to 17f, 16f to 8f, 14f to 8f)
                line(10f to 12f, 10f to 2.5f)
                line(6.5f to 6f, 10f to 2.5f, 13.5f to 6f)
            }
            LcIcon.More -> listOf(5f, 10f, 15f).forEach { dot(it, 10f, 1.3f) }
            LcIcon.Presets -> {
                circle(8f, 8f, 5f)
                circle(12f, 12f, 5f)
            }
            LcIcon.Star -> closed(*starPoints(10f, 10.5f, 7.5f).toTypedArray())
            LcIcon.StarFilled -> fill(starPoints(10f, 10.5f, 7.5f))
            LcIcon.FlagPick -> {
                line(5f to 17f, 5f to 3.5f)
                closed(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f)
            }
            LcIcon.FlagReject -> {
                line(5f to 17f, 5f to 3.5f)
                closed(5f to 3.5f, 15f to 3.5f, 12.5f to 7f, 15f to 10.5f, 5f to 10.5f)
                line(8f to 5f, 12f to 9f)
                line(12f to 5f, 8f to 9f)
            }
            LcIcon.Curve -> {
                rect(3f, 3f, 17f, 17f, 1.5f)
                line(*(0..12).map { i ->
                    val t = i / 12f
                    val y = t * t * (3f - 2f * t)
                    (4.5f + 11f * t) to (15.5f - 11f * y)
                }.toTypedArray())
            }
            LcIcon.Radial -> {
                circle(10f, 10f, 7f)
                circle(10f, 10f, 3.5f, color.copy(alpha = color.alpha * 0.5f))
            }
            LcIcon.BeforeAfter -> {
                rect(3f, 4f, 17f, 16f, 1f)
                line(10f to 2.5f, 10f to 17.5f)
                rectFill(10f, 4f, 17f, 16f, 0.5f)
            }
            LcIcon.Info -> {
                circle(10f, 10f, 7.5f)
                line(10f to 9f, 10f to 14f)
                dot(10f, 6.3f, 0.9f)
            }
            LcIcon.Plus -> {
                line(10f to 4f, 10f to 16f)
                line(4f to 10f, 16f to 10f)
            }
            LcIcon.Trash -> {
                // a solid bin with three slits, its lid and handle
                rectFill(3f, 4.3f, 17f, 6f, 0.5f)
                rectFill(8f, 2.6f, 12f, 4.6f, 0.7f)
                fill(listOf(4.8f to 7f, 15.2f to 7f, 14.2f to 17.5f, 5.8f to 17.5f))
                cutLine(7.9f to 9.5f, 8.2f to 15f)
                cutLine(10f to 9.5f, 10f to 15f)
                cutLine(12.1f to 9.5f, 11.8f to 15f)
            }
            LcIcon.Edited -> {
                circle(10f, 10f, 7f)
                line(7f to 13f, 13f to 7f)
            }
            LcIcon.Sliders -> listOf(5f to 12f, 10f to 7f, 15f to 13f).forEach { (y, x) ->
                line(3f to y, (x - 2f) to y)
                line((x + 2f) to y, 17f to y)
                circle(x, y, 2f)
            }
            LcIcon.Check -> line(4f to 10.5f, 8.5f to 15f, 16f to 5.5f)
            LcIcon.Close -> {
                line(5f to 5f, 15f to 15f)
                line(15f to 5f, 5f to 15f)
            }
            LcIcon.Crop -> {
                line(6f to 2.5f, 6f to 14f, 17.5f to 14f)
                line(2.5f to 6f, 14f to 6f, 14f to 17.5f)
                line(15.5f to 2f, 17.5f to 4f, 15.5f to 6f)
            }
            LcIcon.Photos -> {
                rect(2.5f, 4f, 17.5f, 16f, 1.5f)
                line(2.5f to 13.5f, 7f to 9.5f, 11f to 13f, 13.5f to 11f, 17.5f to 14.5f)
                dot(13.5f, 7.5f, 1.3f)
            }
            LcIcon.Folder -> closed(2.5f to 5f, 8f to 5f, 9.5f to 7f, 17.5f to 7f, 17.5f to 15.5f, 2.5f to 15.5f)
            LcIcon.Picker -> {
                line(4f to 16f, 11.5f to 8.5f)
                closed(11f to 5f, 15f to 9f, 17f to 7f, 13f to 3f)
            }
            LcIcon.ProfileGrid -> {
                rectFill(3.5f, 3.5f, 9f, 9f, 0.5f)
                rectFill(11f, 3.5f, 16.5f, 9f, 0.5f)
                rectFill(3.5f, 11f, 9f, 16.5f, 0.5f)
                circle(14f, 14f, 2.5f)
                line(15.8f to 15.8f, 17.5f to 17.5f)
            }
            LcIcon.Invert -> {
                circle(10f, 10f, 7f)
                fill(arcPoints(10f, 10f, 7f, 90f, 270f))
            }
            LcIcon.Versions -> {
                rect(5f, 3f, 17f, 13f, 1.5f)
                line(3f to 6f, 3f to 16f, 14f to 16f)
            }
            LcIcon.Filter -> closed(3f to 4f, 17f to 4f, 11.5f to 10.5f, 11.5f to 16f, 8.5f to 14.5f, 8.5f to 10.5f)
            LcIcon.Sort -> {
                // shorter lines down, and the order's arrow beside them
                line(2.75f to 5.25f, 11f to 5.25f)
                line(2.75f to 10f, 9f to 10f)
                line(2.75f to 14.75f, 7f to 14.75f)
                line(14.75f to 5f, 14.75f to 14.9f)
                line(12.65f to 12.8f, 14.75f to 14.9f, 16.85f to 12.8f)
            }
            // --- drawn for the phone, in the same style
            LcIcon.Undo -> {
                line(7.5f to 2.5f, 4f to 6f, 7.5f to 9.5f)
                line(4f to 6f, 12f to 6f)
                arc(12f, 11f, 5f, 270f, 450f)
                line(12f to 16f, 6f to 16f)
            }
            LcIcon.Redo -> {
                line(12.5f to 2.5f, 16f to 6f, 12.5f to 9.5f)
                line(16f to 6f, 8f to 6f)
                arc(8f, 11f, 5f, 270f, 90f)
                line(8f to 16f, 14f to 16f)
            }
            LcIcon.Auto -> {
                line(4f to 16f, 12f to 8f)
                line(14.5f to 2.5f, 14.5f to 8.5f)
                line(11.5f to 5.5f, 17.5f to 5.5f)
                dot(16.5f, 11f, 0.9f)
                dot(9f, 3.5f, 0.9f)
            }
            LcIcon.Light -> {
                circle(10f, 10f, 3.5f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val (c, s) = cos(a).toFloat() to sin(a).toFloat()
                    line((10f + 6f * c) to (10f + 6f * s), (10f + 8.5f * c) to (10f + 8.5f * s))
                }
            }
            LcIcon.Color -> {
                arc(10f, 12f, 5f, -30f, 210f)
                line(14.33f to 9.5f, 10f to 2.5f, 5.67f to 9.5f)
            }
            LcIcon.Effects -> {
                rect(2.5f, 4f, 17.5f, 16f, 1.5f)
                circle(10f, 10f, 3.5f)
            }
            LcIcon.Detail -> closed(10f to 3.5f, 17f to 16f, 3f to 16f)
            LcIcon.Optics -> optics()
            LcIcon.Geometry -> {
                closed(5.5f to 4f, 14.5f to 4f, 17.5f to 16f, 2.5f to 16f)
                line(10f to 4f, 10f to 16f)
                line(4f to 10f, 16f to 10f)
            }
            LcIcon.Calibration -> {
                circle(10f, 10f, 6f)
                line(10f to 2f, 10f to 6f)
                line(10f to 14f, 10f to 18f)
                line(2f to 10f, 6f to 10f)
                line(14f to 10f, 18f to 10f)
                dot(10f, 10f, 1f)
            }
            LcIcon.Menu -> listOf(5f, 10f, 15f).forEach { y -> line(3f to y, 17f to y) }
            LcIcon.AddPhoto -> {
                // a photo (a solid hill, a sun), a round plus over its corner
                rect(1f, 1.7f, 14.6f, 13.6f, 1.2f)
                fill(listOf(1f to 13.6f, 1f to 10.6f, 4.7f to 6.9f, 8.4f to 10.6f, 10.2f to 8.9f, 12.6f to 11.3f, 12.6f to 13.6f))
                dot(11f, 5f, 1.4f)
                cutDot(15f, 15f, 5.7f)
                dot(15f, 15f, 4.6f)
                cutLine(15f to 12.5f, 15f to 17.5f)
                cutLine(12.5f to 15f, 17.5f to 15f)
            }
            LcIcon.Download -> {
                line(10f to 3f, 10f to 13f)
                line(6f to 9.5f, 10f to 13.5f, 14f to 9.5f)
                line(4f to 16.5f, 16f to 16.5f)
            }
            LcIcon.Pencil -> {
                // a solid body, an open tip
                fill(listOf(4.2f to 11.95f, 14.8f to 1.35f, 17.8f to 4.35f, 7.2f to 14.95f))
                closed(2.7f to 16.45f, 4.2f to 11.95f, 7.2f to 14.95f)
            }
            LcIcon.PencilOff -> {
                fill(listOf(3.8f to 12.3f, 14.4f to 1.7f, 17.4f to 4.7f, 6.8f to 15.3f))
                closed(2.3f to 16.8f, 3.8f to 12.3f, 6.8f to 15.3f)
                cutLine(4.2f to 0.9f, 19.2f to 15.9f)
                line(2.6f to 1.7f, 17.4f to 16.5f)
            }
            LcIcon.Import -> {
                line(8.6f to 5.6f, 8.6f to 2.8f, 17.6f to 2.8f, 17.6f to 17.2f, 8.6f to 17.2f, 8.6f to 14.4f)
                line(1.4f to 10f, 12.6f to 10f)
                line(9.2f to 6.6f, 12.6f to 10f, 9.2f to 13.4f)
            }
            LcIcon.Search -> {
                circle(8.5f, 8.5f, 5f)
                line(12.2f to 12.2f, 16.5f to 16.5f)
            }
            LcIcon.Camera -> {
                closed(2.5f to 6.5f, 6.5f to 6.5f, 8f to 4f, 12f to 4f, 13.5f to 6.5f, 17.5f to 6.5f, 17.5f to 16f, 2.5f to 16f)
                circle(10f, 11f, 3f)
            }
            LcIcon.Person -> {
                circle(10f, 7f, 3.2f)
                line(*arcPoints(10f, 17f, 6f, 180f, 360f).toTypedArray())
            }
            LcIcon.Tag -> {
                closed(3f to 3f, 10f to 3f, 17f to 10f, 10f to 17f, 3f to 10f)
                dot(6.8f, 6.8f, 1.1f)
            }
            LcIcon.Film -> {
                // a solid strip, its sprocket holes and frame cut out
                rectFill(2.5f, 2.1f, 17.5f, 17.9f, 1.2f)
                listOf(4.3f, 8.1f, 11.9f, 15.7f).forEach { y ->
                    cutRect(3.6f, y - 0.95f, 5.4f, y + 0.95f, 0.3f)
                    cutRect(14.6f, y - 0.95f, 16.4f, y + 0.95f, 0.3f)
                }
                cutRect(6.8f, 3.4f, 13.2f, 16.6f, 0.6f)
            }
            LcIcon.Calendar -> {
                rect(3f, 4.5f, 17f, 17f, 1.5f)
                line(7f to 2.5f, 7f to 6f)
                line(13f to 2.5f, 13f to 6f)
                // its days
                for (r in 0..2) for (c in 0..3) dot(5.8f + c * 2.8f, 9.4f + r * 2.6f, 0.75f)
            }
            LcIcon.GridSquare -> {
                rectFill(3f, 3f, 9f, 9f, 0.5f)
                rectFill(11f, 3f, 17f, 9f, 0.5f)
                rectFill(3f, 11f, 9f, 17f, 0.5f)
                rectFill(11f, 11f, 17f, 17f, 0.5f)
            }
            LcIcon.GridJustified -> {
                rectFill(2.1f, 2.1f, 11.6f, 9.2f, 0.5f)
                rectFill(13.2f, 2.1f, 17.9f, 9.2f, 0.5f)
                rectFill(2.1f, 10.8f, 6.8f, 17.9f, 0.5f)
                rectFill(8.4f, 10.8f, 17.9f, 17.9f, 0.5f)
            }
            LcIcon.Segments -> {
                // a segment's heading over its rows
                dot(2.2f, 4f, 1.6f)
                line(5.6f to 4f, 18.6f to 4f)
                rectFill(0.7f, 8.2f, 19.3f, 12f, 0.8f)
                rectFill(0.7f, 14.1f, 19.3f, 17.9f, 0.8f)
            }
            LcIcon.SelectBox -> {
                // two cards, the front one solid with its check cut out
                rectFill(1.3f, 1.3f, 13.4f, 13.4f, 2.2f)
                cutRect(4.5f, 4.5f, 20f, 20f, 3f)
                rectFill(5.7f, 5.7f, 18.7f, 18.7f, 2.2f)
                cutLine(8.6f to 12.3f, 11.2f to 14.9f, 15.8f to 10.1f)
            }
            LcIcon.AtLeast -> {
                line(5.75f to 4.5f, 14.25f to 7.5f, 5.75f to 10.5f)
                line(5.75f to 13.5f, 14.25f to 13.5f)
            }
            LcIcon.Exactly -> {
                line(5.75f to 6.75f, 14.25f to 6.75f)
                line(5.75f to 11.25f, 14.25f to 11.25f)
            }
            LcIcon.AtMost -> {
                line(14.25f to 4.5f, 5.75f to 7.5f, 14.25f to 10.5f)
                line(5.75f to 13.5f, 14.25f to 13.5f)
            }
            LcIcon.FlagNone -> {
                line(4.5f to 18f, 4.5f to 2.8f)
                closed(4.5f to 3.5f, 15.5f to 3.5f, 15.5f to 11f, 4.5f to 11f)
            }
            LcIcon.FlagPickSolid -> {
                // a solid flag, a check cut out of it
                line(4.5f to 18f, 4.5f to 2.8f)
                rectFill(4.5f, 2.8f, 16.3f, 11.7f, 0.4f)
                cutLine(7.6f to 7.3f, 9.6f to 9.3f, 13.4f to 5.3f)
            }
            LcIcon.FlagRejectSolid -> {
                line(4.5f to 18f, 4.5f to 2.8f)
                rectFill(4.5f, 2.8f, 16.3f, 11.7f, 0.4f)
                cutLine(8.3f to 5.1f, 12.5f to 9.3f)
                cutLine(12.5f to 5.1f, 8.3f to 9.3f)
            }
            LcIcon.RotateLeft -> {
                rect(7f, 8f, 17f, 17f, 1f)
                arc(7f, 8f, 5f, 180f, 270f)
                line(4.5f to 6f, 2f to 8f, 0.5f to 5.5f)
            }
            LcIcon.RotateRight -> {
                rect(3f, 8f, 13f, 17f, 1f)
                arc(13f, 8f, 5f, 270f, 360f)
                line(15.5f to 6f, 18f to 8f, 19.5f to 5.5f)
            }
            LcIcon.FlipH -> {
                line(10f to 2.5f, 10f to 17.5f)
                closed(8f to 5f, 8f to 15f, 2.5f to 15f)
                fill(listOf(12f to 5f, 12f to 15f, 17.5f to 15f))
            }
            LcIcon.FlipV -> {
                line(2.5f to 10f, 17.5f to 10f)
                closed(5f to 8f, 15f to 8f, 15f to 2.5f)
                fill(listOf(5f to 12f, 15f to 12f, 15f to 17.5f))
            }
            LcIcon.Heal -> {
                closed(2.5f to 12.5f, 12.5f to 2.5f, 17.5f to 7.5f, 7.5f to 17.5f)
                line(7.5f to 7.5f, 12.5f to 12.5f)
                dot(9f, 11f, 0.7f)
                dot(11f, 9f, 0.7f)
            }
            LcIcon.Gear -> {
                circle(10f, 10f, 2.5f)
                circle(10f, 10f, 5.5f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val (c, s) = cos(a).toFloat() to sin(a).toFloat()
                    line((10f + 5.5f * c) to (10f + 5.5f * s), (10f + 7.5f * c) to (10f + 7.5f * s))
                }
            }
            LcIcon.Phone -> {
                rect(5.5f, 2.5f, 14.5f, 17.5f, 1.5f)
                line(8.5f to 15f, 11.5f to 15f)
            }
            LcIcon.Help -> {
                circle(10f, 10f, 7.5f)
                arc(10f, 8f, 2.5f, 180f, 405f)
                line(11.8f to 9.8f, 10f to 11f, 10f to 12f)
                dot(10f, 14.5f, 0.9f)
            }
            LcIcon.Az -> {
                line(2f to 11.2f, 5.6f to 2.2f, 9.2f to 11.2f)
                line(3.3f to 8f, 7.9f to 8f)
                line(10.6f to 10.4f, 17.6f to 10.4f, 10.6f to 17.6f, 17.6f to 17.6f)
            }
            LcIcon.Actions -> closed(11.5f to 2f, 4.5f to 11f, 9.5f to 11f, 8.5f to 18f, 15.5f to 9f, 10.5f to 9f)
            LcIcon.Play -> fill(listOf(3.5f to 3.3f, 16.5f to 10f, 3.5f to 16.7f))
            LcIcon.PhotoStack -> {
                // a photo (a solid hill, a sun) with another behind it
                line(3.5f to 17f, 18.2f to 17f, 18.2f to 4.2f)
                rect(0.9f, 1.6f, 15.6f, 14.1f, 1f)
                fill(listOf(0.9f to 14.1f, 0.9f to 11.2f, 4.9f to 7.2f, 8.8f to 11.2f, 10.8f to 9.4f, 15.6f to 13.4f, 15.6f to 14.1f))
                dot(11.6f, 5.4f, 1.4f)
            }
            LcIcon.Minus -> line(4.5f to 10f, 15.5f to 10f)
            LcIcon.DevicePhotos -> {
                // a phone, a photo (a solid hill, a sun) in front of it
                rect(1.65f, 1.65f, 11.5f, 18.35f, 1.4f)
                line(5.3f to 3.9f, 7.8f to 3.9f)
                cutRect(6.3f, 6.6f, 19.85f, 17.6f, 1f)
                rect(7.05f, 7.35f, 19.1f, 16.85f, 0.8f)
                fill(listOf(7.05f to 16.85f, 7.05f to 14.6f, 10.4f to 11.4f, 13.3f to 14.2f, 14.9f to 12.8f, 19.1f to 16.2f, 19.1f to 16.85f))
                dot(15.8f, 10.3f, 1.1f)
            }
            LcIcon.Library -> {
                // LightCraft's library: a solid tile, a photo's hill and sun cut out of it
                rectFill(1.8f, 1.8f, 18.2f, 18.2f, 2.5f)
                cutFill(listOf(4f to 15.6f, 8f to 10.2f, 11f to 13.4f, 12.8f to 11.6f, 16f to 15.6f))
                cutDot(12.8f, 6.6f, 1.6f)
            }
            LcIcon.Target -> {
                circle(8.5f, 10f, 5f)
                dot(8.5f, 10f, 1.1f)
                line(16.5f to 3f, 16.5f to 17f)
                line(14.5f to 5f, 16.5f to 3f, 18.5f to 5f)
                line(14.5f to 15f, 16.5f to 17f, 18.5f to 15f)
            }
            LcIcon.FixAngle -> {
                closed(4f to 7.5f, 14.5f to 4f, 16f to 12.5f, 5.5f to 16f)
                line(2f to 10f, 18f to 10f)
            }
            LcIcon.Clone -> {
                circle(10f, 5f, 2.5f)
                line(10f to 7.5f, 10f to 11f)
                rect(4f, 11f, 16f, 14.5f, 1f)
                line(4.5f to 17f, 15.5f to 17f)
            }
            LcIcon.HealCross -> {
                circle(10f, 10f, 7f)
                line(10f to 6.5f, 10f to 13.5f)
                line(6.5f to 10f, 13.5f to 10f)
            }
            // Upright's: a frame cut level, cut upright, cut both ways into nine
            LcIcon.Level -> {
                rect(3f, 3f, 17f, 17f, 0.5f)
                line(3f to 10f, 17f to 10f)
            }
            LcIcon.Vertical -> {
                rect(3f, 3f, 17f, 17f, 0.5f)
                line(10f to 3f, 10f to 17f)
            }
            LcIcon.FullUpright -> {
                rect(3f, 3f, 17f, 17f, 0.5f)
                line(7.67f to 3f, 7.67f to 17f)
                line(12.33f to 3f, 12.33f to 17f)
                line(3f to 7.67f, 17f to 7.67f)
                line(3f to 12.33f, 17f to 12.33f)
            }
            LcIcon.Guided -> {
                // two guides drawn along slanting lines, a dot at each end
                rect(3f, 3f, 17f, 17f, 0.5f)
                line(6f to 5.8f, 7.6f to 14.2f)
                line(14f to 5.8f, 12.4f to 14.2f)
                dot(6f, 5.8f, 1.1f)
                dot(7.6f, 14.2f, 1.1f)
                dot(14f, 5.8f, 1.1f)
                dot(12.4f, 14.2f, 1.1f)
            }
            LcIcon.Original -> {
                // the photo as it is: a wide picture, its hills and sun
                rect(1f, 3.4f, 19f, 16.6f, 0.8f)
                fill(listOf(3.2f to 14.6f, 8f to 8.8f, 11f to 11.8f, 13.2f to 9.6f, 16.8f to 14.6f))
                dot(14f, 7.2f, 1.4f)
            }
            LcIcon.FreeAspect -> {
                rect(3f, 4f, 17f, 16f, 1.5f)
                line(7f to 13f, 13f to 7f)
                line(9.5f to 7f, 13f to 7f, 13f to 10.5f)
                line(7f to 9.5f, 7f to 13f, 10.5f to 13f)
            }
            LcIcon.ToolPresets -> {
                // a look laid over the photo: an open circle over a solid one, the solid one checkered under it
                dot(7.8f, 12.2f, 5.8f)
                cutDot(12.4f, 7.6f, 6.6f)
                circle(12.4f, 7.6f, 5.4f)
                checker(1.4f) { x, y -> hypot(x - 7.8f, y - 12.2f) <= 5.8f && hypot(x - 12.4f, y - 7.6f) <= 4.4f }
            }
            LcIcon.ToolMasking -> {
                // a selection: a dashed ring around a checkered disc (what's picked)
                for (i in 0 until 10) arc(10f, 10f, 8.2f, i * 36f + 6f, i * 36f + 24f)
                checker(1.5f) { x, y -> hypot(x - 10f, y - 10f) <= 6.3f }
            }
            LcIcon.ToolRemove -> {
                // an eraser at a slant, its tip set apart, over the line it leaves
                fill(listOf(14.2f to 1.2f, 19f to 6f, 8.8f to 16.2f, 4f to 11.4f))
                cutLine(7.3f to 8f, 12.1f to 12.8f)
                line(10.5f to 18.6f, 18.5f to 18.6f)
            }
            LcIcon.CurveLine -> {
                // an S from the lower left to the upper right, a dot at each end
                line(*(0..14).map { i ->
                    val t = i / 14f
                    val y = t * t * (3f - 2f * t)
                    (3.5f + 13f * t) to (16.5f - 13f * y)
                }.toTypedArray())
                dot(3.5f, 16.5f, 1.9f)
                dot(16.5f, 3.5f, 1.9f)
            }
            LcIcon.Grading -> {
                // three wheels, one above two: solid, each with its middle cut out
                for ((x, y) in listOf(10f to 5.4f, 5.2f to 13.8f, 14.8f to 13.8f)) {
                    dot(x, y, 4.1f)
                    cutDot(x, y, 1.6f)
                }
            }
            LcIcon.TargetMove -> {
                // a dot with an arrowhead out to each side: drag it any way
                dot(10f, 10f, 3f)
                line(10f to 2.2f, 10f to 4.8f)
                line(8.6f to 3.6f, 10f to 2.2f, 11.4f to 3.6f)
                line(10f to 17.8f, 10f to 15.2f)
                line(8.6f to 16.4f, 10f to 17.8f, 11.4f to 16.4f)
                line(2.2f to 10f, 4.8f to 10f)
                line(3.6f to 8.6f, 2.2f to 10f, 3.6f to 11.4f)
                line(17.8f to 10f, 15.2f to 10f)
                line(16.4f to 8.6f, 17.8f to 10f, 16.4f to 11.4f)
            }
            LcIcon.Parametric -> {
                // a curve's regions: three bent bands side by side across a ring
                arc(10f, 10f, 8.6f, 105f, 165f)
                arc(10f, 10f, 8.6f, 285f, 345f)
                for (k in listOf(-1f, 0f, 1f)) {
                    val d = k * 2.6f
                    line(*(0..12).map { i ->
                        val t = i / 12f
                        val y = t * t * (3f - 2f * t)
                        (4.6f + 10.8f * t + d) to (15.4f - 10.8f * y + d)
                    }.toTypedArray())
                }
            }
            LcIcon.ViewOptions -> {
                // an eye, a list under it at the right
                arc(8f, 11.23f, 7.93f, 208.05f, 331.95f)
                arc(8f, 3.77f, 7.93f, 28.05f, 151.95f)
                dot(8f, 7.5f, 2.3f)
                for (y in listOf(13.5f, 16f, 18.5f)) line(12.5f to y, 18.5f to y)
            }
            LcIcon.CreatePreset -> {
                dot(6.5f, 7.5f, 4.6f)
                cutDot(10.2f, 7.5f, 5.8f)
                circle(10.2f, 7.5f, 4.6f)
                badge(14.5f, 14.5f)
                cutLine(12.3f to 14.5f, 16.7f to 14.5f)
                cutLine(14.5f to 12.3f, 14.5f to 16.7f)
            }
            LcIcon.CopySettings, LcIcon.PasteSettings, LcIcon.ApplyPrevious -> {
                // two sliders, a badge saying what happens to them
                line(1.5f to 4.5f, 4.6f to 4.5f)
                circle(6.5f, 4.5f, 1.8f)
                line(8.4f to 4.5f, 13.5f to 4.5f)
                line(1.5f to 9.5f, 7.6f to 9.5f)
                circle(9.5f, 9.5f, 1.8f)
                line(11.4f to 9.5f, 13.5f to 9.5f)
                badge(14.5f, 14.5f)
                when (icon) {
                    LcIcon.CopySettings -> {
                        cutLine(12.3f to 14.5f, 16.7f to 14.5f)
                        cutLine(14.5f to 12.3f, 14.5f to 16.7f)
                    }
                    LcIcon.PasteSettings -> {
                        cutLine(14.5f to 12.1f, 14.5f to 16.9f)
                        cutLine(12.6f to 15f, 14.5f to 16.9f, 16.4f to 15f)
                    }
                    else -> {
                        cutLine(16.9f to 14.5f, 12.1f to 14.5f)
                        cutLine(14f to 12.6f, 12.1f to 14.5f, 14f to 16.4f)
                    }
                }
            }
            LcIcon.SectionAuto -> {
                // a photo (its frame, hills in it), its corner given over to a sparkle
                rect(2.5f, 5.5f, 15.5f, 17.5f, 1f)
                fill(listOf(4.5f to 15.5f, 8f to 10.5f, 10.5f to 13.5f, 12f to 12f, 13.8f to 15.5f))
                cutDot(15.5f, 5.5f, 4.6f)
                fill(sparkle(15.6f, 4.6f, 3.6f))
                fill(sparkle(11.4f, 2.3f, 1.6f))
            }
            LcIcon.Lock, LcIcon.Unlock -> {
                // a padlock: its body solid, the shackle closed or swung open
                rectFill(4.5f, 9f, 15.5f, 18f, 1.5f)
                cutDot(10f, 13.2f, 1.4f)
                if (icon == LcIcon.Lock) {
                    arc(10f, 9f, 3.6f, 180f, 360f)
                    line(6.4f to 9f, 6.4f to 6.5f)
                    line(13.6f to 9f, 13.6f to 6.5f)
                } else {
                    // lifted: the right leg still in the body, the left one free above it
                    arc(10f, 6.5f, 3.6f, 180f, 360f)
                    line(13.6f to 6.5f, 13.6f to 9f)
                    line(6.4f to 6.5f, 6.4f to 7.2f)
                }
            }
            LcIcon.Ratios -> {
                // three frames of other shapes, stacked from the back right to the front left
                rectFill(8f, 3f, 17.5f, 10.5f, 0.8f)
                cutRect(4.8f, 5.3f, 15.7f, 14.7f, 1.2f)
                rectFill(5.5f, 6f, 15f, 14f, 0.8f)
                cutRect(1.8f, 9.3f, 12.2f, 18.2f, 1.2f)
                rectFill(2.5f, 10f, 11.5f, 17.5f, 0.8f)
            }
            LcIcon.AngleGrid -> {
                // a grid of three by two on a plane seen at an angle
                closed(3f to 6f, 17f to 3f, 17f to 17f, 3f to 14.5f)
                line(7.7f to 5f, 7.7f to 15.3f)
                line(12.3f to 4f, 12.3f to 16.2f)
                line(3f to 10.25f, 17f to 10f)
            }
            LcIcon.SwapAspect -> {
                // a crop's corners, the last one an arrow turning back: the crop the other way round
                line(3f to 6.2f, 3f to 3f, 6.2f to 3f)
                line(10.8f to 3f, 14f to 3f, 14f to 6.2f)
                line(3f to 10.8f, 3f to 14f, 6.2f to 14f)
                line(14f to 9.8f, 14f to 13.5f)
                arc(11f, 13.5f, 3f, 0f, 90f)
                line(12.6f to 14.9f, 11f to 16.5f, 12.6f to 18.1f)
            }
            LcIcon.MaskOverlay -> {
                // the reference's: a frame filled, the mask a disc cut from it, struck through with a
                // gap either side of the stroke (in both states: the tint tells them apart)
                rectFill(2.9f, 4.3f, 16.8f, 15.7f, 0.2f)
                cutDot(10.2f, 9.9f, 3.5f)
                cutFill(listOf(2.6f to 4.4f, 4.4f to 2.6f, 17.4f to 15.6f, 15.6f to 17.4f))
                line(3.6f to 3.6f, 16.4f to 16.4f)
            }
            LcIcon.SectionEffects -> {
                // a frame, light at its corners round a dark disc
                rect(1.8f, 1.8f, 18.2f, 18.2f, 0.4f)
                rectFill(4f, 4f, 16f, 16f, 0f)
                cutDot(10f, 10f, 5.9f)
            }
            LcIcon.SectionDetail -> fill(listOf(10f to 2f, 16.5f to 18f, 3.5f to 18f))
            LcIcon.SectionOptics -> optics()
        }
    }
}
