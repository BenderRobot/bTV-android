# Phase 3: PlayerScreen Implementation

## Overview
Complete video playback UI with ExoPlayer integration, error handling, and retry logic.

## Implemented Components

### 1. PlayerUiState (Data Layer)
- **File**: `com/btv/ui/player/PlayerUiState.kt`
- **Purpose**: Immutable state container for video player
- **Fields**:
  - `isPlaying`: Boolean - current playback state
  - `currentPosition`: Long - playback position in ms
  - `duration`: Long - total video duration in ms
  - `bufferedPosition`: Long - buffered position
  - `playbackState`: Int - Player state (IDLE/BUFFERING/READY/ENDED)
  - `isLoading`: Boolean - is player buffering
  - `errorMessage`: String? - error description if any
  - `retryCount`: Int - current retry attempt
  - `maxRetries`: Int - max retry attempts (default 3)

### 2. PlayerViewModel (Business Logic)
- **File**: `com/btv/ui/player/PlayerViewModel.kt`
- **Purpose**: Manage ExoPlayer lifecycle and state
- **Key Methods**:
  - `loadStream(url, contentType)` - load video stream
  - `play()` / `pause()` / `togglePlayPause()` - playback control
  - `seekTo(positionMs)` - jump to position
  - `seekForward(deltaMs = 10s)` - skip forward
  - `seekBackward(deltaMs = 10s)` - skip backward

**Features**:
- Real-time progress tracking (500ms interval)
- Auto-save playback position via UseCase
- Automatic retry on error (2s delay between attempts)
- Player listener for state updates
- Proper resource cleanup in onCleared()

### 3. PlayerScreen (UI Layer)
- **File**: `com/btv/ui/player/PlayerScreen.kt`
- **Purpose**: Compose UI for video playback
- **Components**:
  - Main player view (AndroidView wrapper around ExoPlayer)
  - Controls overlay (play/pause, seek buttons)
  - Progress bar with time display (hh:mm:ss format)
  - Error message overlay with retry button
  - Loading indicator
  - Tap-to-toggle controls

**UI Features**:
- Gradient background for readability
- Center play/pause button with rewind/forward
- Top back button
- Bottom progress/time info
- Error handling with retry option
- Hides controls on tap (tap again to show)

## Error Handling Strategy

**Retry Policy**:
1. Stream fails to load → Automatically retry
2. Delay: 2 seconds between attempts
3. Max Attempts: 3 retries
4. After max attempts: Show error message with manual retry button

**Error Display**:
- Shows error message in centered overlay
- Displays retry count (e.g., "Attempt 1/3")
- After exhaustion: "Max retries reached" with red text

## Integration Points

### With Phase 2 (UseCases)
- `GetPlaybackProgressUseCase.saveProgress()` - auto-save position every 500ms
- Restores position when stream loaded (not yet implemented in this phase)

### With BrowseViewModel
- PlayerViewModel can be injected via Hilt
- BrowseViewModel triggers player navigation with stream URL
- Playback position saved for later resume

## Navigation Flow
```
BrowseScreen (select content)
    ↓
PlayerScreen (play video)
    ↓ on back
BrowseScreen (resume browsing)
```

## Known Limitations
- Position restoration not wired to UI yet (Phase 4)
- No subtitle support yet
- No audio track selection
- No picture-in-picture mode
- Fixed aspect ratio (fit to screen)

## Testing Checklist
- [ ] Play/pause toggle works
- [ ] Seek forward/backward 10s works
- [ ] Progress bar updates in real time
- [ ] Error on invalid URL triggers retry
- [ ] Max retries shows error message
- [ ] Back button exits player
- [ ] Position saves to database
- [ ] Controls hide on tap, show on second tap

## Files Created
1. `PlayerUiState.kt` (15 lines)
2. `PlayerViewModel.kt` (120 lines)
3. `PlayerScreen.kt` (300 lines)

**Total Phase 3: ~435 lines**
