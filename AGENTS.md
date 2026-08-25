# Repository Guidelines

## Project Structure & Module Organization

This is a two-module Android project built with Gradle. `app/` contains the Kotlin application, UI, resources, AIDL interface, and libxposed entry point. Production code lives under `app/src/main/java/com/kieronquinn/app/simnumbersetter/`; Android resources are under `app/src/main/res/`; `app/src/main/resources/META-INF/xposed/` declares the API 102 module, entry point, and scope. `systemstubs/` provides compile-only Java stubs for hidden Android telephony APIs. Centralize plugin and library versions in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Use the checked-in Gradle wrapper; on Windows, run:

- `.\gradlew.bat assembleDebug` — compile both modules and create a debug APK.
- `.\gradlew.bat test` — run all local JVM unit tests.
- `.\gradlew.bat connectedAndroidTest` — run instrumentation tests on a connected device or emulator.
- `.\gradlew.bat lint` — run Android static analysis.
- `.\gradlew.bat clean` — remove generated build outputs.

Open the repository root in Android Studio for interactive development. Configure the Android SDK through the untracked `local.properties`; never commit machine-specific SDK paths.

## Coding Style & Naming Conventions

The Gradle daemon toolchain selects or provisions JDK 17. Follow the official Kotlin style configured in `gradle.properties`: four-space indentation, trailing commas where they improve diffs, and idiomatic null handling and coroutines. The app supports Android 12 (API 31) and newer. Use `PascalCase` for classes, `camelCase` for functions and properties, and `UPPER_SNAKE_CASE` for constants. Keep packages lowercase and within `com.kieronquinn.app.simnumbersetter`. Name Android resources in lowercase `snake_case` (for example, `include_main_loading.xml`). Keep repositories, services, and UI logic in their existing package layers.

## Testing Guidelines

The repository currently has no committed test suites. Add local tests under `app/src/test/` and device tests under `app/src/androidTest/`, mirroring production package paths. Name test classes after the subject, such as `MainViewModelTest`, and test methods by observable behavior. Before submitting changes, run `test` and `lint`; use a rooted Xposed-capable test device for changes that touch SIM writing, permissions, hidden APIs, or service binding. Never experiment on an irreplaceable SIM.

## Commit & Pull Request Guidelines

Recent history favors short, scoped prefixes such as `feat:`, `fix:`, `misc:`, and `release:`. Write imperative, focused commit subjects and separate unrelated changes. Pull requests should explain the user-visible effect, testing performed, Android/device details, and any root or Xposed assumptions. Link relevant issues and include screenshots for UI or localization changes. Do not commit APKs, signing keys, `local.properties`, IDE metadata, or generated build directories.
