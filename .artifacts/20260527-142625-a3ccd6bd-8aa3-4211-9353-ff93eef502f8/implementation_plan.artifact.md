# Implementation Plan - Fix Playback Position Carryover

The issue is caused by a race condition in `PlayerActivity.saveVideoPlaybackState`. This method launches an asynchronous coroutine to save the playback state to the database but uses the global `mediaIdentifier` variable. When switching to the next video, `loadPlaylistItemInternal` updates the global `mediaIdentifier` immediately after calling `saveVideoPlaybackState`. This often happens before the coroutine starts, leading to the previous video's position being saved for the new video's identifier.

## Proposed Changes

### [PlayerActivity](file:///Users/peilinxiong/StudioProjects/mpvExtended-android/app/src/main/java/app/marlboroadvance/mpvex/ui/player/PlayerActivity.kt)

#### `saveVideoPlaybackState(mediaTitle: String)`
- Capture all necessary state variables (identifier, position, duration, speed, tracks, etc.) synchronously on the calling thread *before* launching the `lifecycleScope.launch(Dispatchers.IO)` coroutine.
- Ensure the coroutine uses these captured values instead of reading from the global state or `viewModel`.

#### `calculateSavePosition(pos: Int, duration: Int, oldState: PlaybackStateEntity?)`
- Modify this method to accept `pos` and `duration` as parameters instead of reading them from `viewModel`.

## Verification Plan

### Automated Tests
- Since this is a race condition in an Activity, unit testing might be difficult without a full Robolectric setup. However, I will verify the logic changes by reviewing the data flow.
- I will check if there are any existing tests for `PlaybackStateRepository` that I can leverage.

### Manual Verification
- Deploy the app and test with a playlist (specifically network files like SMB if possible, or any playlist).
- Play Video 1 to some middle position.
- Click "Next" to Video 2.
- Verify Video 2 starts from the beginning (00:00).
- Check logs for "Saving playback state" to ensure identifiers match the captured state.
