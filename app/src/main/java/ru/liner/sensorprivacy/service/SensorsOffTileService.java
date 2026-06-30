package ru.liner.sensorprivacy.service;

import static ru.liner.sensorprivacy.Application.preferences;

import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;

import java.io.InputStream;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;
import ru.liner.sensorprivacy.R;
import ru.liner.sensorprivacy.SensorsOffAdminReceiver;
import ru.liner.sensorprivacy.preference.PreferenceListener;
import ru.liner.sensorprivacy.shizuku.ShizukuState;

/**
 * Blocks ONLY camera + microphone using DevicePolicyManager.
 * Motion sensors (accelerometer, gyroscope etc.) are NOT affected.
 *
 * How it works:
 *   1. DevicePolicyManager.setCameraDisabled()    — hard-blocks camera for all apps
 *   2. DevicePolicyManager.setMicrophoneDisabled() — hard-blocks mic (API 31+)
 *
 * These are persistent (survive app restarts, camera app kills, reboots).
 * Device Admin is activated automatically via Shizuku the first time the tile is tapped.
 * No root required.
 *
 * Why not SensorPrivacyManager?
 *   - setSensorPrivacy(true)        = blocks ALL sensors including motion ❌
 *   - setToggleSensorPrivacy(cam,1) = only dims/darkens camera feed on MIUI 13 ❌
 *   DevicePolicyManager is the only clean per-sensor complete block. ✓
 */
public class SensorsOffTileService extends TileService implements
        Shizuku.OnRequestPermissionResultListener,
        Shizuku.OnBinderReceivedListener,
        Shizuku.OnBinderDeadListener {

    private static final int REQUEST_CODE_SHIZUKU = 9988;

    private DevicePolicyManager dpm;
    private ComponentName adminComp;
    private KeyguardManager keyguardManager;
    private boolean privacyEnabled;
    private Icon activeIcon, inactiveIcon, warningIcon, stopIcon;

    @ShizukuState
    private int shizukuState;

    @Override
    public void onCreate() {
        super.onCreate();
        Context ctx = getApplicationContext();
        dpm       = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComp = new ComponentName(ctx, SensorsOffAdminReceiver.class);
        keyguardManager = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);

        preferences.register(new PreferenceListener<Boolean>() {
            @NonNull @Override public String key() { return "privacy_state"; }
            @Override public void onChanged(@NonNull Boolean newValue) { setPrivacyEnabled(newValue); }
            @NonNull @Override public Boolean defaultValue() { return false; }
        });

        activeIcon   = Icon.createWithResource(ctx, R.drawable.tile_icon_sensorsoff_active);
        inactiveIcon = Icon.createWithResource(ctx, R.drawable.tile_icon_sensorsoff_inactive);
        warningIcon  = Icon.createWithResource(ctx, R.drawable.tile_icon_warning);
        stopIcon     = Icon.createWithResource(ctx, R.drawable.tile_icon_stop);
    }

    @Override
    public void onStartListening() {
        shizukuState = checkShizukuState();
        // Read state from preferences — no system calls (safe on all MIUI versions)
        privacyEnabled = preferences.get("privacy_enabled", false);
        updateUI();
    }

    @Override public void onStopListening() { super.onStopListening(); }

    @Override
    public void onClick() {
        setPrivacyEnabled(!privacyEnabled);
    }

    /**
     * Activates Device Admin via Shizuku (same as running:
     *   adb shell dpm set-active-admin ru.liner.sensorprivacy/.SensorsOffAdminReceiver
     * Returns true if admin is active after the call.
     */
    private boolean ensureAdminActive() {
        if (dpm.isAdminActive(adminComp)) return true;
        try {
            Process p = Shizuku.newProcess(
                new String[]{
                    "dpm", "set-active-admin",
                    "ru.liner.sensorprivacy/.SensorsOffAdminReceiver"
                },
                null, null
            );
            // Read output to avoid blocking
            InputStream is = p.getInputStream();
            byte[] buf = new byte[256];
            int n = is.read(buf);
            p.waitFor();
            is.close();
        } catch (Exception e) {
            // Shizuku call failed
            return false;
        }
        return dpm.isAdminActive(adminComp);
    }

    /**
     * Blocks or unblocks camera + microphone ONLY via DevicePolicyManager.
     * Hard block — apps cannot access camera at all (not just a dim preview).
     * Completely persistent — survives camera app restarts and reboots.
     */
    private void setPrivacyEnabled(boolean enabled) {
        shizukuState = checkShizukuState();
        if (shizukuState != ShizukuState.NORMAL) {
            updateUI();
            return;
        }
        if (keyguardManager.isKeyguardLocked()) return;

        if (!ensureAdminActive()) {
            // Device Admin activation failed
            shizukuState = ShizukuState.UNKNOWN;
            updateUI();
            return;
        }

        try {
            // Block/unblock camera completely
            dpm.setCameraDisabled(adminComp, enabled);

            // Block/unblock microphone completely (Android 12 / API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.setMicrophoneDisabled(adminComp, enabled);
            }

            privacyEnabled = enabled;
        } catch (SecurityException e) {
            // Admin not properly active
            privacyEnabled = false;
            shizukuState = ShizukuState.UNKNOWN;
        }

        preferences.put("privacy_enabled", privacyEnabled);
        updateUI();
    }

    private void updateUI() {
        Tile tile = getQsTile();
        if (tile == null) return;
        switch (shizukuState) {
            case ShizukuState.NORMAL:
                tile.setState(privacyEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                tile.setIcon(privacyEnabled ? activeIcon : inactiveIcon);
                tile.setLabel(getString(privacyEnabled
                        ? R.string.tile_sensors_disabled
                        : R.string.tile_sensors_enabled));
                break;
            case ShizukuState.PERMISSION_WAIT:
            case ShizukuState.PERMISSION_DENIED:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(warningIcon);
                tile.setLabel(getString(R.string.tile_permission_required));
                break;
            case ShizukuState.BINDER_DEAD:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(warningIcon);
                tile.setLabel(getString(R.string.tile_shizuku_not_working));
                break;
            case ShizukuState.UNKNOWN:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setIcon(stopIcon);
                tile.setLabel(getString(R.string.tile_shizuku_error));
                break;
        }
        tile.updateTile();
    }

    @Override
    public IBinder onBind(Intent intent) {
        TileService.requestListeningState(
                this, new ComponentName(this, SensorsOffTileService.class));
        return super.onBind(intent);
    }

    private boolean checkShizukuPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
        if (Shizuku.isPreV11()) return false;
        if (Shizuku.shouldShowRequestPermissionRationale()) return false;
        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
        return false;
    }

    @ShizukuState
    private int checkShizukuState() {
        if (Shizuku.pingBinder()) {
            Shizuku.addRequestPermissionResultListener(this);
            return checkShizukuPermission() ? ShizukuState.NORMAL : ShizukuState.PERMISSION_WAIT;
        } else {
            Shizuku.addBinderReceivedListener(this);
            return ShizukuState.BINDER_DEAD;
        }
    }

    @Override
    public void onRequestPermissionResult(int requestCode, int grantResult) {
        shizukuState = (requestCode == REQUEST_CODE_SHIZUKU
                && grantResult == PackageManager.PERMISSION_GRANTED)
                ? ShizukuState.NORMAL : ShizukuState.PERMISSION_DENIED;
        Shizuku.removeRequestPermissionResultListener(this);
        updateUI();
    }

    @Override
    public void onBinderReceived() {
        shizukuState = checkShizukuState();
        Shizuku.removeBinderReceivedListener(this);
        updateUI();
    }

    @Override
    public void onBinderDead() {
        shizukuState = ShizukuState.BINDER_DEAD;
        Shizuku.addBinderReceivedListener(this);
        updateUI();
    }
}
