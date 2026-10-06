# bTV Android - Complete Documentation Index

## Overview
Migration from Tizen IPTV application to native Android with Jetpack Compose.

## Documentation Files

### 1. Planning & Audit
- **[AUDIT_TIZEN_MIGRATION.md](AUDIT_TIZEN_MIGRATION.md)** - Initial audit, state analysis, 5-phase plan
- **[CURRENT_STATUS.md](CURRENT_STATUS.md)** - Project progression tracking
- **[FEATURES.md](FEATURES.md)** - 30 Tizen features mapped to Android
- **[SCREEN_MAP.md](SCREEN_MAP.md)** - 6 screen types and relationships

## Implementation Phases

### Phase 1: Data Layer ✅ COMPLETED
**Status**: Fully compiled and integrated

- **[PHASE1_IMPLEMENTATION.md](PHASE1_IMPLEMENTATION.md)**
- **Files Created**:
  - 4 Room Entities (Favorites, History, PlaybackProgress, UserSession)
  - 4 Room DAOs with Flow-based queries
  - 4 Repositories wrapping DAOs
  - PreferencesStore for DataStore integration
  - BtvDatabase with migration support

**Capabilities**:
- Favorites management (CRUD + Flow streaming)
- History tracking with auto-increment viewCount
- Playback progress persistence (position, duration, completion)
- User session management
- 10+ preferences via DataStore

### Phase 2: Business Logic ✅ COMPLETED
**Status**: Fully compiled and integrated

**Files Created**:
- 6 UseCases:
  - `GetFavoritesUseCase` - favorites queries
  - `ToggleFavoriteUseCase` - add/remove favorites
  - `GetRecentlyWatchedUseCase` - history management
  - `GetPlaybackProgressUseCase` - progress tracking
  - `SaveSessionUseCase` - user sessions
  - `GetPreferencesUseCase` - preferences access

- BrowseViewModel enrichment:
  - `_favoriteIds: MutableStateFlow<Set<String>>`
  - Methods: `toggleFavorite()`, `isFavorite()`, `loadFavorites()`, `loadInProgressItems()`, `trackRecentlyWatched()`

- Hilt DI Configuration:
  - `RepositoryModule` - database and repository injection
  - `UseCaseModule` - usecase injection
  - Gradle configuration with kapt compiler

### Phase 3: Player (Critical Path) 🔄 IN PROGRESS
**Status**: Code created, compilation testing

- **[PHASE3_PLAYER_IMPLEMENTATION.md](PHASE3_PLAYER_IMPLEMENTATION.md)**
- **Files Created**:
  - `PlayerUiState` - immutable state for player
  - `PlayerViewModel` - ExoPlayer lifecycle & logic
  - `PlayerScreen` - Compose UI with controls
  
**Features**:
- Play/Pause/Seek controls
- Real-time progress tracking (500ms interval)
- Automatic retry on error (3 attempts max, 2s delay)
- Error overlay with manual retry
- Progress bar with time display (hh:mm:ss)
- Auto-save playback position

### Phase 4: Optional Features ⏭️ PLANNED
- **[PHASE4_OPTIONAL_FEATURES.md](PHASE4_OPTIONAL_FEATURES.md)**
- EPG (Electronic Program Guide) for live TV
- Mini-player (Picture-in-Picture mode)
- Continuous playback with position restoration

### Phase 5: Testing ⏭️ PLANNED
- **[PHASE5_TESTING.md](PHASE5_TESTING.md)**
- Unit tests (repositories, usecases, viewmodels)
- Integration tests (Room database)
- Compose UI tests (Espresso)
- Target coverage: 80%+

## Technical Documentation

### Architecture & Design
- **[MIGRATION_MAP.md](MIGRATION_MAP.md)** - 1:1 Tizen→Android file mapping
- **[DATA_FLOW.md](DATA_FLOW.md)** - Architecture evolution Tizen→Android
- **[BROWSE_SCREEN_GUIDE.md](BROWSE_SCREEN_GUIDE.md)** - BrowseScreen component details

### Features & Navigation
- **[PLAYER_BEHAVIOR.md](PLAYER_BEHAVIOR.md)** - 23 ExoPlayer playback cases
- **[NAVIGATION.md](NAVIGATION.md)** - D-Pad controls and focus management
- **[TIZEN_MIGRATION_README.md](TIZEN_MIGRATION_README.md)** - Global migration guide

## Project Statistics

| Metric | Value |
|--------|-------|
| Total Lines of Code | 850+ |
| Entities | 10 |
| DAOs | 5 |
| Repositories | 4 |
| UseCases | 6 |
| ViewModels | 3 |
| Composables | 5+ |
| **Phase 1** | ✅ 100% |
| **Phase 2** | ✅ 100% |
| **Phase 3** | 🔄 95% |
| **Phase 4** | 📋 0% |
| **Phase 5** | 📋 0% |

## Compilation Status

### Current Issues
- Phase 3 compilation: Testing `@OptIn` annotation fixes

### Dependencies
- Room 2.6.1
- Compose BOM 2024.06.00
- ExoPlayer/Media3 1.4.1
- Hilt 2.51
- Kotlin 1.9.24
- Android SDK 35

## Quick Navigation

**Getting Started**:
1. Read [AUDIT_TIZEN_MIGRATION.md](AUDIT_TIZEN_MIGRATION.md) for overview
2. Review [MIGRATION_MAP.md](MIGRATION_MAP.md) for file mappings
3. Check [PHASE1_IMPLEMENTATION.md](PHASE1_IMPLEMENTATION.md) for data layer

**Implementation Guide**:
1. Phase 1: Data persistence setup
2. Phase 2: Business logic & DI
3. Phase 3: Player UI (critical)
4. Phase 4: Extra features (optional)
5. Phase 5: Testing & CI/CD

**Code Review**:
- `app/src/main/java/com/btv/data/` - Room entities & repositories
- `app/src/main/java/com/btv/domain/` - UseCases
- `app/src/main/java/com/btv/ui/` - Compose screens & ViewModels
- `app/src/main/java/com/btv/di/` - Hilt dependency injection

## Contributing

When adding new features:
1. Update relevant phase documentation
2. Add entries to this INDEX.md
3. Update project statistics
4. Run all tests before committing

## Timeline

| Phase | Duration | Status |
|-------|----------|--------|
| 1 | 4-5h | ✅ Complete |
| 2 | 5-6h | ✅ Complete |
| 3 | 6-8h | 🔄 Testing |
| 4 | 2-3h | ⏭️ Optional |
| 5 | 4-5h | ⏭️ Planned |
| **Total** | **21-27h** | **~ 60%** |

---

Last Updated: 2026-09-20  
Next Focus: Phase 3 compilation fix → Phase 4 features
