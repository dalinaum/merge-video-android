# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android app (Kotlin + Jetpack Compose, Material 3) that lists mp4 files in `DCIM/Camera` and concatenates the user's selection into a single mp4 saved back to `DCIM/Camera`. Primary target device: Xiaomi Redmi 10 (Android 11). `minSdk = 24`, `targetSdk = 34`.

## Commands

```bash
./gradlew assembleDebug         # build APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug          # build + adb install onto a connected device
./gradlew clean                 # wipe build outputs
./gradlew lint                  # AGP lint
```

There are no unit/instrumentation tests in the project yet; do not invent test commands.

`local.properties` (gitignored) must point at the Android SDK: `sdk.dir=/Users/<user>/Library/Android/sdk`. The wrapper pins Gradle 8.7; AGP 8.5.2 + Kotlin 1.9.24 + Compose Compiler 1.5.14.

## Architecture

The merge flow is a small pipeline through four collaborators owned by `VideoListViewModel`:

```
VideoRepository ──► ViewModel ──► MergedVideoSink ─┐
   (MediaStore         │                            ▼
    query)             ▼                       VideoMerger
                  selectedUris            (MediaExtractor
                  (List<Uri>,              + MediaMuxer,
                   ordered)                 no re-encoding)
```

- **`data/VideoRepository.kt`** queries `MediaStore.Video` filtered to `MIME_TYPE = video/mp4` and (`RELATIVE_PATH LIKE 'DCIM/Camera/%'` on Q+ / `DATA LIKE` on legacy). Returns `VideoItem(uri, displayName, durationMs, sizeBytes)`.

- **`data/MergedVideoSink.kt`** creates the *output* file. The `Sink` is `AutoCloseable` with explicit `commit()`. **Important**: `close()` without `commit()` deletes the row (Q+) or the file (legacy), so the abort path on exceptions does not leave orphaned `IS_PENDING=1` rows or zero-byte files. Two implementations: `PendingMediaStoreSink` (Q+) uses `IS_PENDING=1` then flips to 0 on commit; `LegacyFileSink` writes directly via `ParcelFileDescriptor.open` and `MediaScannerConnection.scanFile` on commit.

- **`merge/VideoMerger.kt`** does **remuxing only** (no decode/encode). Iterates inputs in selection order. For each input: opens a fresh `MediaExtractor` (in `try/finally`), copies video and audio sample data into the muxer with `presentationTimeUs += ptsOffsetUs`. **A single shared `ptsOffsetUs`** is advanced by `max(videoLastPts, audioLastPts) + frameDuration` per clip — not per-track — to prevent A/V drift accumulating across files. The sample buffer starts at 1 MB and grows to `MediaFormat.KEY_MAX_INPUT_SIZE` of any track on demand. Caller must guarantee uniform codec/resolution/sample-rate across inputs; `MediaMuxer` will throw otherwise (surface as `UiState.Error`).

- **`ui/VideoListViewModel.kt`** owns `uiState: StateFlow<UiState>` (`NoPermission | Loading | Loaded | Merging | Done | Error`) and `selectedUris: StateFlow<List<Uri>>`. Selection order is preserved (a `List`, not a `Set`) because it determines merge order. `mergeSelected()` runs on `Dispatchers.IO` and uses `sinkFactory.create().use { ... commit() }` — the `commit()` happens *inside* `use { }` so any thrown exception triggers the deleting `close()` path.

- **`ui/VideoListScreen.kt`** computes `Map<Uri, Int>` from the selection list once via `remember(selected)` so each row's order badge is an O(1) lookup instead of `indexOf` per recomposition. The bottom bar derives "is merging" from `uiState as? UiState.Merging` rather than carrying a redundant boolean.

## Conventions

- Source root is `app/src/main/kotlin/...` (declared in `app/build.gradle.kts` `sourceSets`), not the default `java/`.
- User-facing strings live in `app/src/main/res/values/strings.xml`. Korean is the only locale. Technical exception messages (thrown from `VideoMerger` / `MergedVideoSink`) are intentionally English because they surface in `UiState.Error.message` for debugging.
- Permission handling differs by SDK: `READ_MEDIA_VIDEO` on API 33+, `READ_EXTERNAL_STORAGE` on 32 and below. `MainActivity.videoPermissionName()` is the single SDK gate; `VideoListScreen` requests via `ActivityResultContracts.RequestPermission`.

## Reference

- Repo: https://github.com/dalinaum/merge-video-android
- Plan files for past tasks: `~/.claude/plans/generic-swimming-charm.md`
