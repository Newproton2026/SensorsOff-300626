package ru.liner.sensorprivacy.service;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;

import ru.liner.sensorprivacy.Application;
import ru.liner.sensorprivacy.R;
import ru.liner.sensorprivacy.SensorsOffAdminReceiver;
import ru.liner.sensorprivacy.preference.Preferences;
import ru.liner.sensorprivacy.receiver.RestartReceiver;
import ru.liner.sensorprivacy.utils.Broadcast;
import ru.liner.sensorprivacy.utils.Consumer;

/**
 * Per-app blocking service using DevicePolicyManager.
 * Blocks ONLY camera + microphone for apps in the blocked list.
 */
public class BlockingService extends ImmortalService {
    public static final String WINDOW_CHANGE_ACTION = "ru.liner.sensorprivacy.WINDOW_CHANGE_ACTION";
    public static final String EXTRA_PACKAGE_NAME   = "package_name";
    public static final String EXTRA_CLASS_NAME     = "class_name";

    private Preferences preferences;
    private Broadcast windowChangeListener;
    private DevicePolicyManager dpm;
    private ComponentName adminComp;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new Preferences(this);
        dpm       = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComp = new ComponentName(this, SensorsOffAdminReceiver.class);

        windowChangeListener = new Broadcast(this, WINDOW_CHANGE_ACTION) {
            @Override
            public void handle(@NonNull Context context, @NonNull Intent intent) {
                Consumer.of(intent.getAction()).ifPresent(input -> {
                    if (input.equals(WINDOW_CHANGE_ACTION)) {
                        boolean shouldBlock = preferences
                                .getList("blocked_application_list", String.class)
                                .contains(intent.getStringExtra(EXTRA_PACKAGE_NAME));
                        if (dpm.isAdminActive(adminComp)) {
                            try {
                                dpm.setCameraDisabled(adminComp, shouldBlock);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    dpm.setMicrophoneDisabled(adminComp, shouldBlock);
                                }
                            } catch (SecurityException ignored) { }
                        }
                    }
                });
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        windowChangeListener.register();
        if (!Application.isAccessibilityServiceEnabled(this))
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { super.onDestroy(); windowChangeListener.unregister(); }

    @NonNull @Override public String getChannelID() { return BlockingService.class.getSimpleName(); }
    @NonNull @Override public String getChannelDescription() { return "Blocks camera & mic only"; }
    @Override public int getNotificationIcon() { return R.drawable.tile_icon_sensorsoff_active; }
    @NonNull @Override public Class<? extends BroadcastReceiver> getRestartReceiverClass() {
        return RestartReceiver.class;
    }
}
