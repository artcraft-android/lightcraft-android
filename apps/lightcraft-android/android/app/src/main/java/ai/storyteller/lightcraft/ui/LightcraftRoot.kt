package ai.storyteller.lightcraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The library grid, or the editor when a photo is open; snackbars for both. */
@Composable
fun LightcraftRoot(vm: AppViewModel, onPickPhotos: () -> Unit, onPickFiles: () -> Unit, onCamera: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    // kept here so the grid's scroll position survives a visit to the editor
    val grid = rememberLazyListState()
    LaunchedEffect(vm) {
        for (m in vm.messages) {
            val result = snackbar.showSnackbar(
                m.text,
                actionLabel = m.actionLabel,
                withDismissAction = m.actionLabel == null,
                duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) m.action?.invoke()
        }
    }
    // back in the app: the folder library's folder may have new photos, or edits another app saved to a sidecar;
    // the cloud library sends the edits and fetches what the PC changed (at most once a minute)
    LifecycleResumeEffect(vm) {
        vm.syncFolder()
        vm.cloudSyncOnResume()
        onPauseOrDispose {}
    }
    val editor = vm.editor
    // the library's message (and its Undo) stays with the library
    LaunchedEffect(editor != null) { if (editor != null) snackbar.currentSnackbarData?.dismiss() }
    // the editor fading out still needs the photo it showed
    val last = remember { arrayOfNulls<EditorState>(1) }
    if (editor != null) last[0] = editor
    BackHandler(enabled = editor != null) { vm.back() }
    Box {
        Crossfade(targetState = editor != null, label = "screen") { editing ->
            val ed = if (editing) vm.editor ?: last[0] else null
            if (ed == null) LibraryScreen(vm, snackbar, grid, onPickPhotos, onPickFiles, onCamera) else EditorScreen(vm, ed, snackbar)
        }
        if (vm.slideshow != null) Slideshow(vm)
    }
}
