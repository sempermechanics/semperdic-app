# Quality program: baseline (2026-10-01)

The whole-app quality and readability program (bug fixes, package layout,
shared abstractions, ViewBinding, smaller files) is measured against this
snapshot of `main` @ `ff0cfc3d`. Regenerate with:

```bash
python scripts/quality_metrics.py <checkout> <out_prefix>
```

Line coverage (`./gradlew :app:koverLog`): **57.8 %** (floor 49).
Counts are approximate (regex and brace counting); compare runs of the script
with each other only. "Non-invariant" excludes the files CLAUDE.md says not to
split (VisualizationEngine, ReportBuilder, GifEncoder, ScrubFrameCache,
PointSpatialIndex).

| Metric | Value |
|---|---|
| kotlin_files | 223 |
| kotlin_lines | 43037 |
| code_lines_normalised | 23271 |
| mean_file_lines | 193.0 |
| files_over_300 | 39 |
| files_over_500 | 20 |
| files_over_500_non_invariant | 18 |
| largest_file | ('ui/analysis/StaticAnalysisActivity.kt', 1834) |
| max_files_in_package | 54 |
| files_with_@file:Suppress | 80 |
| functions | 1843 |
| functions_over_60_lines | 47 |
| functions_over_100_lines | 15 |
| functions_with_7plus_params | 37 |
| duplicated_6line_blocks | 83 |
| duplicated_lines | 584 |
| duplication_pct | 2.51 |
| layout_xml_files | 36 |
| layout_xml_lines | 6283 |
| findViewById | 416 |
| lateinit var | 183 |
| Toast.makeText | 65 |
| CrispToast.show | 13 |
| generic catch (Exception/Throwable) | 59 |
| CancellationException mentions | 31 |
| Dispatchers.IO | 97 |
| !! (non-null assertions) | 4 |
| MaterialAlertDialogBuilder/AlertDialog.Builder | 31 |
| Pair</Triple< types | 91 |
| Timber.e/w | 185 |
| String.format(Locale | 32 |
| @Suppress LongParameterList (file / inline) | 20 / 25 |
| @Suppress ReturnCount (file / inline) | 29 / 35 |
| @Suppress CyclomaticComplexMethod (file / inline) | 19 / 4 |
| @Suppress LongMethod (file / inline) | 17 / 3 |
| @Suppress LargeClass (file / inline) | 5 / 1 |
| @Suppress TooManyFunctions (file / inline) | 27 / 11 |
| @Suppress MagicNumber (file / inline) | 56 / 3 |
| @Suppress TooGenericExceptionCaught (file / inline) | 14 / 34 |
| @Suppress NestedBlockDepth (file / inline) | 9 / 2 |
| @Suppress ComplexCondition (file / inline) | 6 / 0 |
| test: files / lines / @Test / Thread.sleep / @Config(sdk | 142 / 20301 / 1125 / 5 / 72 |
| androidTest: files / lines / @Test / Thread.sleep / @Config(sdk | 17 / 3199 / 65 / 13 / 0 |

## Top 25 files

| File | Lines |
|---|---|
| ui/analysis/StaticAnalysisActivity.kt | 1834 |
| ui/viewer/ResultViewerActivity.kt | 1442 |
| data/DicUploadWorker.kt | 1021 |
| data/CloudRestore.kt | 912 |
| ui/analysis/AnalysisViewModel.kt | 902 |
| ui/analysis/VsgPlotView.kt | 842 |
| ui/analysis/SweepSetupHelper.kt | 820 |
| ui/settings/SettingsActivity.kt | 817 |
| ui/home/HomeActivity.kt | 802 |
| ui/viewer/ShareCenter.kt | 781 |
| data/net/SemperApi.kt | 775 |
| ui/analysis/VsgLatticeActivity.kt | 775 |
| data/AuthRepository.kt | 721 |
| ui/analysis/StudioOverlayView.kt | 721 |
| report/VisualizationEngine.kt | 656 |
| ui/auth/AuthActivity.kt | 608 |
| data/net/DriveTransfer.kt | 519 |
| data/SessionStore.kt | 518 |
| report/ReportBuilder.kt | 514 |
| data/CloudSync.kt | 511 |
| ui/analysis/SubsetRecommender.kt | 474 |
| ui/viewer/TouchImageView.kt | 468 |
| data/SessionZip.kt | 465 |
| ui/analysis/DicBatchRunner.kt | 455 |
| ui/analysis/NoiseFloorStats.kt | 454 |

## Top 20 longest functions

| Function | File:line | Lines | Params |
|---|---|---|---|
| doWork | data/DicUploadWorker.kt:383 | 513 | 1 |
| runBatchAnalysisBody | ui/analysis/DicBatchRunner.kt:40 | 385 | 3 |
| onCreate | ui/analysis/StaticAnalysisActivity.kt:169 | 306 | 1 |
| downloadFile | data/net/DriveTransfer.kt:250 | 216 | 6 |
| onCreate | ui/viewer/ResultViewerActivity.kt:265 | 188 | 1 |
| onCreate | ui/analysis/RoiDrawActivity.kt:70 | 175 | 1 |
| stageCsvAndBundles | data/SessionUploadBundler.kt:62 | 165 | 10 |
| bakeAnnotationsToCanvas | report/ReportBuilder.kt:361 | 153 | 14 |
| buildReport | report/ReportBuilder.kt:211 | 147 | 1 |
| onCreate | ui/home/HomeActivity.kt:141 | 145 | 1 |
| handle | ui/analysis/AnalysisDeformedBatchHelper.kt:31 | 136 | 4 |
| generateHeatmapIndices | report/VisualizationEngine.kt:327 | 119 | 8 |
| onTouchEvent | ui/analysis/StudioOverlayView.kt:436 | 110 | 1 |
| runVsgSweepBody | ui/analysis/AnalysisViewModel.kt:408 | 105 | 2 |
| doWork | data/DicBundleDownloadWorker.kt:41 | 102 | 1 |
| extract | ui/analysis/AnalysisVideoExtractHelper.kt:35 | 100 | 10 |
| firebaseThen | data/AuthRepository.kt:437 | 98 | 2 |
| showVideoSamplingDialog | ui/analysis/StaticAnalysisActivity.kt:849 | 93 | 2 |
| onDraw | ui/analysis/VsgPlotView.kt:527 | 83 | 1 |
| generateDeformedHeatmapIndices | report/VisualizationEngine.kt:477 | 78 | 8 |

## Packages

| Package | Files | Lines |
|---|---|---|
| (root) | 9 | 748 |
| analytics | 1 | 85 |
| data | 50 | 8805 |
| data/net | 16 | 2794 |
| imaging | 9 | 1309 |
| navigation | 1 | 35 |
| report | 12 | 3126 |
| ui/admin | 1 | 143 |
| ui/analysis | 54 | 13937 |
| ui/auth | 8 | 1409 |
| ui/common | 18 | 1905 |
| ui/home | 6 | 1631 |
| ui/limit | 2 | 232 |
| ui/settings | 10 | 1667 |
| ui/viewer | 20 | 5004 |
| util | 6 | 207 |
