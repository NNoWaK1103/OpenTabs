# iOS foundation

This branch develops an iOS app in the original repository. Android remains the existing Compose app consuming `:core`'s JVM variant; it does not switch UI frameworks or storage formats.

## Shared code and migration boundaries

`:core` now uses Kotlin Multiplatform, with JVM, iOS ARM64 device, ARM64 simulator, and x86_64 simulator targets. `commonMain` contains the unchanged original exercises, serializable `ViewerSettings`, the voice-command parser, and a small `ExerciseCatalog` API convenient for Swift. `lowercase()` replaces JVM `Locale.ROOT` in the parser: both use invariant casing. Common tests run on JVM and the host-compatible iOS simulator.

The existing `src/main/kotlin`, `src/test/kotlin`, and test resources explicitly map to `jvmMain` and `jvmTest`. JVM storage, Unicode search normalization, HTTP/cache/providers, URL policy, import/GP7 parsing, and integration interfaces stay there. Existing Android app APIs and the `:core:test` command are preserved. Test execution properties, including opt-in live providers, are applied to JVM test tasks.

Kotlin/serialization/Compose compiler plugins move together from 2.1.20 to 2.2.21 to support Xcode 26.0 with the existing AGP and Gradle versions. Android dependencies and SDK levels are unchanged. Compatibility references: [Kotlin's compatibility matrix](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html), [direct Xcode framework integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html).

## Run on a Mac

Requirements: JDK 17, Xcode 26.0 (26.0.1 is the compatible patch on the hosted image), an installed iOS simulator runtime, and XcodeGen 2.44.1. CI uses macOS 15 and pins XcodeGen source at `21ac9944b0ab546a07422dbed86f33dd2ebd76f8`. Hosted-image inventory changes over time; update the explicit Xcode selection and Kotlin together when retiring that toolchain.

From the repository root:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export DEVELOPER_DIR=/Applications/Xcode_26.0.app/Contents/Developer
xcodegen generate --spec iosApp/project.yml
./gradlew :core:test :core:iosSimulatorArm64Test -PiosOnly=true
bash .github/scripts/ios-tests.sh
open iosApp/OpenTabs.xcodeproj
```

On Intel Macs use `:core:iosX64Test`. Select the OpenTabs scheme and an iPhone or iPad simulator in Xcode. The first build downloads Kotlin/Native and dependencies. `project.yml` is the source of truth; the generated Xcode project is ignored. The pre-build script calls `:core:embedAndSignAppleFrameworkForXcode`, and framework search/link settings point to its output. The framework is static. There is no CocoaPods or remote binary dependency. `-PiosOnly=true` excludes the Android app from Gradle settings so an Android SDK is unnecessary for iOS work. Normal Android commands still include both modules.

The app supports iOS 16+, iPhone and iPad, dark appearance, native navigation, selectable monospace text, scrolling on both axes, and font size controls. It reads the shared original exercises; it does not persist user data. There are no network requests, microphone requests, or account flows in this preview.

For a physical device, set your own development team and a provisionable bundle identifier in `project.yml`, regenerate, and enable signing in Xcode. No team identity, production certificates, or App Store configuration is committed. Unsigned simulator checks require no Apple developer account.

## Validation

The Android workflow retains unit tests, lint, APK/instrumentation compilation, and the API 26/35 emulator jobs. Both workflows run on pushes to this branch, PRs, main, and manual dispatch. The iOS workflow runs existing JVM tests, common tests on Kotlin/Native, a device-framework compile, and an unsigned Xcode simulator build/UI test. The UI test opens First steps from the Kotlin catalog and verifies the native text viewer. Test reports and the Xcode result bundle are uploaded even on failure. Linux can validate the JVM/Android paths but cannot compile or run SwiftUI or Kotlin/Native Apple targets.

## Next phases

1. Define portable domain/storage contracts and migrate models plus search ranking with parity tests for accents, locale behavior, serialization, and existing JSON fixtures. Retain JVM adapters and Android API compatibility.
2. Introduce native storage/import adapters with bounded decoding, atomic writes, corruption recovery, and persistence tests before enabling editable iOS libraries. Keep Android library files and identifiers stable.
3. Add user-driven networking with platform HTTP adapters, cancellation, rights/cache controls, and deterministic provider fixtures. Migrate GP7 ZIP/XML parsing only with equivalent security/size-limit tests.
4. Build out SwiftUI library/search/practice/settings screens, then review WKWebView navigation policy and lifecycle independently of Android WebView. Add accessibility, rotation, iPad, physical-device, and background/foreground tests.

There is currently no iOS import, saved library, favorites, online search, website browser, auto-scroll, speech recognition, playback, Spotify, or music identification. Do not advertise Android feature parity or an App Store-ready app yet.

## Licensing decision remains with the owner

The inspected main revision (`c99f7929c4115508fb4b0452e21f2a4af40b404d`) has no root LICENSE/COPYING file, and GitHub reports no detected project license. This change does not add a license or relicense existing code/content. The repository owner should explicitly choose the project terms, confirm contributor consent as applicable, and review bundled exercise/content and dependency notices before public distribution. Existing provider-specific rights and attribution requirements in [PROVIDERS.md](PROVIDERS.md) remain separate from project licensing. No license selection is implied by creating an iOS branch.
