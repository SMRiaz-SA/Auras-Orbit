#!/usr/bin/env python3
"""Compile and run the exact extracted CS3 context validation method with stubs.
This is not a full Android/CS3 adapter compile or runtime acceptance.
"""
from pathlib import Path
import re, subprocess, tempfile
src = Path(__file__).resolve().parents[3] / 'app/src/main/java/com/auras/orbit/providers/Cs3MediaProviderAdapter.kt'
text = src.read_text()
needle = '    override fun isLoadedDetailsContextCurrent(details: MediaItem): Boolean {'
assert text.count(needle) == 1
start = text.index(needle)
depth = 0
end = None
for i in range(text.index('{', start), len(text)):
    if text[i] == '{': depth += 1
    elif text[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
assert end is not None
method = text[start:end]
assert 'actionMetadata[episode.providerData]?.ownerStableId == details.stableId' in method
assert text.count('syncData, currentStableId)') == 2
source = '''
import java.util.LinkedHashMap

enum class MediaKind { MOVIE, SERIES, LIVE }
data class Episode(val providerData: String)
data class MediaItem(val kind: MediaKind, val stableId: String,
    val detailUrl: String, val providerData: String, val episodes: List<Episode>)
data class ActionMetadata(val ownerStableId: String)
class Descriptor(val details: Boolean = true)
interface Provider { fun isLoadedDetailsContextCurrent(details: MediaItem): Boolean }
class Context(val descriptor: Descriptor = Descriptor()): Provider {
    var current = true
    val actionMetadata = LinkedHashMap<String, ActionMetadata>()
    fun isRegistrationCurrent() = current
''' + method + '''
}
fun main() {
    val c = Context()
    val movie = MediaItem(MediaKind.MOVIE, "movie-one", "https://details", "d1", emptyList())
    val other = MediaItem(MediaKind.MOVIE, "movie-two", "https://details", "d1", emptyList())
    check(!c.isLoadedDetailsContextCurrent(movie))
    c.actionMetadata["d1"] = ActionMetadata("movie-one")
    check(c.isLoadedDetailsContextCurrent(movie))
    check(!c.isLoadedDetailsContextCurrent(other))
    c.actionMetadata["d1"] = ActionMetadata("movie-two")
    check(!c.isLoadedDetailsContextCurrent(movie))
    c.current = false
    check(!c.isLoadedDetailsContextCurrent(other))
    c.current = true
    val series = MediaItem(MediaKind.SERIES, "series", "https://s", "",
         listOf(Episode("ep1"), Episode("ep2")))
    c.actionMetadata["ep1"] = ActionMetadata("series")
    check(!c.isLoadedDetailsContextCurrent(series))
    c.actionMetadata["ep2"] = ActionMetadata("series")
    check(c.isLoadedDetailsContextCurrent(series))
    c.actionMetadata["ep1"] = ActionMetadata("else")
    check(!c.isLoadedDetailsContextCurrent(series))
    val live = MediaItem(MediaKind.LIVE, "live", "https://live", "data", emptyList())
    check(!c.isLoadedDetailsContextCurrent(live))
    println("CS3_CACHED_CONTEXT_METHOD_PASS checks=9 method_extracted=actual_adapter")
}
'''
with tempfile.TemporaryDirectory() as temp:
    path = Path(temp) / 'Context.kt'
    out = Path(temp) / 'probe.jar'
    path.write_text(source)
    result = subprocess.run(['kotlinc', str(path), '-include-runtime', '-d', str(out)],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=90)
    if result.returncode:
        raise RuntimeError(result.stderr)
    output = subprocess.check_output(['java', '-jar', str(out)], text=True, timeout=20)
    print(output.strip())
