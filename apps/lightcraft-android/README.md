# LightCraft for Android

LightCraft on phones: the engine (`crates/engine` and everything below it) as a native library, with a
touch UI written in Jetpack Compose. It is a fourth frontend next to the desktop app, the CLI/MCP
server and the web build, and like them it drives the engine through commands
(`develop.set`, `photo.rate`, `edit.undo`…) and render jobs. Nothing of the egui UI is used.

Status: alpha, like the rest of LightCraft. You can browse the library (views, albums, filters,
sorting, search, a justified or square grid), import photos (JPEG, PNG, TIFF, WebP, DNG and the
other raw formats LightCraft reads; not HEIC, which the engine can't decode yet), edit them with
every develop slider, crop and straighten, paint masks, remove spots, apply presets and profiles
(previewed on the photo), rate and flag them (one at a time or a selection), undo and redo, compare
against the original, and save or share JPEGs.

The screens follow the reference phone app's layout and behaviour.
Where the reference uses Google's Material icons, so does LightCraft (the same Apache-2.0 set,
`ui/MaterialIcons.kt`); its other icons are LightCraft's own, drawn in code in the same weight
(`ui/LcIcons.kt`). The font (Inter), the name and the words are LightCraft's own too.

## Layout

| Path | What |
|---|---|
| `src/lib.rs` | The `lightcraft-android` crate: [`MobileSession`](src/lib.rs) wraps `lightcraft_engine::Session` for UniFFI (Kotlin now, Swift later) |
| `src/tests.rs` | Host tests of that API (`cargo test -p lightcraft-android`) |
| `uniffi.toml` | Bindings config: the Kotlin package `ai.storyteller.lightcraft.core` |
| `bindgen/` | `uniffi-bindgen`, pinned to the crate's UniFFI release; the Android build runs it |
| `android/` | The Android Studio project (Gradle, Kotlin, Compose) |

## Build and run

You need:
- [rustup](https://rustup.rs): it installs the Rust release and the Android targets that
  `rust-toolchain.toml` names (one pinned release, so every build of a commit is byte-identical)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk --version 4.1.2 --locked`
- the Android SDK with NDK 29.0.14206865 (Android Studio → SDK Manager → SDK Tools → NDK)
- JDK 17 or newer (Android Studio's bundled one is fine)

Then open `apps/lightcraft-android/android` in Android Studio and press Run, or from a terminal:

```sh
cd apps/lightcraft-android/android
./gradlew installDebug                                # phone (arm64) and emulator (x86_64) libraries
./gradlew installDebug -Plightcraft.abis=arm64-v8a    # just the phone's: half the Rust build time
./gradlew installDebug -Plightcraft.abis=armeabi-v7a  # a 32-bit ARM phone
```

In PowerShell, quote the property, or PowerShell splits it at the dot:
`.\gradlew.bat installDebug "-Plightcraft.abis=arm64-v8a"`.

The Gradle build runs `cargo ndk … build --release -p lightcraft-android` (always the release
profile: a debug engine is too slow for photos), then generates the Kotlin bindings from the library
it built. The first build compiles the engine for each ABI (several minutes each). After that cargo
rebuilds only what changed. Gradle finds `cargo` in `~/.cargo/bin` (or `$CARGO`).

### librclone (the cloud library)

The cloud library reaches a PC's published photos through rclone's C library, which is not built by
Gradle. Build it once with [Go](https://go.dev) installed (`scripts/build-librclone.sh`: pinned to rclone
v1.75.2, a reduced backend set; add `LIBRCLONE_FULL=1` for all of them):

```sh
apps/lightcraft-android/scripts/build-librclone.sh                  # arm64-v8a, armeabi-v7a and the host
apps/lightcraft-android/scripts/build-librclone.sh arm64-v8a x86_64 # chosen ABIs
```

The Android libraries land in `android/app/src/main/jniLibs/<abi>/` (Gradle packages them as they
are), the host one in `librclone-host/` (used by `cargo test -p lightcraft-cloud-client`). Nothing
built is committed. Without them the app still builds; connecting to a cloud library fails with
"librclone.so not found". See [docs/cloud-library.md](docs/cloud-library.md).

## How it works

- **One session per process.** `LightcraftApplication` opens the library in the app's private files
  (`files/LightCraft Library`) on a background thread. A new library starts with the procedural demo
  photos. The library is locked while the session is open, so activities share this one.
- **Threads.** Every `MobileSession` call blocks, so the app calls them off the main thread.
  Commands go through one serial dispatcher, so they apply in the order the UI issued them. Renders
  hold the session lock only to build their job and to keep the decoded source afterwards; the
  develop work runs unlocked, so slider moves don't wait for a render in flight.
- **Slider drags** are engine interactions (`begin_adjustment` → `set_control`… →
  `end_adjustment`): previews while the finger moves, one undo step when it lifts. During a drag the
  view renders at draft quality and half size, then at full quality when the drag ends. A per-view
  `StageCache` means a drag reruns only the pipeline stages its setting feeds. The newest request
  always wins: a busy render loop skips the requests that came in while it was rendering.
- **The panel builds itself** from the engine's control specs (`develop_controls`): sections, ranges,
  defaults, precision and track styles (temperature, tint, hue, colour gradients). Double-tap a
  slider's label to reset it. White balance matches the desktop: a relative Temp slider (−100…100,
  a mired shift from 6500 K) for rendered files, Kelvin for raws. The Kelvin track is spaced in
  mireds, so the useful 2500–10000 K range isn't squeezed into a sliver.
- **Thumbnails** come from the engine's thumbnail cache (memory, and `thumbs/` in the library) keyed
  by the photo's content and settings. An edit shows up as a new key, so the grid updates.
- **Import** copies picked or shared documents into `files/Import`, then `library.import` with
  `mode: move` moves them into the library's `Originals/YYYY/YYYY-MM-DD/`. That is a rename, since
  both are on the same volume. The camera button (the right half of the add pill) has the phone's
  camera app write a photo into the app's cache (`res/xml/file_paths.xml`, a `FileProvider`),
  which is then moved in the same way. Raw files that the photo picker doesn't list come in from
  Files: the folder at the end of more › Add photos.
- **Export** renders and encodes in the engine (`export`, the `app.export` options) and the app
  writes the bytes into `Pictures/LightCraft` through MediaStore (no storage permission needed).
  A share goes from the app's cache through its `FileProvider` instead (`PhotoFiles.shareable`), so
  it leaves no copy in the phone's photos.
- **Undo** is the engine's one history. The editor reaches back only to when its photo was opened
  (`open_photo` marks the history; `close_editor` lifts the mark), so the library's steps stay out of
  its Undo. A mode's Cancel (Crop, Profiles) takes back every step after a mark taken as it opened
  (`history_depth`, `discard_to`), and a message's Undo takes back only the step it reported
  (`undo_at`).
- **Motion.** Sheets and the album panel come and go as the reference's do, measured from screen
  recordings of it (`ui/Overlays.kt`: in over 170 ms rising 20 dp, out over 155 ms rising 10 dp, the
  dim on its own clock; a sheet can be pulled down to close). The drawer opens over 295 ms and
  closes at once, as the reference's.
- **Permissions.** Only the Device tab asks for one: the photos (`READ_MEDIA_IMAGES`; from Android
  14 the person may pick only some, `READ_MEDIA_VISUAL_USER_SELECTED`; `READ_EXTERNAL_STORAGE` before
  Android 13). Importing through the photo picker or Files needs none, nor does the camera button
  (the camera app takes the photo).
- **The screens.** The home screen has two tabs along the bottom: Device (the phone's own photos,
  read through MediaStore once the person allows it; open one and a copy joins the library, to go
  again if it's left untouched) and LightCraft, the library. The library has a top bar (menu,
  search, more; a search is typed over the dimmed screen and, once sent, shows as a chip over its
  results), then the view's title (tap it for the views and albums), filter and sort, then the
  grid: justified or square, in one run or segmented by year, month, day or hour. Sideways the
  library takes the reference's landscape layout: the tabs in a rail at the left, the views and
  albums in a pane beside the photos, and filter, sort and more as cards by their buttons. Long-press a photo to select; a selection can be rated,
  flagged, added to an album, given pasted settings or removed (Deleted keeps it until you delete
  it for good). The editor puts the photo under a slim top bar and the tools along the bottom:
  Actions (Auto, Fix angle and Rate in a row above), Presets, Crop, Edit (its sections in a row
  above: Auto, Light, Color, Effects, Detail, Optics, Calibration, Profiles), Masking and Remove.
  An open section's panel sizes itself to its content, up to about two and a half sliders; pull its
  list up to see more. Light's curve is drawn over the photo (`ui/CurveTool.kt`) and Color's grading
  is a wheel (`ui/GradingWheel.kt`). Crop and Profiles are modes with cancel and apply; Crop's,
  Remove's and Masking's handles are on the photo (`ui/CropTool.kt`, `ui/RemoveTool.kt`,
  `ui/MaskTool.kt`; `MobileSession.crop_frame` and `view_geometry` map the screen to the photo).
  Save and share open the export sheet (`ui/ExportSheet.kt`: file type, size, quality, metadata).
  Masking lists the photo's masks in a column at the right, the edited one with its parts (each
  with a menu: hide, rename, duplicate, convert to subtract or add, delete), and swaps the sections
  for the mask's own adjustments; the edited part's tools stand at the left (a range's Pick and
  Refine, a radial's Feather, a brush's size, feather and flow; invert, delete). A colour or
  luminance range is picked with a ring or over an area dragged on the photo (`ui/RangePicker.kt`);
  a luminance range's values are ranks in the photo's own brightness (50: half the photo is
  darker), so a pick reads the same however the photo is exposed, and a colour range selects by
  hue and chroma first, lightness counting far less (both measured against the reference's
  masks on our test photos).
  The mask shows in red over the photo while you edit it. Sideways, the panel, the sections and the
  tools stand as columns to the right of the photo.
- **Folder library (opt-in).** By default photos are copied into the app's own library. Preferences ▸
  Library folder instead edits the photos of a folder where they are, with the edits in XMP sidecars
  next to them (`IMG_1.xmp`), so other apps and sync tools such as Syncthing see them. It needs "All
  files access" (`MANAGE_EXTERNAL_STORAGE`, which the app asks for only when this is turned on;
  Android 11 or later), so the engine works on plain `/storage/emulated/0/…` paths. Turning it on adds
  the folder and its subfolders in place, watches it (`library.autoImport`, add in place) and turns
  on `library.xmpPreferences` auto-write; turning it off stops watching and leaves the photos in the
  library and every file on disk alone. The app *synchronizes* whenever it comes to the front, and
  from Preferences: `MobileSession::sync_folder` adds the photos that appeared (once their size has
  stopped changing) and reads the sidecars that are new or changed since the last time
  (`photo.readMetadataFromFile` with `onlyChanged`: a sidecar that says what the library has is no
  undo step, and what is read is not written back, so a sidecar is never rewritten by reading it).
  Photos that were deleted from the folder are not removed from the library.
- **Anything else** is a command away: `MobileSession.execute(id, json)` runs any of the engine's
  commands with the parameters documented in `docs/control-protocol.md` and the MCP tools.

### Engine notes for Android

- **GPU.** The engine's wgpu compute pipeline uses Vulkan on Android; without a usable GPU it renders on
  the CPU (the reference pipeline). About (the library's menu) shows which one is in use, and
  Preferences can turn the GPU off. Images over 2^24 pixels (16.7 MP: full-size exports of larger
  photos) render on the CPU on Android: an Adreno 830 wrote every pixel from that index on back over
  the start of the image (`Gpu::max_pixels` in `crates/gpu`).
- **Memory.** The budget is a quarter of RAM, at most 1.5 GB (`crates/engine/src/memory.rs`, which
  now reads `/proc/meminfo` on Android too); 256 MB on devices Android calls low-RAM.
- **Never crash.** The rules of the workspace apply to this crate too (no `unwrap`/`expect`/`panic`
  outside tests, `#![forbid(unsafe_code)]`: UniFFI's generated glue builds under it). Bad input from
  the UI returns `MobileException`, and a panic that escapes a call reaches Kotlin as an
  `InternalException` instead of killing the app.

## Licences of what the app adds

- [UniFFI](https://github.com/mozilla/uniffi-rs) (MPL-2.0): the `uniffi` crate in the library, and
  the generator of the Kotlin bindings (generated at build time, not committed).
- [JNA](https://github.com/java-native-access/jna) (Apache-2.0, dual-licensed with LGPL-2.1): how the
  Kotlin bindings call the library. The app uses it under Apache-2.0.
- AndroidX, Jetpack Compose and kotlinx.coroutines (Apache-2.0).
- [Material Icons](https://github.com/google/material-design-icons) by Google (Apache-2.0): the
  ones the reference app shows on its library screens, as path data in `ui/MaterialIcons.kt`
  (listed in `assets/ATTRIBUTION.md`). The other icons are LightCraft's own, drawn in code
  (`ui/LcIcons.kt`).
- The launcher icon is LightCraft's own (`assets/app-icon/lightcraft-1024.png`), copied in at build
  time.

No Adobe code, icon, image, font, preset or profile is in the app.

## Next

- iOS: the same crate with `crate-type` `staticlib` and `uniffi-bindgen generate --language swift`,
  and a SwiftUI shell.
- The AI masks (they need their model on the phone).
- The editor's icons: the reference's editor blocks screenshots, so only its layout could be
  measured (from screen-reader dumps); its tools keep LightCraft's own icons.
- Zoomed-in views rendered at 1:1 from the full-resolution source.
- HEIC: the engine has no HEVC decoder yet. Until it has one, the app could decode HEIC with
  Android's own decoder and hand the engine the pixels.
