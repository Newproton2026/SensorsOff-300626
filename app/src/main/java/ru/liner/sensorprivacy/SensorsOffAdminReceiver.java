package ru.liner.sensorprivacy;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;

/**
 * Device Admin receiver required for DevicePolicyManager.
 * Activated automatically via Shizuku (no user interaction needed).
 * Allows setCameraDisabled() and setMicrophoneDisabled() —
 * which block ONLY camera/mic without touching motion sensors.
 */
public class SensorsOffAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) { }
    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) { }
}
