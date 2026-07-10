# Android Audit Review and Push - 2026-07-10

## Objective

Review the uncommitted Android audit fixes based on `32c8c756`, resolve every
blocking finding, run the staged Gradle gates, commit only Readflow-owned files,
push `main`, and confirm the Android Dev Release workflow and OTA artifact.

## Scope

- Android app, feature, core, extension, render, manifest, and Gradle changes
  documented in `docs/android-code-audit-2026-07-10.md`.
- Focused regression tests added with the audit fixes.
- Tracking updates required to preserve review and release evidence.
- Web and HarmonyOS have no implementation changes in this batch.

## Excluded From Commit

- Minecraft/Fabric/Velocity files at the repository root.
- `reasonix.toml`.
- Generated duplicate sources under `android/core/model/bin/`.

## Live Plan

- [x] Recover the current worktree, objective, and release boundary.
- [x] Complete independent data/security and app/lifecycle reviews.
- [x] Resolve all Critical and Important findings.
- [x] Pass phase 1, phase 2, and phase 3 Gradle gates plus whitespace checks.
- [x] Stage only the reviewed Readflow files and verify the staged diff.
- [x] Commit with an explicit three-platform status.
- [x] Push `main` and confirm CI plus the `dev-latest` OTA artifact.

## Risks

- The worktree contains unrelated untracked projects and large binaries that
  must never be included by a broad `git add -A`.
- The audit changes span reader lifecycle, database transactions, Calibre
  networking, OTA, and deletion semantics, so a green build alone is not a
  sufficient review signal.
- The development OTA path still embeds a long-lived GitHub credential; this
  remains a documented release blocker, not part of this development push.

## Review Outcome

- Independent review found no remaining Critical or Important finding after the
  backup ZIP, Reader close, and OTA stale-result fixes were re-reviewed.
- Remaining Minor: nested calls from one coroutine would overwrite the outer
  producer registration in `CoroutineBookAssetOperationCoordinator`; no current
  production call graph nests `produce()`.
- Remaining Minor: deletion-cancelled Calibre downloads may leave
  `LibraryViewModel.downloadingBookId` showing the old ID until later state work.
- Remaining Minor: the Calibre download-failure smoke does not yet exercise a
  true mid-stream interruption or match the actual `calibre-42-*.part` name.

## Release Evidence

- Implementation commit: `e59780ec40999e10b10ca70235250824cd958b11`.
- Push advanced `origin/main` from `32c8c7560082d745437e7eacb5baaacb24b9d550`
  to the implementation commit with no intervening upstream commit.
- `Android Dev Release` run
  [29065942672](https://github.com/kragcola/linreads/actions/runs/29065942672)
  completed successfully in 8m18s.
- Mutable release `dev-latest` became `Dev build #198`; its body records commit
  `e59780ec40999e10b10ca70235250824cd958b11`, branch `main`, and unique build tag
  `dev-198-e59780ec40999e10b10ca70235250824cd958b11`.
- The refreshed asset was created/updated at `2026-07-10T03:10:11Z/03:10:12Z`,
  is 9,849,169 bytes, and downloaded with SHA-256
  `8ccf6a088899ef3e06c862e94b6631829b83c51b1bd22d41591f2907a485a2c5`.
- The downloaded APK passes ZIP integrity and APK Signature Scheme v2 checks,
  reports package `dev.readflow`, and embeds the exact unique build tag in DEX.

## Test Ledger

| Time | Command / Check | Result | Conclusion |
| --- | --- | --- | --- |
| 2026-07-10 | `git status --short --branch` and untracked-file classification | PASS | Only reviewed Readflow files were staged; root game/server files, `reasonix.toml`, generated `android/core/model/bin/`, and Kotlin session files were excluded. |
| 2026-07-10 | `JAVA_HOME=/Library/Java/JavaVirtualMachines/microsoft-21.jdk/Contents/Home ./gradlew -Preadflow.phase=2 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.readflow.page05.EpubFlowAnchorRuntimeSmokeTest#epubFlowSystemBackFlushesLatestLocatorAndReadingSessionRuntime' --console=plain` | PASS, 1/1 on `readflow_test` AVD | Route-level `BackHandler` now waits for `closeBook()` before popping, so system Back flushes locator, engine state, and the reading session. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:testDebugUnitTest --tests 'dev.readflow.updater.UpdateDownloadIdentityTest' --console=plain` | RED: 2/3 failed; GREEN: 3/3 passed | Mutable `dev-latest` downloads now use the release-body BUILD_TAG, missing identity never reuses an APK, and commit text cannot override workflow metadata. |
| 2026-07-10 | `./gradlew :core:calibre:testDebugUnitTest --tests 'dev.readflow.core.calibre.CalibreDownloadPlannerTest' --console=plain` | PASS, 5/5 | Formal downloads override the 8-second probe timeout and stream the response channel directly into the staging file. |
| 2026-07-10 | `./gradlew :core:database:testDebugUnitTest --tests 'dev.readflow.core.database.CompleteBookDeletionStoreTest' --tests 'dev.readflow.core.database.LinReadsBackupExporterTest.exportRejectsManifestLargerThanRestoreLimit' --console=plain` | PASS, 7/7 after final test expansion | Complete deletion stages/restores book plus cover, recovers interrupted work by DB state, preserves an existing original, and oversized exports write no partial ZIP. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.readflow.page05.EpubFlowAnchorRuntimeSmokeTest#epubImportExtractsCoverWithAndroidXmlParserRuntime' --console=plain` | RED: missing `coverUrl`; GREEN: 1/1 passed on `readflow_test` AVD | Android-compatible secure XmlPullParser now extracts a namespaced EPUB3 cover through relative and percent-encoded paths. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :core:database:testDebugUnitTest :features:reader:testDebugUnitTest :core:ui:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` | PASS | Database and reader suites, radio semantics, mandatory backup transaction wiring, and instrumentation sources compile together. |
| 2026-07-10 | `./gradlew :core:database:testDebugUnitTest --tests 'dev.readflow.core.database.CompleteBookDeletionStoreTest' :features:library:testDebugUnitTest --tests 'dev.readflow.features.library.LibraryViewModelTest.deleteAllCancelsMatchingDownloadBeforeDeletingBook' --console=plain` | RED: 4 focused regressions failed | Confirmed that post-commit cancellation restores deleted files, one damaged recovery group blocks later groups, a stale Room URI leaves a completed download orphaned, and complete deletion does not cancel an in-flight same-book download. |
| 2026-07-10 | `./gradlew :core:database:testDebugUnitTest --tests 'dev.readflow.core.database.CompleteBookDeletionStoreTest' --tests 'dev.readflow.core.database.CoroutineBookAssetOperationCoordinatorTest' :features:library:testDebugUnitTest --tests 'dev.readflow.features.library.LibraryViewModelTest' --console=plain` | GREEN: 23/23 | Transaction outcome is resolved from Room state under `NonCancellable`; recovery failures are isolated by book; managed files are found by stable book ID; matching downloads are cancelled and joined while unidentified local imports finish before deletion. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest --console=plain` | RED on ordinary close failure and cancellation collision; GREEN: 77/77 | Reader close now contains ordinary persistence/engine failures, clears state exactly once, and preserves `CancellationException` even when cleanup also fails. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:testDebugUnitTest :app:compileDebugKotlin --console=plain` | RED on initial/repeated lifecycle gate; GREEN: 13/13 before notification gate expansion | Activity lifecycle `ON_START` now reaches the foreground update checker once per started interval and re-arms after `ON_STOP`. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:testDebugUnitTest --tests 'dev.readflow.updater.AppUpdateNotificationGateTest' --console=plain` | RED: 2/2 failed; GREEN: 2/2 passed | Making the notification path reachable exposed its permission boundary; Android 13+ denial and disabled notifications now skip posting, and posting failures remain contained. |
| 2026-07-10 | `./gradlew :extensions:api:testDebugUnitTest --console=plain` and `:extensions:api:lintDebug` | RED on underreported/declared oversized XML and archive-root fallback; GREEN: 7/7 plus lint | EPUB container/OPF parsing now caps actual decompressed bytes, rejects the limit plus one byte, and retains normalized legacy archive-root cover lookup. |
| 2026-07-10 | `./gradlew test lint -Preadflow.phase=2 --continue --console=plain` | PASS in 1m34s; 1094 actionable tasks | All phase 2 JVM variants and Android Lint completed without failure after the blocking fixes were integrated. |
| 2026-07-10 | `./gradlew test -Preadflow.phase=3 --continue --console=plain` | PASS; 705 actionable tasks | Phase 3 unit-test graph remains valid with the real `:ink` module. |
| 2026-07-10 | `./gradlew :app:assembleDebug -Preadflow.phase=1 --console=plain` | PASS; 186 actionable tasks | Phase 1 source-set DI and application wiring compile and package independently. |
| 2026-07-10 | `./gradlew :app:assembleDebug -Preadflow.phase=3 --console=plain` | PASS; 347 actionable tasks | Phase 3 app package builds with the integrated fixes. |
| 2026-07-10 | `./gradlew projects -Preadflow.phase=3 --quiet` | PASS | Project graph contains only the expected app/core/extensions/features/ink/render modules. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:assembleOta --console=plain` | PASS in 2m54s; 599 actionable tasks | Local signed OTA produced `app-ota.apk` (9.4 MiB), SHA-256 `db90c5995f8e3c6b4220145fc3ba1807754aff7ce1a527698d9591762dfa0e35`. |
| 2026-07-10 | `git diff --check` | PASS | Integrated tracked and new Readflow source changes contain no whitespace errors. |
| 2026-07-10 | `./gradlew :core:database:testDebugUnitTest --tests 'dev.readflow.core.database.LinReadsBackupExporterTest.restoreRejectsCompressedEntryBeforeManifestWithoutMutatingLocalData' --console=plain` | RED: 1/1 failed | The old restore scan drained a >16 MiB highly compressed entry before finding a later manifest and then mutated Room data. |
| 2026-07-10 | `./gradlew :core:database:testDebugUnitTest --tests 'dev.readflow.core.database.LinReadsBackupExporterTest' --console=plain` | GREEN | Restore now requires the first entry to be a non-directory `manifest.json`, rejects the preceding bomb before transaction/DAO work, and retains the 16 MiB manifest cap. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :features:reader:testDebugUnitTest` with the three late close regressions | RED: 3/3 failed; GREEN: 4/4 including cancellation preservation | Entry cancellation can no longer skip cleanup; ordinary persistence failures do not skip later snapshots or block replacement opens; cancellation remains observable after cleanup. |
| 2026-07-10 | `./gradlew -Preadflow.phase=2 :app:testDebugUnitTest --tests 'dev.readflow.updater.ForegroundUpdateCheckGateTest' --tests 'dev.readflow.updater.AppUpdateNotificationGateTest' --console=plain` | RED: missing cancel/latest guard; GREEN | STOP/dispose cancels the active job and the synchronized request-token guard rejects stale notification results. |
| 2026-07-10 | Independent data/security, Reader/Calibre, and app/OTA re-review | PASS; no remaining Critical/Important | All late blocking findings were confirmed closed; three explicit Minor items remain documented above. |
| 2026-07-10 | `JAVA_HOME=.../microsoft-21.jdk/Contents/Home ./gradlew test lint -Preadflow.phase=2 --continue --console=plain` | PASS in 29s; 1094 actionable tasks | Final Phase 2 tests and Android Lint pass after every late review fix. |
| 2026-07-10 | `JAVA_HOME=.../microsoft-21.jdk/Contents/Home ./gradlew test -Preadflow.phase=3 --continue --console=plain` | PASS in 3s; 705 actionable tasks | Final Phase 3 unit-test graph remains valid. |
| 2026-07-10 | Final `:app:assembleDebug` for Phase 1 and Phase 3 | PASS in 4s each; 186 / 347 actionable tasks | Both source-set boundaries package after the late fixes. |
| 2026-07-10 | Final `projects -Preadflow.phase=3 --quiet` | PASS | Only the expected app/core/extensions/features/ink/render modules are configured. |
| 2026-07-10 | Final `-Preadflow.phase=2 :app:assembleOta --console=plain` | PASS in 1m51s; 599 actionable tasks | Local OTA is 9,849,169 bytes; SHA-256 `eb9cb1ad5ad5c8a7539800ca9dcd04692e4be89abcd31c6bfacd08a4a53a7965`. |
| 2026-07-10 | Final `git diff --check` | PASS | Final reviewed worktree contains no whitespace errors before staging. |
| 2026-07-10 | Commit `e59780ec40999e10b10ca70235250824cd958b11` and `git push origin main` | PASS | The reviewed 62-file Android audit batch reached `origin/main`; excluded root projects and generated files remained untracked. |
| 2026-07-10 | `gh run watch 29065942672 --exit-status --interval 10` | PASS in 8m18s | `Android Dev Release` built and published `Dev build #198`; only a non-blocking Node 20 action deprecation annotation was emitted. |
| 2026-07-10 | `gh release view dev-latest`, asset API metadata, download, `unzip -t`, `apksigner verify`, DEX `strings` | PASS | Release commit/branch/build tag match; remote APK is 9,849,169 bytes, SHA-256 `8ccf6a088899ef3e06c862e94b6631829b83c51b1bd22d41591f2907a485a2c5`, v2-signed, intact, and embeds `dev-198-e59780ec40999e10b10ca70235250824cd958b11`. |
