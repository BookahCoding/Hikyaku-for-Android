# Reproducible chat performance

`:benchmark` contains Android Macrobenchmark 1.5.0 journeys and an app-specific Baseline Profile generator. `androidApp` bundles the reviewed generated source-name rules in `src/main/baseline-prof.txt`; AGP rewrites them through R8 and merges library profiles. ProfileInstaller 1.4.1 handles installation. Generation is explicit: ordinary app assembly and existing CI checks never require a connected device.

## Targets and fixture boundaries

- `benchmark`: release-derived, non-debuggable, R8/resource shrinking enabled, debug-signed. Separate `dev.herald.android.benchmark` data. Used for timing and rendering assertions.
- `profile`: same isolated app and fixtures, without R8/resource shrinking so generation emits source-name rules. Used only to collect profiles, never for timing comparisons.
- The fixture activity and synthetic data live only under `androidApp/src/benchmark`. The `profile` source set explicitly includes them. Normal debug/dev/release manifests do not expose this activity, and release R8 removes the unused preview adapter.
- Both fixture targets remove the INTERNET permission. They retain the real application initialization and real `ChatView`, but feed state locally through the existing screenshot preview's no-op actions. No credentials, real chats, gateway calls, database loading, WebSocket delivery, or session navigation/repository latency are exercised.
- Each session has 120 messages. Every tenth answer contains long Markdown with headings, lists, fenced Kotlin code and inline math. The scroll journey makes three backward and three forward swipes. Streaming publishes 240 immutable state updates at 20 ms intervals while the same scroll journey runs. Rapid transcript switching changes among three synthetic identities six times using the fixture control; it waits for the title only, so later switches can cancel unfinished asynchronous preparation. It does not measure latency to fully rendered session content.
- Cold startup measures first display of this synthetic chat activity, including real application initialization, not the production launcher/sign-in route or time to a fully parsed transcript. Session timings cover transcript replacement/rendering, not opening the real sidebar or fetching history.

The generator exercises all four paths and filters out synthetic activity/preview rules. It keeps app-specific `dev.hermeskotlin` rules; AndroidX and other dependencies provide their own library profiles. The manual collection/copy workflow deliberately avoids adding another Gradle plugin compatibility dependency to AGP 9.4.1.

The checked-in profile was generated on 2026-11-09 using the API 34 x86_64 GymPalTest emulator (360 dp, font scale 1.0), with `maxIterations=10` and `stableIterations=2`. The collector declared stability at iteration seven. It contains 3,186 rules, including real application initialization, chat and Markdown paths; the canonical generated text was copied unchanged. SHA-256: `a67405be017cc1c9565d2ec039508f8a01dee1d220ca659f1de306d4bf5ba686`. This proves collection, not phone performance; regenerate after meaningful changes to these paths.

## Build and generate

Use JDK 17+, SDK 37, and one connected API 33+ device for unrooted collection (or a supported rooted device). Use a physical device for meaningful performance conclusions. On Windows remove stray quote characters from PATH before invoking Gradle.

```powershell
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:PATH = $env:PATH.Replace('"', '')
./gradlew.bat :androidApp:assembleBenchmark :androidApp:assembleProfile :benchmark:assembleBenchmark :benchmark:assembleProfile
./gradlew.bat :benchmark:connectedProfileAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.herald.benchmark.BaselineProfileGenerator'
```

The generated `BaselineProfileGenerator_generate-baseline-prof.txt` is under `benchmark/build/outputs/connected_android_test_additional_output/profile/` (possibly in a device subdirectory). Review it, verify it contains real chat/designsystem/startup classes and no `PerformanceFixture` or `/preview/` rules, then copy the single generated file:

```powershell
$generated = @(Get-ChildItem benchmark/build/outputs/connected_android_test_additional_output/profile -Recurse -Filter '*-baseline-prof.txt')
if ($generated.Count -ne 1) { throw 'Select exactly one device profile before copying' }
Copy-Item -LiteralPath $generated[0].FullName -Destination androidApp/src/main/baseline-prof.txt
./gradlew.bat :androidApp:assembleBenchmark :androidApp:assembleRelease
```

Commit that text profile and its provenance alongside source changes. Keep traces, raw generated outputs, APKs, JSON and connected-test reports under ignored `build/` directories. Confirm the built release APK contains `assets/dexopt/baseline.prof` and `baseline.profm`; `CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0)` must also pass below, so missing/uninstallable profiles cannot silently fall back.

## Measure and verify

```powershell
./gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.herald.benchmark.RenderingCorrectness'
./gradlew.bat :benchmark:connectedBenchmarkAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.herald.benchmark.ChatBenchmarks' '-Pandroid.testInstrumentationRunnerArguments.iterations=10'
```

For emulator diagnostics only, append `'-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR'`. Do not suppress errors for phone measurements. The default is three iterations for a quick diagnostic; increase to at least ten and repeat paired runs on a stable physical device before interpreting a change. Keep device, OS, density, font scale, thermal state, build source and background workload fixed.

Each journey runs with no compilation (`CompilationMode.None`) and with the bundled profile required (partial compilation, no warmup). These are compilation-mode comparisons of identical final source, not a before/after comparison of the rendering changes or of an APK without any bundled library profile. Benchmarks reset compilation between modes. Read cold-start `timeToInitialDisplayMs` and frame `frameDurationCpuMs`/`frameOverrunMs` distributions from `benchmark/build/outputs/connected_android_test_additional_output/benchmark/` JSON and Perfetto traces. Frame distributions include fixture controls and accessibility interaction costs; startup is only TTID.

Use a short checkout path on Windows. Long trace basenames can reach the Windows 260-character path limit in nested worktrees: AGP may report successful tests while failing to pull individual traces. Check the copy diagnostics as well as the test XML/JSON; device-side additional output is removed when the test APK is uninstalled.

The correctness test streams quickly into a >2,048-character plain paragraph, then replaces a different long math source on the next frame. It asserts exact full rendered final/replacement text through accessibility, absence of old source text at settled checkpoints, and that the replacement remains readable after scrolling. These settled plain-text assertions do not cover rich code/math suffix correctness, unchanged-text completion, or every transient frame; inspect traces/screenshots when investigating flicker.

See [the Android Macrobenchmark guide](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview), [Baseline Profile configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles), and [profile rewriting support](https://developer.android.com/topic/performance/baselineprofiles/overview).
