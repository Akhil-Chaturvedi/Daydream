package com.bytesmith.daydream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.Spanned
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.collection.LruCache
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

/**
 * Handles media session display and song information.
 * Extracted from DreamService for better separation of concerns.
 */
class MediaSessionHandler(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val cacheDir: File
) {
    private val memoryCache: LruCache<String, Bitmap>
    private var canvasArtCacheDir: File? = null
    
    // State for preventing redundant updates
    private var lastDisplayedTitle: String? = null
    private var lastDisplayedArtist: String? = null
    private var lastDisplayedArtKey: String? = null

    init {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = maxMemory / DaydreamSettings.Layout.DEFAULT_MEMORY_CACHE_DIVISOR
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    /**
     * Parses a song string into title and artist.
     */
    fun parseSongString(songString: String?): Pair<String?, String?> {
        if (songString.isNullOrBlank()) return null to null
        val cleaned = songString.replace("%20", " ")
        return if (cleaned.contains('\n')) {
            val parts = cleaned.split('\n', limit = 2)
            parts[0] to parts[1]
        } else {
            cleaned to null
        }
    }

    /**
     * Formats song text as HTML for display.
     */
    fun formatSongText(title: String?, artist: String?): Spanned {
        val safeTitle = escapeHtml(capitalizeWords(title))
        val safeArtist = escapeHtml(capitalizeWords(artist))
        val htmlString = if (!artist.isNullOrBlank()) {
            "<b><font size='+4'>$safeTitle</font></b><br>$safeArtist"
        } else {
            "<b><font size='+4'>$safeTitle</font></b>"
        }
        return HtmlCompat.fromHtml(htmlString, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    /**
     * Checks if the media info has changed and needs display update.
     */
    fun hasMediaInfoChanged(title: String?, artist: String?, cacheKey: String?): Boolean {
        val safeCacheKey = cacheKey ?: "null"
        return title != lastDisplayedTitle || artist != lastDisplayedArtist || safeCacheKey != lastDisplayedArtKey
    }

    /**
     * Updates the last displayed media info.
     */
    fun updateLastDisplayedInfo(title: String?, artist: String?, cacheKey: String?) {
        lastDisplayedTitle = title
        lastDisplayedArtist = artist
        lastDisplayedArtKey = cacheKey ?: "null"
    }

    /**
     * Clears the last displayed info (used when media stops).
     */
    fun clearLastDisplayedInfo() {
        lastDisplayedTitle = null
        lastDisplayedArtist = null
        lastDisplayedArtKey = null
    }

    /**
     * Processes and displays canvas art with caching.
     */
    fun processAndDisplayCanvasArt(
        sourceBitmap: Bitmap,
        cacheKey: String,
        imageView: android.widget.ImageView,
        onComplete: (() -> Unit)? = null
    ) {
        val cachedBitmap = memoryCache.get(cacheKey)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            onComplete?.invoke()
            return
        }
        
        scope.launch {
            val finalBitmap = generateAndCacheArt(sourceBitmap, cacheKey)
            finalBitmap?.let {
                memoryCache.put(cacheKey, it)
                imageView.setImageBitmap(it)
            }
            onComplete?.invoke()
        }
    }

    /**
     * Generates and caches canvas art.
     */
    private suspend fun generateAndCacheArt(sourceBitmap: Bitmap, cacheKey: String): Bitmap? = 
        withContext(Dispatchers.IO) {
            val cachedInMemory = memoryCache.get(cacheKey)
            if (cachedInMemory != null) return@withContext cachedInMemory

            val artCacheDir = getCanvasArtCacheDir()
            val cachedFile = File(artCacheDir, "$cacheKey.png")
            
            if (cachedFile.exists()) {
                return@withContext BitmapFactory.decodeFile(cachedFile.absolutePath)?.also {
                    memoryCache.put(cacheKey, it)
                }
            }

            val generatedArt = generateCanvasArt(sourceBitmap)
            generatedArt?.let {
                try {
                    FileOutputStream(cachedFile).use { out ->
                        it.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to save canvas art to cache", e)
                }
                memoryCache.put(cacheKey, it)
            }
            return@withContext generatedArt
        }

    private fun generateCanvasArt(source: Bitmap): Bitmap? {
        return try {
            ArtisticRenderer.create(context, source)
        } catch (e: Exception) {
            Log.e(TAG, "ArtisticRenderer failed.", e)
            null
        }
    }

    private fun getCanvasArtCacheDir(): File {
        if (canvasArtCacheDir == null) {
            canvasArtCacheDir = File(cacheDir, "canvas_art_cache").also { it.mkdirs() }
        }
        return canvasArtCacheDir!!
    }

    private fun escapeHtml(text: String?): String {
        return if (text == null) "" else TextUtils.htmlEncode(text)
    }

    private fun capitalizeWords(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str.split(Regex("\\s+")).joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }

    companion object {
        private const val TAG = "MediaSessionHandler"
    }
}