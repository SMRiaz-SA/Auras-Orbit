package android.net
import java.io.File
class Uri private constructor(private val value: String) {
    override fun toString(): String = value
    companion object { @JvmStatic fun fromFile(file: File): Uri = Uri(file.toURI().toString()) }
}
