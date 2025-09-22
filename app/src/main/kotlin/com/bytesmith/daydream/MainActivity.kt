package com.bytesmith.daydream
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
    // companion object is like a static block in Java
    companion object {
        private const val WRITE_SETTINGS_REQUEST_CODE = 1000
    }
    private var currentToast: Toast? = null
    // 'lateinit' means we promise to initialize this variable before we use it.
    // This avoids making it nullable (Button?) when we know it will be assigned in onCreate.
    private lateinit var notificationButton: Button
    private lateinit var writeSettingsButton: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Assign the views using findViewById
        notificationButton = findViewById(R.id.NotificationButton)
        writeSettingsButton = findViewById(R.id.WriteSystemSettings)
        initializeButtons()
    }
    override fun onResume() {
        super.onResume()
        // Update button status when returning to the activity
        updateNotificationButtonStatus()
        updateWriteSettingsButtonStatus()
    }
    override fun onDestroy() {
        // Clear toast reference
        currentToast?.cancel()
        currentToast = null
        super.onDestroy()
    }
    private fun initializeButtons() {
        // Setup Screensaver Button using a Kotlin lambda for the click listener
        setupButton(R.id.ScreensaverButton) {
            launchActivityByClassName(
                "com.android.settings",
                "com.android.settings.Settings\$DreamSettingsActivity",
                "Failed to open Dream settings. Please check if the settings app is available."
            )
        }
        // Setup Notification Button
        setupButton(R.id.NotificationButton) {
            launchActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            showToast("Please enable notification access for DayDream in the list.")
        }
        updateNotificationButtonStatus() // Initial status check
        // Setup Write System Settings Button
        setupButton(R.id.WriteSystemSettings) {
            handleWriteSettingsPermission()
        }
        updateWriteSettingsButtonStatus() // Initial status check
    }
    private fun updateNotificationButtonStatus() {
        if (isNotificationServiceEnabled()) {
            notificationButton.text = "Notification Access: Granted"
            notificationButton.isEnabled = false // Disable if granted
        } else {
            notificationButton.text = "Notification Access: Required"
            notificationButton.isEnabled = true
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
            writeSettingsButton.text = "Write Settings: Granted"
            writeSettingsButton.isEnabled = false // Disable if granted
        } else {
            writeSettingsButton.text = "Write Settings: Required"
            writeSettingsButton.isEnabled = true
        }
    }
    private fun canWriteSettings(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else {
            true // Not needed below Marshmallow
        }
    }
    // A higher-order function that takes a click listener lambda
    private fun setupButton(buttonId: Int, clickListener: (View) -> Unit) {
        findViewById<Button>(buttonId)?.setOnClickListener(clickListener)
    }
    private fun launchActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("Failed to launch activity: ${e.message}") // String templates
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivityForResult(intent, WRITE_SETTINGS_REQUEST_CODE)
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
    // Note: onActivityResult is deprecated, but we keep it for this example
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WRITE_SETTINGS_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    showToast("Write settings permission granted!")
                } else {
                    showToast("Write settings permission denied.")
                }
            }
        }
    }
    private fun showToast(message: String) {
        // Cancel any existing toast to prevent stacking
        currentToast?.cancel()
        // Create and show new toast
        currentToast = Toast.makeText(this, message, Toast.LENGTH_LONG).apply {
            show()
        }
    }
}