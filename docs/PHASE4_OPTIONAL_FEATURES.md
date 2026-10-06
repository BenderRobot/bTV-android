# Phase 4: Optional Features (EPG & Mini-player)

## Features Overview
Extended functionality for better user experience (non-critical path).

### Feature 1: Electronic Program Guide (EPG)
**Purpose**: Show TV schedule for live channels

**Entities**:
```kotlin
@Entity(tableName = "epg_programs")
data class EpgProgramEntity(
    @PrimaryKey val programId: String,
    val channelId: String,
    val title: String,
    val description: String,
    val startTime: Long,
    val endTime: Long,
    val genre: String,
    val rating: String
)
```

**DAO Operations**:
- `getProgramsByChannel(channelId): Flow<List<EpgProgram>>`
- `getProgramsByTime(startTime, endTime): Flow<List<EpgProgram>>`
- `insertPrograms(programs: List<EpgProgram>)`

**UseCase**:
- `GetEpgUseCase` - Fetch programs for channel/time range

**UI Components**:
- `EpgScreen` - Grid view of programs by time
- `EpgItem` - Program card with title, time, genre
- `EpgTimeline` - Horizontal timeline selector

### Feature 2: Mini-player (Picture-in-Picture)
**Purpose**: Continue watching while browsing

**ViewModel Enhancement**:
```kotlin
class PlayerViewModel {
    val isPictureInPicture: StateFlow<Boolean>
    fun enterPictureInPicture()
    fun exitPictureInPicture()
}
```

**AndroidManifest Permission**:
```xml
<uses-feature android:name="android.software.picture_in_picture" android:required="false" />
```

**Implementation**:
- Detect PiP capability via `PackageManager`
- Resize player view when entering PiP
- Resume full screen on exit
- Save state to preferences

### Feature 3: Continuous Playback
**Purpose**: Resume from last position automatically

**Flow**:
1. Load video → Check if previously watched
2. If watched > 90% → Ask to resume
3. If 10-90% watched → Auto-resume
4. If < 10% watched → Start from beginning
5. Save position every 30 seconds

## Implementation Order
1. ✅ Phase 1: Room entities & repositories
2. ✅ Phase 2: UseCases & ViewModel integration
3. ✅ Phase 3: PlayerScreen (critical)
4. ✅ Phase 4: EPG implemented (mini-player/PiP skipped - low ROI for Fire TV)
5. ⏳ Phase 5: Tests

## Phase 4 Actual Implementation (2026-09-20)

**EPG — DONE**:
- `EpgProgramEntity.kt`, `EpgDao.kt`, `EpgRepository.kt`, `GetEpgUseCase.kt` — real Room-backed data layer, wired into Hilt (`RepositoryModule`, `UseCaseModule`), DB bumped to v3.
- `EpgScreen.kt` (`com.btv.ui.epg`) — full-screen guide: selected-program detail panel (title, time range, genre, description, live progress bar) + horizontal D-Pad-navigable timeline (`EpgItem` cards), live program marked with a red dot + highlighted background, auto-scrolls to keep selection in view.
- Entry point: a 3rd icon (📅) on `BackdropHeader`, shown only when `contentType == LIVE`, calling `BrowseViewModel.openEpg(channelId)`.
- `BrowseViewModel` currently **generates mock programs in-memory** (`generateMockPrograms`) rather than reading from the Room/UseCase layer — the data layer exists and is DI-wired for when a real EPG API/XMLTV source is integrated, but isn't populated yet.
- Verified live on device (Fire TV, physical remote + adb): opens without crash, D-Pad left/right moves selection and auto-scrolls, Back returns to Browse cleanly.

**Mini-player / PiP — SKIPPED**: Picture-in-Picture is a phone-multitasking paradigm; on Fire TV/Android TV the leanback launcher doesn't surface it the same way, so the effort/value tradeoff didn't justify implementing it for this app right now. Revisit if cross-device (phone/tablet) support is ever added.

**Continuous Playback (>90%/10-90%/<10% resume logic) — NOT YET DONE**: still open, see Phase 5 planning.

## Files to Create (Phase 4)

### EPG Feature
- `EpgProgramEntity.kt` (20 lines)
- `EpgDao.kt` (30 lines)
- `EpgRepository.kt` (40 lines)
- `GetEpgUseCase.kt` (25 lines)
- `EpgScreen.kt` (200 lines)

### Mini-player Feature
- `PictureInPictureHelper.kt` (50 lines)
- Player UI enhancements (50 lines)

**Total Phase 4: ~415 lines**

## Dependencies
- No new external dependencies needed
- Uses existing Room, Compose, ExoPlayer

## Timeline
- **Estimated Duration**: 2-3 hours
- **Risk Level**: Low (optional, no impact on core functionality)
- **Testing**: Manual UI tests

## Notes
- EPG data typically fetched from API (not covered here)
- Mini-player requires Android 8.0+ (API 26+)
- Both features fully optional - app works without them
