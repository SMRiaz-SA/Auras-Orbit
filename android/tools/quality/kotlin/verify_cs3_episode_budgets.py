#!/usr/bin/env python3
"""Compiles exact CS3 adapter episode conversion methods with minimal Kotlin contracts.
Measures scan work on malformed inputs; does not constitute full Android build.
"""
from pathlib import Path
import subprocess, tempfile
SOURCE = Path(__file__).resolve().parents[3] / 'app/src/main/java/com/auras/orbit/providers/Cs3MediaProviderAdapter.kt'
src = SOURCE.read_text()
def extract(prefix):
    assert src.count(prefix) == 1, (prefix, src.count(prefix))
    start = src.index(prefix)
    bracket = src.index('{', start)
    depth = 0
    for pos in range(bracket, len(src)):
        if src[pos] == '{': depth += 1
        elif src[pos] == '}':
            depth -= 1
            if depth == 0: return src[start:pos+1]
    raise AssertionError('unclosed Kotlin method')
assert 'const val MAX_EPISODE_SCAN = MediaItem.MAX_EPISODES * 4' in src
assert 'const val MAX_ANIME_GROUPS = 16' in src
methods = '\n\n'.join(extract(n) for n in [
    '    private fun toAnimeEpisodeItems(',
    '    private fun toEpisodeItems(',
    '    private class AnimeEpisodeGroup(',
])
# Exercise the production methods, not a hand-written duplicate of the loop.
kt = '''import java.util.Collections

enum class DubStatus { Dubbed, Subbed, Original }
class Episode(val valid: Boolean)
class MediaItem {
    companion object { const val MAX_EPISODES = 25_000 }
    data class EpisodeItem(val marker: Int)
}
class AnimeLoadResponse(val episodes: Map<DubStatus, List<Episode>>)
class Conversion {
    companion object {
        const val MAX_EPISODE_SCAN = MediaItem.MAX_EPISODES * 4
        const val MAX_ANIME_GROUPS = 16
    }
    var visited = 0
    fun checkCurrent() = Unit
    fun toEpisodeItem(parent: String, episode: Episode?, variant: String, index: Int): MediaItem.EpisodeItem? {
        visited++
        if (visited > MAX_EPISODE_SCAN + 10) throw IllegalStateException("unbounded episode scan")
        return if (episode?.valid == true) MediaItem.EpisodeItem(index) else null
    }
''' + methods + '''
    fun series(items: List<Episode>) = toEpisodeItems("series", items, "")
    fun anime(data: AnimeLoadResponse) = toAnimeEpisodeItems("anime", data)
}
fun main() {
    val invalid = List(20_000) { Episode(false) }
    val a = Conversion()
    check(a.series(invalid).isEmpty())
    check(a.visited <= Conversion.MAX_EPISODE_SCAN) { "series consumed ${a.visited} untrusted records" }
    val b = Conversion()
    check(b.anime(AnimeLoadResponse(mapOf(DubStatus.Dubbed to invalid))).isEmpty())
    check(b.visited <= Conversion.MAX_EPISODE_SCAN) { "anime consumed ${b.visited} untrusted records" }
    val c = Conversion()
    val many = List(3000) { Episode(it == 2500) }
    check(c.series(many).size == 1)
    check(c.visited == 3000)
    val d = Conversion()
    check(d.series(List(505) { Episode(true) }).size == 505)
    check(d.visited == 505)
    val e = Conversion()
    val animeSuccess = AnimeLoadResponse(mapOf(
        DubStatus.Dubbed to List(320) { Episode(true) },
        DubStatus.Subbed to List(320) { Episode(true) }))
    check(e.anime(animeSuccess).size == 640)
    check(e.visited == 640)
    val f = Conversion()
    val mixed = AnimeLoadResponse(mapOf(
        DubStatus.Dubbed to invalid,
        DubStatus.Subbed to List(5) { Episode(true) }))
    check(f.anime(mixed).size == 5) { "invalid first group must not starve playable second group" }
    check(f.visited <= Conversion.MAX_EPISODE_SCAN)
    val g = Conversion()
    check(g.series(List(1200) { Episode(true) }).size == 1200)
    check(g.visited == 1200)
    val overflow = List(25_001) { Episode(true) }
    val h = Conversion()
    check(runCatching { h.series(overflow) }.exceptionOrNull() is IllegalStateException)
    check(h.visited == 0)
    val j = Conversion()
    check(runCatching {
        j.anime(AnimeLoadResponse(mapOf(DubStatus.Dubbed to overflow)))
    }.exceptionOrNull() is IllegalStateException)
    check(j.visited == 0)
    println("CS3_EPISODE_BUDGET_PASS checks=18 extracted=production_scan_methods")
}
'''
with tempfile.TemporaryDirectory() as td:
    p = Path(td)/'Probe.kt'; p.write_text(kt)
    jar=Path(td)/'probe.jar'
    result=subprocess.run(['kotlinc', str(p), '-include-runtime', '-d', str(jar)],
        capture_output=True,text=True,timeout=90)
    if result.returncode:raise RuntimeError(result.stderr)
    result=subprocess.run(['java','-Xmx128m','-jar',str(jar)],capture_output=True,text=True,timeout=20)
    if result.returncode: raise RuntimeError(result.stdout+'\n'+result.stderr)
    print(result.stdout.strip())
