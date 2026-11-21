package probe

import com.auras.orbit.metadata.TmdbTitleMetadataRepository
import com.auras.orbit.metadata.TitleCatalogRecord
import com.uwetrottmann.tmdb2.Tmdb

fun main() {
    val client = Tmdb()
    client.expectedMovieId = 512
    client.expectedTvId = 512
    val reader = TmdbTitleMetadataRepository(client)
    check(reader.load(512, "MOVIE")?.title == "Movie 512")
    check(reader.load(512, "SERIES")?.title == "Show 512")
    check(reader.load(513, "MOVIE") == null)
    check(reader.load(513, "SERIES") == null)
    check(reader.load(512, "MOVIE")?.posterUrl == "https://image.tmdb.org/t/p/w500/public.jpg")
    check(TitleCatalogRecord(100, "MOVIE", "Name", "", "", "https://evil.com/img", "").posterUrl == "")
    var rejected = false
    try { reader.load(0, "MOVIE") } catch (expected: IllegalArgumentException) { rejected = true }
    check(rejected)
    println("TMDB_TYPED_READER_KOTLIN_PASS checks=7 (API stubs; not Android integration)")
}
