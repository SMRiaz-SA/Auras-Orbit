package com.lagradost.cloudstream3.utils

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.TvContractCompat
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

internal fun artworkCacheNeedsReferenceScan(totalBytes: Long, maximumBytes: Long): Boolean =
    totalBytes > maximumBytes

/**
 * Creates persistent, launcher-readable 16:9 thumbnails from provider poster URLs already
 * carried by SearchResponse. Landscape posters get a center crop; portrait posters are
 * composed with the full poster visible over a subdued fill.
 */
object TvPreviewArtwork {
    private const val DIRECTORY_NAME = "tv_preview_art"
    private const val MAX_CACHE_BYTES = 48L * 1024L * 1024L
    private const val OUTPUT_WIDTH = 960
    private const val OUTPUT_HEIGHT = 540
    private val generationSlots = Semaphore(2)
    private val generationLocks = ConcurrentHashMap<String, Mutex>()
    private val cacheMutationLock = Mutex()

    suspend fun thumbnailUri(
        context: android.content.Context,
        imageUrl: String?,
        headers: Map<String, String>? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        val source = imageUrl?.takeIf(::isHttpUrl) ?: return@withContext null
        val appContext = context.applicationContext
        val key = artworkKey(source, headers)
        val directory = File(appContext.filesDir, DIRECTORY_NAME)
        val output = File(directory, "$key.jpg")

        val keyLock = generationLocks.computeIfAbsent(key) { Mutex() }
        keyLock.withLock {
            if (cachedArtwork(output)) return@withLock uriFor(appContext, key)

            generationSlots.withPermit {
                withTimeoutOrNull(8_000L) {
                    try {
                        // loadProviderImage returns an app-owned copy; it is safe to recycle here.
                        val bitmap = loadProviderImage(appContext, source, headers)
                            ?: return@withTimeoutOrNull null
                        val thumbnail = try {
                            createWideThumbnail(bitmap)
                        } finally {
                            bitmap.recycle()
                        }

                        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                            thumbnail.recycle()
                            return@withTimeoutOrNull null
                        }

                        val temporary = File(directory, "$key.tmp")
                        val saved = try {
                            cacheMutationLock.withLock {
                                FileOutputStream(temporary).use { stream ->
                                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 84, stream)
                                } && temporary.length() > 0L &&
                                    (!output.exists() || output.delete()) && temporary.renameTo(output)
                            }
                        } finally {
                            thumbnail.recycle()
                            if (temporary.exists()) temporary.delete()
                        }

                        if (!saved) return@withTimeoutOrNull null
                        output.setLastModified(System.currentTimeMillis())
                        val retained = cacheMutationLock.withLock {
                            trimCache(appContext, directory, output)
                        }
                        if (retained && output.isFile) uriFor(appContext, key) else null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    private suspend fun loadProviderImage(
        context: android.content.Context,
        url: String,
        headers: Map<String, String>?,
    ): Bitmap? {
        val requestHeaders = NetworkHeaders.Builder().apply {
            this["User-Agent"] = USER_AGENT
            headers?.forEach { (name, value) -> this[name] = value }
        }.build()
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(OUTPUT_WIDTH, OUTPUT_WIDTH)
            .allowHardware(false)
            .httpHeaders(requestHeaders)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val drawable = result.image.asDrawable(context.resources)
        // Coil may return a Drawable backed by its cached Bitmap. toBitmap() can expose that
        // same bitmap, so make an independent mutable copy before returning ownership to the
        // caller. The intermediate is deliberately not recycled because its ownership belongs
        // to Coil or is otherwise unknown.
        val imageBitmap = drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        return imageBitmap.copy(Bitmap.Config.ARGB_8888, /* mutable = */ true)
    }

    private fun createWideThumbnail(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        val imageRatio = source.width.toFloat() / source.height.coerceAtLeast(1)

        if (imageRatio >= 1.35f) {
            canvas.drawBitmap(
                source,
                sourceCrop(source.width, source.height),
                Rect(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT),
                paint,
            )
            return output
        }

        // A small upscaled copy of this same provider poster makes a soft background
        // while preserving the entire original poster in the foreground.
        val softWidth = 40
        val softHeight = (softWidth * OUTPUT_HEIGHT / OUTPUT_WIDTH).coerceAtLeast(1)
        val softBackground = Bitmap.createBitmap(softWidth, softHeight, Bitmap.Config.ARGB_8888)
        Canvas(softBackground).drawBitmap(
            source,
            sourceCrop(source.width, source.height, softWidth, softHeight),
            Rect(0, 0, softWidth, softHeight),
            paint,
        )
        canvas.drawBitmap(softBackground, null, Rect(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT), paint)
        softBackground.recycle()
        canvas.drawColor(0x99000000.toInt())

        val maxPosterWidth = OUTPUT_WIDTH * 0.43f
        val maxPosterHeight = OUTPUT_HEIGHT * 0.90f
        val posterScale = min(maxPosterWidth / source.width, maxPosterHeight / source.height)
        val posterWidth = source.width * posterScale
        val posterHeight = source.height * posterScale
        val posterRect = RectF(
            (OUTPUT_WIDTH - posterWidth) / 2f,
            (OUTPUT_HEIGHT - posterHeight) / 2f,
            (OUTPUT_WIDTH + posterWidth) / 2f,
            (OUTPUT_HEIGHT + posterHeight) / 2f,
        )
        val posterClip = Path().apply {
            addRoundRect(posterRect, 8f, 8f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(posterClip)
        canvas.drawBitmap(source, null, posterRect, paint)
        canvas.restore()
        return output
    }

    private fun sourceCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int = OUTPUT_WIDTH,
        targetHeight: Int = OUTPUT_HEIGHT,
    ): Rect {
        val sourceRatio = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)
        val targetRatio = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
        return if (sourceRatio > targetRatio) {
            val cropWidth = sourceHeight * targetRatio
            val left = (sourceWidth - cropWidth) / 2f
            Rect(left.toInt(), 0, (left + cropWidth).toInt(), sourceHeight)
        } else {
            val cropHeight = sourceWidth / targetRatio
            val top = (sourceHeight - cropHeight) / 2f
            Rect(0, top.toInt(), sourceWidth, (top + cropHeight).toInt())
        }
    }

    private fun cachedArtwork(file: File): Boolean {
        val isFresh = file.isFile && file.length() > 0L
        if (isFresh) file.setLastModified(System.currentTimeMillis())
        return isFresh
    }

    private fun trimCache(context: android.content.Context, directory: File, newest: File): Boolean {
        val files = directory.listFiles()
            ?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) }
            ?.sortedByDescending(File::lastModified)
        if (files == null) {
            newest.delete()
            return false
        }
        var remaining = files.sumOf(File::length)
        if (!artworkCacheNeedsReferenceScan(remaining, MAX_CACHE_BYTES)) {
            // A cache hit needs no eviction reference scan. Some Android TV launchers
            // restrict provider queries, but that must not prevent the normal under-limit
            // artwork path from working.
            return newest.isFile
        }
        val referenced = referencedArtworkFiles(context)
        if (referenced == null) {
            // Unknown reference state is not an empty reference set. Keep every old file and
            // decline only this not-yet-published composite rather than breaking active URIs.
            newest.delete()
            return false
        }
        val handoffDeadline = System.currentTimeMillis() - CACHE_HANDOFF_GRACE_MS
        for (file in files.asReversed()) {
            if (remaining <= MAX_CACHE_BYTES) break
            if (file == newest || file.name in referenced || file.lastModified() >= handoffDeadline) continue
            val fileSize = file.length()
            if (file.delete()) remaining -= fileSize
        }
        // Active launcher references and just-created handoff files are protected. If they fill
        // the bounded cache, decline this optional composite instead of evicting a live URI.
        if (remaining > MAX_CACHE_BYTES && newest.name !in referenced) {
            newest.delete()
            return false
        }
        return newest.isFile
    }

    private fun referencedArtworkFiles(context: android.content.Context): Set<String>? {
        val referenced = mutableSetOf<String>()
        val columns = arrayOf("poster_art_uri", "thumbnail_uri")
        val programTables = buildList {
            add(TvContractCompat.PreviewPrograms.CONTENT_URI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(TvContractCompat.WatchNextPrograms.CONTENT_URI)
            }
        }
        for (table in programTables) {
            try {
                val cursor = context.contentResolver.query(table, columns, null, null, null)
                    ?: return null
                cursor.use {
                    val indexes = columns.map { column -> cursor.getColumnIndex(column) }
                    if (indexes.any { it < 0 }) return null
                    while (cursor.moveToNext()) {
                        for (index in indexes) {
                            if (index < 0 || cursor.isNull(index)) continue
                            val uri = Uri.parse(cursor.getString(index))
                            if (uri.authority == "${context.packageName}.tvpreviewart") {
                                uri.lastPathSegment?.takeIf {
                                    it.matches(Regex("[a-f0-9]{64}\\.jpg"))
                                }?.let(referenced::add)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                return null
            }
        }
        return referenced
    }

    private fun artworkKey(url: String, headers: Map<String, String>?): String {
        val stableInput = buildString {
            append(url)
            headers.orEmpty().entries
                .sortedBy { it.key.lowercase(Locale.ROOT) }
                .forEach { (name, value) ->
                    append('\n')
                    append(name.lowercase(Locale.ROOT))
                    append(':')
                    append(value)
                }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(stableInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun uriFor(context: android.content.Context, key: String): Uri =
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority("${context.packageName}.tvpreviewart")
            .appendPath("thumbs")
            .appendPath("$key.jpg")
            .build()

    private fun isHttpUrl(value: String): Boolean =
        runCatching {
            val uri = Uri.parse(value)
            uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true)
        }.getOrDefault(false)

    private const val CACHE_HANDOFF_GRACE_MS = 2 * 60 * 1000L
}

/** Public, read-only access to the small set of generated TV thumbnails. */
class TvPreviewArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun getType(uri: Uri): String? = resolveArtworkFile(uri)?.let { "image/jpeg" }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolveArtworkFile(uri) ?: return null
        val columns = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("TV artwork is read-only")
        val file = resolveArtworkFile(uri)
            ?: throw FileNotFoundException("TV artwork does not exist")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("TV artwork provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("TV artwork provider is read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("TV artwork provider is read-only")

    private fun resolveArtworkFile(uri: Uri): File? {
        val appContext = context?.applicationContext ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
            uri.authority != "${appContext.packageName}.tvpreviewart" ||
            uri.pathSegments.size != 2 || uri.pathSegments[0] != "thumbs"
        ) return null

        val name = uri.pathSegments[1]
        if (!name.matches(Regex("[a-f0-9]{64}\\.jpg"))) return null
        val directory = File(appContext.filesDir, "tv_preview_art").canonicalFile
        val file = File(directory, name).canonicalFile
        return file.takeIf { it.parentFile == directory && it.isFile }
    }
}
