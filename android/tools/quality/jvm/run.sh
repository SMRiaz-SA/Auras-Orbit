#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/../../.." && pwd)"
MAIN="$ANDROID_ROOT/app/src/main/java"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
mkdir -p "$TMPDIR/classes"
find "$MAIN/com/auras/orbit/providers" -maxdepth 1 -name '*.java' ! -name 'Cs3ProviderRegistry.java' -print > "$TMPDIR/sources.txt"
cat >> "$TMPDIR/sources.txt" <<EOF
$MAIN/com/auras/orbit/intent/RequestValidation.java
$MAIN/com/auras/orbit/ui/BoundedTaskExecutor.java
$MAIN/com/auras/orbit/ui/WeakPendingImageRequests.java
$MAIN/com/auras/orbit/ui/PageWindow.java
$MAIN/com/auras/orbit/ui/OrbitShellPerformanceCounters.java
$MAIN/com/auras/orbit/metadata/ActorMetadata.java
$MAIN/com/auras/orbit/metadata/ConservativeIdentityMatcher.java
$MAIN/com/auras/orbit/metadata/MetadataFieldPolicy.java
$MAIN/com/auras/orbit/metadata/MetadataDisplayProjection.java
$MAIN/com/auras/orbit/metadata/TitleCatalogRecord.java
$MAIN/com/auras/orbit/navigation/ShellRoute.java
$MAIN/com/auras/orbit/navigation/ShellNavigationRules.java
$MAIN/com/auras/orbit/navigation/ShellState.java
$MAIN/com/auras/orbit/player/PlaybackHandoffExpiry.java
$MAIN/com/auras/orbit/jellyfin/BoundedFutureExecutor.java
$MAIN/com/auras/orbit/jellyfin/JellyfinPageBounds.java
$MAIN/com/auras/orbit/jellyfin/JellyfinModels.java
$MAIN/com/auras/orbit/jellyfin/JellyfinPageAccumulator.java
$MAIN/com/auras/orbit/jellyfin/JellyfinResponseBudgets.java
$MAIN/com/lagradost/cloudstream3/subtitles/SubtitleArchiveGuard.java
$MAIN/com/auras/orbit/extensions/RepositoryCatalogLimits.java
$MAIN/com/lagradost/cloudstream3/ShowStatus.java
EOF
find "$HERE/src" -name '*.java' -print >> "$TMPDIR/sources.txt"
echo "$ANDROID_ROOT/app/src/test/java/com/auras/orbit/providers/ProviderCoordinatorBackpressureTest.java" >> "$TMPDIR/sources.txt"
javac --release 17 -encoding UTF-8 -d "$TMPDIR/classes" @"$TMPDIR/sources.txt"
java -Xmx512m -cp "$TMPDIR/classes" audit.OrbitJvmProbes
java -Xmx512m -cp "$TMPDIR/classes" audit.ProviderSchedulerRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.ArtworkSchedulerRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.RepositoryBudgetRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.JellyfinQueueRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.JellyfinPageBoundsRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.SubtitleArchiveGuardRegression

java -Xmx512m -cp "$TMPDIR/classes" audit.StandaloneJUnitRegressionRunner

java -Xmx512m -cp "$TMPDIR/classes" audit.ScreenLifecycleRegression

java -Xmx512m -cp "$TMPDIR/classes" audit.PageWindowRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.ActorFilmographyPagingRegression
java -Xmx512m -cp "$TMPDIR/classes" audit.ProviderResultsBudgetRegression
java -Xmx256m -cp "$TMPDIR/classes" audit.ModelAdmissionRegression
java -Xmx128m -cp "$TMPDIR/classes" audit.HomeSectionAdversarialAdmissionRegression
java -Xmx256m -cp "$TMPDIR/classes" audit.JellyfinServerMetadataRegression

java -Xmx256m -cp "$TMPDIR/classes" com.auras.orbit.jellyfin.JellyfinPageAccumulatorRegression

java -Xmx128m -cp "$TMPDIR/classes" com.auras.orbit.jellyfin.JellyfinResponseBudgetsRegression

java -Xmx256m -cp "$TMPDIR/classes" audit.ProviderLinkAdmissionRegression

java -Xmx256m -cp "$TMPDIR/classes" audit.ProviderHeaderAdmissionRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.ProviderCollectionAdmissionRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.ActorCreditCollectionRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.ProviderLinkFieldAdmissionRegression
java -Xmx256m -cp "$TMPDIR/classes" audit.ProviderDetailsMemoryCacheRegression
java -Xmx256m -cp "$TMPDIR/classes" audit.ShellPerformanceCountersRegression

java -Xmx256m -cp "$TMPDIR/classes" audit.MetadataPolicyRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.ShellNavigationStateRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.DetailsSingleFlightRegression

java -Xmx128m -cp "$TMPDIR/classes" audit.MetadataDisplayProjectionRegression
