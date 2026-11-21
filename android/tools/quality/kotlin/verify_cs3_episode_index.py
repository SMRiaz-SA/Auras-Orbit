#!/usr/bin/env python3
"""Exercise the real extracted CS3 episode first-match indexing method with stubs."""
from pathlib import Path
import subprocess,tempfile,re
src=(Path(__file__).resolve().parents[3]/'app/src/main/java/com/auras/orbit/providers/Cs3MediaProviderAdapter.kt').read_text()
line=re.search(r'^    private data class EpisodeMatchKey\([^\n]+\)',src,re.M)
assert line, 'production index key not found'
needle='    private fun indexOriginalEpisodes('
assert src.count(needle)==1
st=src.index(needle); b=src.index('{',st); depth=0
for p in range(b,len(src)):
    if src[p]=='{':depth+=1
    elif src[p]=='}':
        depth-=1
        if depth==0:
            method=src[st:p+1];break
else:raise AssertionError('unclosed index method')
assert 'val originalIndex = indexOriginalEpisodes(originals)' in src
assert 'val original = originalIndex[EpisodeMatchKey(' in src
kt='''import java.util.HashMap
import kotlin.random.Random

data class Episode(val data:String?, val season:Int?, val episode:Int?, val label:Int)
class Index {
''' + line.group(0) + '\n' + method + '''
    fun lookup(items:List<Episode>, data:String?, season:Int, number:Int):Episode? =
        indexOriginalEpisodes(items)[EpisodeMatchKey(data,season,number)]
}
fun main() {
    val index=Index()
    val random=Random(4129)
    val samples=List(2000) { Episode(
        if (it%11==0) null else "data-${random.nextInt(125)}",
        if (it%7==0) null else random.nextInt(1,12),
        if (it%13==0) null else random.nextInt(1,50),it) }
    var checked=0
    repeat(1500) {
        val data=if(it%11==0)null else "data-${random.nextInt(125)}"
        val season=random.nextInt(-1,12)
        val number=random.nextInt(-1,50)
        val slow=samples.firstOrNull { old -> old.data==data && (old.season ?: -1)==season
            && (old.episode ?: -1)==number }
        val fast=index.lookup(samples,data,season,number)
        check(slow === fast) { "first match or null mismatch" }
        checked++
    }
    val dupe=Episode("duplicate", 1,1,9991)
    val duped=listOf(dupe,Episode("duplicate",1,1,9992))
    check(index.lookup(duped,"duplicate",1,1) === dupe)
    println("CS3_EPISODE_INDEX_PASS checks=${checked+1} extracted=production_index_method")
}'''
with tempfile.TemporaryDirectory() as d:
    p=Path(d)/'Index.kt';p.write_text(kt); jar=Path(d)/'index.jar'
    r=subprocess.run(['kotlinc',str(p),'-include-runtime','-d',str(jar)],capture_output=True,text=True,timeout=90)
    if r.returncode:raise RuntimeError(r.stderr)
    r=subprocess.run(['java','-Xmx256m','-jar',str(jar)],capture_output=True,text=True,timeout=90)
    if r.returncode: raise RuntimeError(r.stdout+'\n'+r.stderr)
    print(r.stdout.strip())
