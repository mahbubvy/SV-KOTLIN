# Camera folder scrolling performance

Request: reduce slight scrolling lag with more than 30 photos/videos. Measurements
use Pixel 5, 96 generated items (64 photos, 32 videos), encrypted originals and
512 px encrypted previews. Existing private media is filtered out of the rendered
test screen. Each trial uses fresh fixture IDs and a fresh Coil memory cache.
Owned rows and files are removed afterward. No camera captures or screenshots.

## Measurement

`GalleryScrollPerformanceDeviceTest` runs three trials of four upward and four
downward drags. It measures Window FrameMetrics TOTAL_DURATION, excluding first
draw frames, and reports p95 frame time. `over16msPct` counts frames over a fixed
16.67 ms threshold; it is not Android's full system jank metric. Layout, draw,
sync and GPU p95 values help identify where time goes. Gallery flow emissions
are counted, not database query executions.

`current` previews already use `.gallery2.thumb`; `legacy` previews trigger lazy
upgrades. Code/JIT warming persists between trials, although image caches are
fresh. Device scheduling, temperature and background load are uncontrolled.
Small differences are insufficient evidence of improvement.

## Experiment ledger

| Attempt | p95 ms, three trials | Verdict |
|---|---|---|
| Debug baseline, current previews | 44.99 / 26.25 / 23.06 | Reference; median 26.25 ms |
| Repeated baseline with phase metrics | 45.87 / 34.49 / 22.81 | Reference; meaningful run-to-run variance |
| Debug baseline, legacy previews | 47.13 / 33.01 / 24.36 | Reference; median 33.01 ms |
| Two concurrent encrypted thumbnail decoders | 43.15 / 26.42 / 20.77 | Reverted: median 26.42 ms does not beat noise |
| Fit decoded previews to physical tile width | 44.95 / 28.68 / 22.18 | Reverted: no reliable total-frame improvement |
| Defer legacy upgrades until 500 ms idle | 45.94 / 29.17 / 25.05 | Reverted: less update work, insufficient frame-time evidence |
| Non-debuggable R8 build | No completed optimized frame measurement | Retained at owner's request: installed app feels much smoother; owner asked to stop testing |

Phase measurements show low layout p95 (about 0.1 ms), draw p95 around 3 ms, GPU
p95 around 7-8 ms. These separate percentile values cannot be summed to explain
TOTAL_DURATION. There is a pronounced first-trial penalty even with current
previews, so thumbnail regeneration alone does not explain the lag.

## Optimized build experiment

Android recommends evaluating Compose with debugging disabled and R8 enabled:
[Compose performance](https://developer.android.com/develop/ui/compose/performance).
The optional `performance` variant uses the existing debug certificate so it can
replace the installed personal-use build without clearing the vault. Ordinary
debug/release configurations remain separate.

In-process instrumentation shares target-app dependencies. R8 initially removed
`androidx.tracing.Trace` and `kotlin.LazyKt`, causing the runner to fail before
tests. Scoped rules preserve shared runtime APIs and public app entry points
while permitting method-body optimization. Compiler-only Error Prone annotation
references are suppressed only in test rules.

Build:

```powershell
.\gradlew.bat assemblePerformance assemblePerformanceAndroidTest -PsvPerformanceTest
```

Run on an unlocked Pixel with the installed target/test APKs:

```powershell
adb shell am instrument -w -r `
  -e class com.secretvault.app.ui.GalleryScrollPerformanceDeviceTest `
  -e previewMode current -e profileLabel optimized `
  com.secretvault.app.test/androidx.test.runner.AndroidJUnitRunner
```

Raw local logs are in ignored `scratch/gallery-perf-*.log`.

Installed on Pixel 5:
`app/build/outputs/apk/performance/SecretVault-v1.1.1-performance-performance.apk`.
SHA-256: `E0789722C31E634A65C69C00D4C33F6F7B2C24C95A6AB2C7FD83D71B23BF12E7`.
Future builds intended to retain this configuration should use
`assemblePerformance`, rather than `assembleDebug`.

## Current verification

- Existing debug unit suite: 120 tests, zero failures.
- Optimized app and instrumentation APKs build successfully.
- Optimized runner reaches the test successfully after the shared-runtime fixes.
  The scrolling test then stops at its Android keyguard precondition, before
  fixtures or measurements. No completed optimized timings or optimized device
  regression suite are claimed.
- Owner feedback: "it feels alot smoother than before so you can stop now".
  Testing stopped as requested. This is subjective field confirmation, not a
  quantified speedup or guarantee of 60 FPS. It overrides the skill's default
  requirement to remeasure before retaining an optimization.
- No source UI changes are retained from the reverted experiments. Existing
  dark/mint direction and gallery layout remain as recorded in
  `gallery-slider-improvements-ui-gate.md`.

## Scoped review and delivery gate

- Correctness PASS: both APKs build; installed app runs and owner reports smoother
  scrolling. Existing debug tests pass. Broader R8 runtime regression testing is
  explicitly incomplete because the owner stopped testing.
- Readability PASS: separate build variant and two small rule files describe the
  shared instrumentation dependencies; no production decoder experiments remain.
- Architecture PASS: build configuration only; no media schema or key format
  changes, new runtime dependency, or extra cache introduced.
- Security PASS: existing SQLCipher keep rules inherited; no credentials in the
  patch; same signing certificate permits installation without clearing vault
  data. The personal-use debug certificate is not a public distribution key.
- Performance evidence PASS: retained build has explicit owner field confirmation;
  numeric results only refer to completed baseline/reverted-experiment runs.
- UI scope PASS: no visual changes retained; existing gallery layout, loading,
  empty/error states, dark/mint palette and restrained dials remain intact. The
  preceding UI gate supplies design evidence; no new whole-app audit is claimed.
- Delivery honesty PASS: no percentage/FPS gain claimed; owner requested stopping
  tests and keeping the installed smoother build. No further device actions run.

If lag returns, run the same generated-media benchmark on an unlocked phone with
the optimized variant before changing decoding or thumbnail quality again. Add a
repeatable median frame-time budget only after an optimized baseline exists.
