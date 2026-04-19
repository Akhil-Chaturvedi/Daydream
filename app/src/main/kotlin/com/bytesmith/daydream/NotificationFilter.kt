package com.bytesmith.daydream

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles notification package filtering for icon display.
 * Extracted from NotificationService for better separation of concerns.
 */
object NotificationFilter {
    
    private const val TAG = "NotificationFilter"
    private const val EXCLUDED_PACKAGE = "com.miui.securitycore"

    /**
     * Determines which packages should have their notification icons displayed.
     */
    fun getPackagesForIconDisplay(
        activeNotificationsMap: Map<String, Set<String>>,
        currentlyPlayingMediaPackage: String?,
        isMediaNotification: (android.service.notification.StatusBarNotification) -> Boolean,
        activeNotifications: List<android.service.notification.StatusBarNotification>?
    ): Set<String> {
        val packagesToDisplay = mutableSetOf<String>()
        
        activeNotificationsMap.entries.forEach { (packageName, keys) ->
            if (packageName.equals(EXCLUDED_PACKAGE, ignoreCase = true)) return@forEach
            
            if (packageName == currentlyPlayingMediaPackage) {
                val hasNonMediaNotification = activeNotifications?.any { sbn ->
                    packageName == sbn.packageName && keys.contains(sbn.key) && !isMediaNotification(sbn)
                } ?: false
                if (hasNonMediaNotification) {
                    packagesToDisplay.add(packageName)
                }
            } else if (keys.isNotEmpty()) {
                packagesToDisplay.add(packageName)
            }
        }
        
        Log.d(TAG, "Final packages for icon display: ${packagesToDisplay.size}")
        return packagesToDisplay
    }

    /**
     * Checks if a package should be excluded from icon display.
     */
    fun isExcludedPackage(packageName: String): Boolean {
        return packageName.equals(EXCLUDED_PACKAGE, ignoreCase = true)
    }
}