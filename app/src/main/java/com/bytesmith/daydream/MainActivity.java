package com.bytesmith.daydream;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    
    private static final int WRITE_SETTINGS_REQUEST_CODE = 1000;
    private Toast currentToast = null;
    private Button notificationButton;
    private Button accessibilityButton;
    private Button writeSettingsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        notificationButton = findViewById(R.id.NotificationButton);
        accessibilityButton = findViewById(R.id.AccessibilityButton);
        writeSettingsButton = findViewById(R.id.WriteSystemSettings);
        initializeButtons();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Update button status when returning to the activity
        updateNotificationButtonStatus();
        updateAccessibilityButtonStatus();
        updateWriteSettingsButtonStatus();
    }

    private void initializeButtons() {
        // Setup Screensaver Button
        setupButton(R.id.ScreensaverButton, v -> launchActivityByClassName(
                "com.android.settings",
                "com.android.settings.Settings$DreamSettingsActivity",
                "Failed to open Dream settings. Please check if the settings app is available."
        ));

        // Setup Accessibility Button
        setupButton(R.id.AccessibilityButton, v -> {
            launchActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            showToast("Please enable the accessibility service for DayDream.");
        });
        updateAccessibilityButtonStatus(); // Initial status check

        // Setup Notification Button
        setupButton(R.id.NotificationButton, v -> {
            launchActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            showToast("Please enable notification access for DayDream in the list.");
        });
        updateNotificationButtonStatus(); // Initial status check

        // Setup Write System Settings Button
        setupButton(R.id.WriteSystemSettings, v -> handleWriteSettingsPermission());
        updateWriteSettingsButtonStatus(); // Initial status check
    }
    
    private void updateNotificationButtonStatus() {
        if (notificationButton == null) return;
        
        if (isNotificationServiceEnabled()) {
            notificationButton.setText("Notification Access: Granted");
            notificationButton.setEnabled(false); // Disable if granted
        } else {
            notificationButton.setText("Notification Access: Required");
            notificationButton.setEnabled(true);
        }
    }
    
    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // --- Accessibility Service Check --- 
    private void updateAccessibilityButtonStatus() {
        if (accessibilityButton == null) return;
        
        if (isAccessibilityServiceEnabled()) {
            accessibilityButton.setText("Accessibility: Enabled");
            accessibilityButton.setEnabled(false); // Disable if enabled
        } else {
            accessibilityButton.setText("Accessibility: Required");
            accessibilityButton.setEnabled(true);
        }
    }
    
    private boolean isAccessibilityServiceEnabled() {
        ComponentName expectedComponentName = new ComponentName(this, BrightnessService.class);
        String expectedFlattened = expectedComponentName.flattenToString();

        String enabledServicesSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) {
            return false;
        }

        // Simplified check: See if the flattened component name exists in the enabled services string
        if (enabledServicesSetting.contains(expectedFlattened)) {
            // Check specifically to avoid partial matches (e.g., com.example vs com.example.extra)
            TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
            colonSplitter.setString(enabledServicesSetting);
            while (colonSplitter.hasNext()) {
                String componentNameString = colonSplitter.next();
                if (expectedFlattened.equals(componentNameString)) {
                    return true;
                }
            }
        }

        /* Original loop - kept for reference, but contains check is usually sufficient and cleaner
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);
            if (enabledService != null && enabledService.equals(expectedComponentName)) {
                return true;
            }
        }
        */
        return false;
    }
    
    // --- Write Settings Check --- 
    private void updateWriteSettingsButtonStatus() {
        if (writeSettingsButton == null) return;
        
        if (canWriteSettings()) {
            writeSettingsButton.setText("Write Settings: Granted");
            writeSettingsButton.setEnabled(false); // Disable if granted
        } else {
            writeSettingsButton.setText("Write Settings: Required");
            writeSettingsButton.setEnabled(true);
        }
    }
    
    private boolean canWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(this);
        } else {
            return true; // Not needed below Marshmallow
        }
    }

    private void setupButton(int buttonId, View.OnClickListener clickListener) {
        Button button = findViewById(buttonId);
        if (button != null) {
            button.setOnClickListener(clickListener);
        }
    }

    private void launchActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("Failed to launch activity: " + e.getMessage());
        }
    }

    /**
     * Launches an activity using the specified package and class names.
     */
    private void launchActivityByClassName(String packageName, String className, String errorMessage) {
        Intent intent = new Intent();
        intent.setClassName(packageName, className);
        try {
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            showToast(errorMessage);
        }
    }

    private void handleWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                // Build the intent to request write settings permission.
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivityForResult(intent, WRITE_SETTINGS_REQUEST_CODE);
                } catch (Exception e) {
                    showToast("Could not request permission: " + e.getMessage());
                }
            } else {
                showToast("Write settings permission is already granted.");
            }
        } else {
            showToast("Write settings permission is not required on this Android version.");
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WRITE_SETTINGS_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    showToast("Write settings permission granted!");
                } else {
                    showToast("Write settings permission denied.");
                }
            }
        }
    }

    private void showToast(String message) {
        // Cancel any existing toast to prevent stacking
        if (currentToast != null) {
            currentToast.cancel();
        }
        
        // Create and show new toast
        currentToast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        currentToast.show();
    }
    
    @Override
    protected void onDestroy() {
        // Clear toast reference
        if (currentToast != null) {
            currentToast.cancel();
            currentToast = null;
        }
        super.onDestroy();
    }
}