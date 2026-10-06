# Phase 5: Testing Strategy

## Testing Pyramid

```
         /\
        /  \  UI Tests (Espresso, Compose)
       /----\
      /      \
     /  Unit  \ Unit Tests (JUnit, MockK)
    /          \
   /__________\
   Integration Tests (Room, API mocks)
```

## 1. Unit Tests

### Repository Tests
**File**: `src/test/java/com/btv/data/repository/`

#### FavoritesRepositoryTest
```kotlin
class FavoritesRepositoryTest {
    // Test getAllFavorites() returns favorites
    // Test isFavorite() for known/unknown streams
    // Test addFavorite() inserts correctly
    // Test removeFavorite() deletes correctly
    // Test getFavoritesByType() filters by type
}
```

#### PlaybackProgressRepositoryTest
```kotlin
class PlaybackProgressRepositoryTest {
    // Test saveProgress() updates position
    // Test getProgress() returns correct position
    // Test markAsCompleted() sets completion flag
    // Test getInProgress() filters incomplete items
    // Test updateProgress() on existing entries
}
```

#### HistoryRepositoryTest
```kotlin
class HistoryRepositoryTest {
    // Test addToHistory() increments viewCount on duplicate
    // Test getHistory() returns items in correct order
    // Test getGroupedHistory() filters series/movies
    // Test clearOlderThan() removes old entries
}
```

### UseCase Tests
**File**: `src/test/java/com/btv/domain/usecase/`

#### GetFavoritesUseCaseTest
```kotlin
class GetFavoritesUseCaseTest {
    // Test getAllFavorites() via repository
    // Test getFavoritesByType() filters correctly
    // Test isFavorite() checks existence
    // Test Flow emission on data changes
}
```

#### ToggleFavoriteUseCaseTest
```kotlin
class ToggleFavoriteUseCaseTest {
    // Test execute() adds new favorite
    // Test execute() removes existing favorite
    // Test returns correct boolean state
}
```

#### GetPlaybackProgressUseCaseTest
```kotlin
class GetPlaybackProgressUseCaseTest {
    // Test saveProgress() persists to DB
    // Test getProgress() returns Flow stream
    // Test markAsCompleted() flags item
    // Test shouldResumePlayback() logic
    // Test getResumePosition() returns saved position
}
```

### ViewModel Tests
**File**: `src/test/java/com/btv/ui/`

#### BrowseViewModelTest
```kotlin
class BrowseViewModelTest {
    // Test selectCategory() updates state
    // Test selectContent() triggers tracking
    // Test toggleFavorite() adds/removes favorite
    // Test loadFavorites() populates favorite IDs
    // Test loadInProgressItems() fetches in-progress content
}
```

#### PlayerViewModelTest
```kotlin
class PlayerViewModelTest {
    // Test loadStream() sets media item
    // Test play() / pause() / togglePlayPause()
    // Test seekTo() / seekForward() / seekBackward()
    // Test error handling and retry logic
    // Test progress tracking interval
    // Test saveProgress() called periodically
    // Test max retries exceeded scenario
}
```

## 2. Integration Tests

**File**: `src/androidTest/java/com/btv/`

### Database Tests
```kotlin
class BtvDatabaseTest {
    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()
    
    // Test all entities insert/query correctly
    // Test foreign key constraints
    // Test cascading deletes
    // Test transaction integrity
}
```

### Repository + DB Tests
```kotlin
class FavoritesRepositoryIntegrationTest {
    // Test full insert → query → delete flow
    // Test concurrent access
    // Test index performance
}
```

## 3. Compose UI Tests

**File**: `src/androidTest/java/com/btv/ui/`

### BrowseScreenTest
```kotlin
@RunWith(AndroidJUnit4::class)
class BrowseScreenTest {
    @get:Rule val composeTestRule = createComposeRule()
    
    // Test category selection updates content
    // Test content grid displays items
    // Test favorite button toggle works
    // Test navigation to player on select
    // Test sidebar responsive to D-Pad
    // Test focus management with arrow keys
}
```

### PlayerScreenTest
```kotlin
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {
    @get:Rule val composeTestRule = createComposeRule()
    
    // Test play/pause buttons work
    // Test seek controls update position
    // Test tap toggles control visibility
    // Test error message displays on failure
    // Test retry button triggers reload
    // Test progress bar updates
}
```

## Test Data & Mocks

### MockData.kt
```kotlin
object MockData {
    val mockFavorites = listOf(
        FavoritesEntity("stream1", "movie", "Action Film", ...),
        FavoritesEntity("stream2", "series", "Comedy Show", ...)
    )
    
    val mockHistory = listOf(
        HistoryEntity("stream1", "movie", "Last Watched", ...)
    )
    
    val mockProgress = listOf(
        PlaybackProgressEntity("stream1", 15000, 90000, 16.67f)
    )
}
```

### FakeRepository.kt
```kotlin
class FakeFavoritesRepository : FavoritesRepository {
    private val favorites = mutableListOf<FavoritesEntity>()
    
    override fun getAllFavorites() = flow { emit(favorites) }
    override suspend fun addFavorite(entity: FavoritesEntity) { ... }
    // ... implement interface methods
}
```

## Test Coverage Goals

| Module | Target | Priority |
|--------|--------|----------|
| Repository Layer | 90%+ | Critical |
| UseCase Layer | 85%+ | Critical |
| ViewModel | 80%+ | High |
| UI Screens | 70%+ | Medium |
| **Overall** | **80%+** | - |

## Running Tests

### Unit Tests
```bash
./gradlew test
./gradlew testDebugUnitTest
```

### Instrumentation Tests
```bash
./gradlew connectedAndroidTest
./gradlew connectedDebugAndroidTest
```

### With Coverage
```bash
./gradlew testDebugUnitTestCoverage
./gradlew connectedDebugAndroidTestCoverage
```

### Specific Test Class
```bash
./gradlew testDebugUnitTest --tests com.btv.data.repository.FavoritesRepositoryTest
```

## CI/CD Integration

### GitHub Actions Workflow
```yaml
name: Tests
on: [push, pull_request]
jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
      - run: ./gradlew test
  
  instrumentation-tests:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
      - uses: reactivecircus/android-emulator-runner@v2
      - run: ./gradlew connectedAndroidTest
```

## Test Dependencies

### build.gradle.kts
```kotlin
dependencies {
    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    
    // Instrumentation Testing
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    
    // Code Coverage
    testImplementation("io.mockk:mockk-agent:1.13.5")
}
```

## Success Criteria

- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ 80%+ code coverage
- ✅ No flaky tests
- ✅ CI/CD green on all branches
- ✅ Espresso tests pass on physical device or emulator
