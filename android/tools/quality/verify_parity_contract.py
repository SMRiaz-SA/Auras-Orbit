#!/usr/bin/env python3
"""Explicit source-level contracts, NOT an Android runtime test."""
from pathlib import Path
r=Path(__file__).resolve().parents[3]
java=r/'android/app/src/main/java'
plugin=(java/'com/auras/orbit/extensions/CS3PluginRuntime.java').read_text()
start=plugin.index('    private ActivationResult activateLocked(')
end=plugin.index('    private ActivationResult activationFailure(',start)
actual=plugin[start:end]
assert 'return true;' in plugin[plugin.index('private static boolean isOrdinaryPluginRuntimeEnabled()'):][:180]
assert 'describePreparedArchiveFailure' not in actual, 'Static preflight still blocks ordinary activation'
assert 'preflightCompatibility(plugin)' not in actual, 'Static preflight still blocks ordinary activation'
assert 'PathClassLoader' in actual and 'transaction.commit();' in actual, 'Load or rollback path lost'
assert 'isStorageRecoveryReady()' in actual, 'Package recovery gating lost'
assert 'file.setReadOnly()' in actual, 'DEX read-only gating lost'
assert 'normalizationFailure' in actual and 'Log.w("OrbitCS3"' in actual, 'Optional transform fallback lost'
controller=(java/'com/auras/orbit/extensions/ExtensionOperationController.java').read_text()
assert 'isTargetPluginCrosswalkValidated()' not in controller, 'Plugin update flow still blocked by old global gate'
anime=(java/'com/lagradost/cloudstream3/AnimeLoadResponse.java').read_text()
helper=(java/'com/lagradost/cloudstream3/MainAPIKt.java').read_text()
assert 'MAX_EPISODES_PER_VARIANT' not in anime and 'MAX_TOTAL_EPISODES' not in anime
add=helper[helper.index('public static void addEpisodes('):helper.index('public static com.lagradost.cloudstream3.HomePageResponse newHomePageResponse(',helper.index('public static void addEpisodes('))]
assert 'merged.size() >=' not in add
model=(java/'com/auras/orbit/providers/MediaItem.java').read_text()
assert 'MAX_EPISODES = 25_000' in model
assert 'Provider episode collection exceeds the in-memory admission budget' in model
adapter=(java/'com/auras/orbit/providers/Cs3MediaProviderAdapter.kt').read_text()
assert 'totalEpisodes > MediaItem.MAX_EPISODES' in adapter
assert 'episodes.size > MediaItem.MAX_EPISODES' in adapter
assert 'indexOriginalEpisodes(originals)' in adapter
shell=(java/'com/auras/orbit/ui/OrbitShellActivity.java').read_text()
assert 'PageWindow.of(variant.getValue().size()' in shell and 'providerEpisodePage, 40' in shell
assert 'variant.getValue().subList(episodeWindow.start, episodeWindow.end)' in shell
assert 'provider_episode_previous_' in shell and 'provider_episode_next_' in shell
assert 'providerEpisodePage = offset / 40;' in shell
assert 'updated package will remain unloaded while' not in shell, 'Outdated crosswalk UI status remains'
for txt in ('activation','P4','P10'):
    assert txt in (r/'RECONCILED_IMPLEMENTATION_PROGRAM_2026-11-10.md').read_text()
print('CS3_PARITY_SOURCE_CONTRACT_PASS checks=18 (not Android runtime parity)')
