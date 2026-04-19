// In MainActivity.kt
package com.bytesmith.daydream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.bytesmith.daydream.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "DaydreamSettings"
        const val KEY_ICON_STYLE = "notificationIconStyle"
    }

    private var currentToast: Toast? = null
    private lateinit var binding: ActivityMainBinding
    private lateinit var writeSettingsLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        writeSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    showToast("Write settings permission granted!")
                } else {
                    showToast("Write settings permission denied.")
                }
                updateWriteSettingsButtonStatus()
            }
        }

        initializeButtons()
    }

    override fun onResume() {
        super.onResume()
        updateNotificationButtonStatus()
        updateWriteSettingsButtonStatus()
        updateIconStyleButton()
    }

    override fun onDestroy() {
        currentToast?.cancel()
        currentToast = null
        super.onDestroy()
    }

    private fun initializeButtons() {
        if (resources.getBoolean(R.bool.is_tv)) {
            binding.OverlayPermissionButton.visibility = View.GONE
        } else {
            binding.OverlayPermissionButton.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                }
            }
        }

        binding.ScreensaverButton.setOnClickListener {
            launchActivityByClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DreamSettingsActivity",
                "Failed to open Dream settings. Please check if the settings app is available."
            )
        }

        binding.NotificationButton.setOnClickListener {
            launchActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            showToast("Please enable notification access for DayDream in the list.")
        }
        updateNotificationButtonStatus()

        binding.WriteSystemSettings.setOnClickListener {
            handleWriteSettingsPermission()
        }
        updateWriteSettingsButtonStatus()

        binding.notificationIconStyleButton.setOnClickListener {
            cycleNotificationIconStyle()
        }
        updateIconStyleButton()

        // Customize Daydream button - launches SettingsActivity
        binding.customizeButton?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun cycleNotificationIconStyle() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentStyle = prefs.getInt(KEY_ICON_STYLE, IconStyle.SYSTEM) // Default to System

        val nextStyle = when (currentStyle) {
            IconStyle.SYSTEM -> IconStyle.MONOCHROME
            IconStyle.MONOCHROME -> IconStyle.OFF
            else -> IconStyle.SYSTEM // Covers OFF and any other case
        }

        prefs.edit().putInt(KEY_ICON_STYLE, nextStyle).apply()
        updateIconStyleButton()
    }

    private fun updateIconStyleButton() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentStyle = prefs.getInt(KEY_ICON_STYLE, IconStyle.SYSTEM)

        binding.notificationIconStyleButton.text = when (currentStyle) {
            IconStyle.SYSTEM -> "Icon Style: System"
            IconStyle.MONOCHROME -> "Icon Style: Monochrome"
            else -> "Icon Style: Off"
        }
    }

    private fun updateNotificationButtonStatus() {
        if (isNotificationServiceEnabled()) {
            binding.NotificationButton.text = "Notification Access: Granted"
            binding.NotificationButton.isEnabled = false
        } else {
            binding.NotificationButton.text = "Notification Access: Required"
            binding.NotificationButton.isEnabled = true
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.packageName)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun updateWriteSettingsButtonStatus() {
        if (canWriteSettings()) {
            binding.WriteSystemSettings.text = "Write Settings: Granted"
            binding.WriteSystemSettings.isEnabled = false
        } else {
            binding.WriteSystemSettings.text = "Write Settings: Required"
            binding.WriteSystemSettings.isEnabled = true
        }
    }

    private fun canWriteSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true
        }
    }

    private fun launchActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Failed to launch activity: ${e.message}")
        }
    }

    private fun launchActivityByClassName(packageName: String, className: String, errorMessage: String) {
        val intent = Intent().apply {
            setClassName(packageName, className)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast(errorMessage)
        }
    }

    private fun handleWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    writeSettingsLauncher.launch(intent)
                } catch (e: Exception) {
                    showToast("Could not request permission: ${e.message}")
                }
            } else {
                showToast("Write settings permission is already granted.")
            }
        } else {
            showToast("Write settings permission is not required on this Android version.")
        }
    }

    private fun showToast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, message, Toast.LENGTH_LONG).apply {
            show()
        }
    }
}