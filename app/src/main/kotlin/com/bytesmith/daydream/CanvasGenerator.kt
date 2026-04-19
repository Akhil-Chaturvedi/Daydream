// DEPRECATED: This class has been removed.
// Use ArtisticRenderer.create() directly instead.
// OpenCV initialization is now done at service startup (DreamService.onCreate() and SettingsActivity.onCreate()).
package com.bytesmith.daydream

import android.content.Context
import android.graphics.Bitmap

/**
 * @deprecated This facade class is no longer needed.
 * All functionality has been moved to [ArtisticRenderer].
 * OpenCV is now initialized once at service startup.
 */
@Deprecated("")
object CanvasGenerator {
    @Deprecated("Use ArtisticRenderer.create() directly")
    fun create(context: Context, source: Bitmap): Bitmap? {
        return ArtisticRenderer.create(context, source)
    }
}