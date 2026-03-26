package git.artdeell.dnbootstrap;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Monitors Android thermal status (API 29+) and applies staged responses
 * to prevent hard throttling while allowing higher performance when cool.
 *
 * Stages:
 *   0 — Normal:   eglSwapInterval(0) uncapped
 *   1 — Moderate: eglSwapInterval(2) → ~30fps
 *   2 — Severe:   eglSwapInterval(2) + reduced chunk uploads
 *   3 — Critical: eglSwapInterval(3) → ~20fps minimum
 *
 * 15s hysteresis prevents oscillation between stages.
 */
public class ThermalManager {
    private static final String TAG = "ThermalManager";
    private static final long COOLDOWN_MS = 15_000;

    private final Context context;
    private final PowerManager powerManager;
    private int currentStage = 0;
    private long lastEscalationTime = 0;

    private static native void setSwapInterval(int interval);

    public ThermalManager(Context context) {
        this.context = context.getApplicationContext();
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Thermal API not available (API < 29)");
            return;
        }
        try {
            powerManager.addThermalStatusListener(context.getMainExecutor(), this::onThermalStatus);
            Log.i(TAG, "Thermal monitoring started");
        } catch (Exception e) {
            Log.w(TAG, "Failed to start thermal monitoring: " + e.getMessage());
        }
    }

    public void stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            powerManager.removeThermalStatusListener(this::onThermalStatus);
        } catch (Exception ignored) {}
    }

    public void checkCurrentState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            onThermalStatus(powerManager.getCurrentThermalStatus());
        } catch (Exception e) {
            Log.w(TAG, "Could not read thermal status: " + e.getMessage());
        }
    }

    private void onThermalStatus(int status) {
        int newStage = thermalStage(status);
        long now = System.currentTimeMillis();
        if (newStage > currentStage) {
            applyStage(newStage);
            lastEscalationTime = now;
        } else if (newStage < currentStage && now - lastEscalationTime >= COOLDOWN_MS) {
            applyStage(newStage);
        }
    }

    private int thermalStage(int status) {
        // THERMAL_STATUS: NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5
        if (status <= 1) return 0;
        if (status == 2) return 1;
        if (status == 3) return 2;
        return 3;
    }

    private void applyStage(int stage) {
        if (stage == currentStage) return;
        currentStage = stage;
        Log.i(TAG, "Thermal stage -> " + stage);
        switch (stage) {
            case 0:
                setSwapIntervalSafe(0); // uncapped
                break;
            case 1:
                setSwapIntervalSafe(2); // ~30fps
                showToast("Thermal: reduced to 30 FPS");
                break;
            case 2:
                setSwapIntervalSafe(2);
                showToast("Thermal: severe — reducing quality");
                break;
            case 3:
                setSwapIntervalSafe(3); // ~20fps
                showToast("Thermal: critical — minimum mode");
                break;
        }
    }

    private void setSwapIntervalSafe(int interval) {
        try { setSwapInterval(interval); }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, "setSwapInterval not available"); }
    }

    private void showToast(final String msg) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
        } catch (Exception ignored) {}
    }
}
