package audit

import com.auras.orbit.cs3.BoundedLevenshtein
import kotlin.random.Random

fun main() {
    val rng = Random(0x4B6F42)
    val alphabet = "abcXYZ 12/\u00E9\u2603"
    var checks = 0L
    repeat(150_000) {
        val n1 = rng.nextInt(0, 21)
        val n2 = rng.nextInt(0, 21)
        val s1 = buildString { repeat(n1) { append(alphabet[rng.nextInt(alphabet.length)]) } }
        val s2 = buildString { repeat(n2) { append(alphabet[rng.nextInt(alphabet.length)]) } }
        val matrix = longArrayOf(8000)
        val score = longArrayOf(8000)
        val result = BoundedLevenshtein.partialRatio(s1, s2, 20, 2000, matrix, score)
        if (result == null || result !in 0..100) error("invalid score for fuzz sample s1='$s1' s2='$s2': $result")
        if (matrix[0] < 0 || score[0] < 0) error("budget underflow")
        checks += 3
        val overlong = BoundedLevenshtein.partialRatio(s1, s2, 0, 2000, longArrayOf(1), longArrayOf(1))
        if ((n1 != 0 || n2 != 0) && overlong != null) error("overlong input accepted")
        checks++
    }
    repeat(10_000) {
        val n = rng.nextInt(0, 21)
        val s = buildString { repeat(n) { append(alphabet[rng.nextInt(alphabet.length)]) } }
        val result = BoundedLevenshtein.partialRatio(s, s, 20, 2000, longArrayOf(8000), longArrayOf(8000))
        if (result != 100) error("identical string not scored 100: $result for '$s'")
        checks++
    }
    println("KOTLIN_LEVENSHTEIN_REGRESSION checks=$checks failures=0")
}
