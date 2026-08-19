# Project: Ame Player Material 3 Adaptive Refactoring

## Architecture
- **Adaptive Top-Level Navigation**:
  - Replaces legacy `TabRow` + `Scaffold` with Material 3 `NavigationSuiteScaffold` from `androidx.compose.material3:material3-adaptive-navigation-suite`.
  - On Compact screens (portrait phones, width < 600dp): Renders `NavigationBar` at the bottom.
  - On Medium & Expanded screens (landscape phones, foldables, tablets, width >= 600dp): Renders `NavigationRail` on the left.
  - Nested `Scaffold` provides `TopAppBar` and `MiniPlayerBar` (docked at bottom of content area, above bottom nav in compact, bottom of content in expanded).
  - Full-screen immersion for `PlayerScreen` and `SettingsScreen` remains uninterrupted.
- **Responsive Homepage & Multi-Column Layout**:
  - `UserHomepageView` `PlaylistGrid` refactored from hardcoded `GridCells.Fixed(3)` to `GridCells.Adaptive(minSize = minItemSize)` with responsive margins and spacing.
  - `DiscoverBlocksHomepage` and `DragonBallCard` adapted with responsive sizing via `BoxWithConstraints` to prevent stretching and optimize screen density.
  - `HomepageCache` maintained to guarantee cold-start-level instant tab and orientation switching without reloading.
- **Automated Test Harness**:
  - Local Compose UI testing via Robolectric (`4.14.1`) and `androidx.compose.ui:ui-test-junit4` running on JVM (`.\gradle-9-bin\gradle-9.5.0\bin\gradle.bat testDebugUnitTest`).
  - Tests simulate Compact (e.g. `w400dp-h800dp`), Medium (`w700dp-h900dp`), and Expanded (`w1024dp-h768dp`) configurations using `@Config(qualifiers = "...")` and assert presence of `NavigationRail` vs `NavigationBar`.

## Code Layout
- `gradle/libs.versions.toml`: Version catalog entries for M3 Adaptive, Robolectric, and Compose Testing.
- `app/build.gradle.kts`: Gradle build configuration, testOptions, and dependencies.
- `app/src/main/java/Akari/NCM/player/ui/screen/MainScreen.kt`: NavigationSuiteScaffold implementation, destination definitions, and top bar adjustments.
- `app/src/main/java/Akari/NCM/player/ui/screen/UserHomepageView.kt`: Responsive adaptive grid, dynamic card sizing, and HomepageCache preservation.
- `app/src/test/java/Akari/NCM/player/ui/AdaptiveNavigationTest.kt`: Automated Compose UI tests for adaptive navigation.
- `app/src/test/java/Akari/NCM/player/ui/HomepageAdaptiveGridTest.kt`: Automated Compose UI tests for homepage adaptive layout.
- `GEMINI.md`: Project documentation and architecture records.

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | M3 Adaptive Dependencies & Gradle Config | Add `material3-adaptive-navigation-suite`, `adaptive`, Robolectric, and Compose UI test dependencies | M1 (DONE) | Survey / R1 |
| 2 | NavigationSuiteScaffold Integration | Replace legacy Scaffold/TabRow with NavigationSuiteScaffold in `MainScreen.kt` | M1 (DONE) | ORIGINAL_REQUEST §R1 |
| 3 | NavigationRail in Medium/Expanded | Render NavigationRail on the left for width >= 600dp | M1 (DONE) | ORIGINAL_REQUEST §R1 |
| 4 | NavigationBar in Compact | Render NavigationBar at the bottom for width < 600dp | M1 (DONE) | ORIGINAL_REQUEST §R1 |
| 5 | MiniPlayerBar Docking Alignment | Ensure MiniPlayerBar docks above bottom navigation in Compact and at content bottom in Medium/Expanded | M1 (DONE) | Architecture Survey |
| 6 | Responsive PlaylistGrid in Homepage | Replace Fixed(3) with Adaptive(minSize = 130.dp) in `UserHomepageView.kt` | M2 (DONE) | ORIGINAL_REQUEST §R2 |
| 7 | Responsive DragonBall & Block Cards | Dynamic card sizing and spacing in DiscoverBlocksHomepage | M2 (DONE) | ORIGINAL_REQUEST §R2 |
| 8 | State Caching Preservation | Keep `HomepageCache` functioning smoothly during layout transitions | M2 (DONE) | GEMINI.md & Survey |
| 9 | Automated Compose UI Tests (Robolectric) | Automated tests asserting NavigationRail on Medium/Expanded and NavigationBar on Compact | M3 (DONE) | ORIGINAL_REQUEST §Acceptance Criteria |
| 10 | Automated Homepage Grid Tests | Automated tests verifying multi-column adaptive rendering across window sizes | M3 (DONE) | Acceptance Criteria & R2 |
| 11 | Full Build & Test Verification | `./gradle.bat assembleDebug` and `./gradle.bat testDebugUnitTest` 100% PASS | M4 | Acceptance Criteria |
| 12 | Project Documentation Update | Update `GEMINI.md` with M3 Adaptive architecture changes | M4 | Project Rules |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Material 3 Adaptive Navigation Architecture | `libs.versions.toml`, `app/build.gradle.kts`, `MainScreen.kt` | none | DONE |
| 2 | M2: UserHomepageView Responsive Multi-Column Layout | `UserHomepageView.kt` | M1 | DONE |
| 3 | M3: E2E Automated Compose UI Testing | `AdaptiveNavigationTest.kt`, `HomepageAdaptiveGridTest.kt` | M1, M2 | DONE |
| 4 | M4: Integration Verification, Hardening & Docs | Full build/test, `GEMINI.md` update | M1, M2, M3 | DONE |

## Interface Contracts
### MainScreen ↔ NavigationSuite
- Destinations:
  - Tab 0: "首页" / "Homepage" -> Icon: `Icons.Default.Home`, Composable: `UserHomepageView`
  - Tab 1: "歌单" / "Playlists" -> Icon: `Icons.AutoMirrored.Filled.QueueMusic`, Composable: `PlaylistsView`
- Layout Hierarchy:
  ```kotlin
  if (showPlayerScreen) {
      PlayerScreen(...)
  } else if (showSettingsScreen) {
      SettingsScreen(...)
  } else {
      NavigationSuiteScaffold(
          navigationSuiteItems = {
              item(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { ... }, label = { ... })
              item(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { ... }, label = { ... })
          }
      ) {
          Scaffold(
              topBar = { ... },
              bottomBar = { MiniPlayerBar(...) }
          ) { padding ->
              Box(Modifier.padding(padding)) { ... }
          }
      }
  }
  ```
### UserHomepageView ↔ Window Width Class
- Width < 600dp (Compact): `minSize = 110.dp` ~ 2-3 columns, DragonBall 120x160dp, Creative width 110dp.
- 600dp <= Width < 840dp (Medium): `minSize = 130.dp` ~ 4-5 columns, DragonBall 150x175dp, Creative width 135dp.
- Width >= 840dp (Expanded): `minSize = 140.dp` ~ 6-8 columns, DragonBall 180x195dp, Creative width 160dp.
