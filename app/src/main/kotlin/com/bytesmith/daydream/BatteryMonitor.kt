package com.bytesmith.daydream

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Handles battery status monitoring and icon selection.
 * Extracted from DreamService for better separation of concerns.
 */
class BatteryMonitor(private val context: Context) {
    
    /**
     * Gets the current battery level as a percentage.
     */
    fun getBatteryLevel(): Int {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) 0 else (level.toFloat() / scale.toFloat() * 100).toInt()
        } ?: 0
    }

    /**
     * Gets the appropriate battery icon resource ID based on level.
     */
    fun getBatteryIconResId(batteryLevel: Int): Int {
        return when {
            batteryLevel >= 100 -> R.drawable.battery_charging_100
            batteryLevel >= 95 -> R.drawable.battery_charging_95
            batteryLevel >= 90 -> R.drawable.battery_charging_90
            batteryLevel >= 85 -> R.drawable.battery_charging_85
            batteryLevel >= 80 -> R.drawable.battery_charging_80
            batteryLevel >= 75 -> R.drawable.battery_charging_75
            batteryLevel >= 70 -> R.drawable.battery_charging_70
            batteryLevel >= 65 -> R.drawable.battery_charging_65
            batteryLevel >= 60 -> R.drawable.battery_charging_60
            batteryLevel >= 55 -> R.drawable.battery_charging_55
            batteryLevel >= 50 -> R.drawable.battery_charging_50
            batteryLevel >= 45 -> R.drawable.battery_charging_45
            batteryLevel >= 40 -> R.drawable.battery_charging_40
            batteryLevel >= 35 -> R.drawable.battery_charging_35
            batteryLevel >= 30 -> R.drawable.battery_charging_30
            batteryLevel >= 25 -> R.drawable.battery_charging_25
            batteryLevel >= 20 -> R.drawable.battery_charging_20
            batteryLevel >= 15 -> R.drawable.battery_charging_15
            batteryLevel >= 10 -> R.drawable.battery_charging_10
            batteryLevel >= 5 -> R.drawable.battery_charging_5
            else -> R.drawable.battery_charging_1
        }
    }

    /**
     * Updates battery info display.
     */
    fun updateBatteryInfo(
        batteryInfoView: android.widget.TextView,
        batteryIconView: android.widget.ImageView
    ) {
        val batteryLevel = getBatteryLevel()
        batteryInfoView.text = "$batteryLevel%"
        batteryIconView.setImageResource(getBatteryIconResId(batteryLevel))
    }

    /**
     * Updates battery icon size based on resources.
     */
    fun updateBatteryIconSize(
        batteryIconView: android.widget.ImageView,
        widthDp: Int,
        heightDp: Int
    ) {
        val widthPx = (widthDp * context.resources.displayMetrics.density).toInt()
        val heightPx = (heightDp * context.resources.displayMetrics.density).toInt()
        batteryIconView.layoutParams.apply {
            this.width = widthPx
            this.height = heightPx
        }
        // Defer requestLayout to avoid "requestLayout() improperly called during layout pass" warning
        batteryIconView.post { batteryIconView.requestLayout() }
    }
}