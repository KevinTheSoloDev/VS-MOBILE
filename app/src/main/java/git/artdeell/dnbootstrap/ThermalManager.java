package git.artdeell.dnbootstrap;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Monitors Android thermal status (API 29+) and can optionally trade frame rate
 * for temperature head-room.
 *
 * <p><b>Disabled by default.</b> An earlier revision was always on and clamped
 * the swap interval to 2 (3 at critical) as soon as the device reported
 * THERMAL_STATUS_MODERATE. {@code eglSwapInterval(n)} means refresh-rate/n, so
 * that silently pinned the game at refresh/2 -- 60 FPS on a 120 Hz panel, 30 on
 * a 60 Hz one -- no matter what the game or the user asked for. Throttling is
 * now opt-in.
 *
 * <p>To enable it, create {@code thermal-throttle.txt} in the app's files dir
 * containing {@code on}. When enabled the stages are:
 * <pre>
 *   0 - Normal:   eglSwapInterval(0) uncapped
 *   1 - Moderate: eglSwapInterval(1) vsync
 *   2 - Severe:   eglSwapInterval(2) half refresh
 *   3 - Critical: eglSwapInterval(2) half refresh (never lower)
 * </pre>
 *
 * <p>Escalation is immediate; de-escalation waits {@link #COOLDOWN_MS} of stable
 * readings so the stages do not oscillate.
 */
public class ThermalManager {
    private static final String TAG = "ThermalManager";
    private static final long COOLDOWN_MS = 15_000;

    /** File in the app files dir that turns throttling on. */
    private static final String ENABLE_FILE = "thermal-throttle.txt";

    private final Context context;
    private final PowerManager powerManager;
    private final boolean enabled;
    private final Handler mainHandler;

    private int currentStage = 0;
    private long lastStageChangeTime = 0;

    /**
     * Must be a field. {@link PowerManager#removeThermalStatusListener} matches
     * by listener identity, so passing a fresh {@code this::onThermalStatus}
     * method reference removes nothing.
     */
    private Consumer<Integer> thermalListener;

    private static native void setSwapInterval(int interval);

    public ThermalManager(Context context) {
        this.context = context.getApplicationContext();
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.enabled = readEnabledFlag(this.context);
    }

    private static boolean readEnabledFlag(Context context) {
        File flag = new File(context.getFilesDir(), ENABLE_FILE);
        if (!flag.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(flag))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                return line.equalsIgnoreCase("on") || line.equalsIgnoreCase("1")
                        || line.equalsIgnoreCase("true");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read " + ENABLE_FILE + ", thermal throttling stays off", e);
        }
        return false;
    }

    public void start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "Thermal API not available (API < 29)");
            return;
        }
        if (!enabled) {
            Log.i(TAG, "Thermal throttling disabled (frame rate left uncapped). "
                    + "Create " + ENABLE_FILE + " containing 'on' to enable.");
            return;
        }
        try {
            thermalListener = this::onThermalStatus;
            powerManager.addThermalStatusListener(context.getMainExecutor(), thermalListener);
            Log.i(TAG, "Thermal monitoring started");
        } catch (Exception e) {
            thermalListener = null;
            Log.w(TAG, "Failed to start thermal monitoring: " + e.getMessage());
        }
    }

    public void stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (thermalListener == null) return;
        try {
            powerManager.removeThermalStatusListener(thermalListener);
        } catch (Exception ignored) {
        } finally {
            thermalListener = null;
        }
    }

    public void checkCurrentState() {
        if (!enabled) return;
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
            lastStageChangeTime = now;
        } else if (newStage < currentStage && now - lastStageChangeTime >= COOLDOWN_MS) {
            applyStage(newStage);
            // Reset the clock on the way down too. Without this, a device that
            // cooled off once could drop back through every stage on the next
            // blip with no hysteresis at all.
            lastStageChangeTime = now;
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
                setSwapIntervalSafe(1); // vsync
                showToast("Thermal: capping to refresh rate");
                break;
            case 2:
            case 3:
                setSwapIntervalSafe(2); // half refresh -- floor, never lower
                showToast("Thermal: reducing frame rate to stay cool");
                break;
        }
    }

    private void setSwapIntervalSafe(int interval) {
        try {
            setSwapInterval(interval);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "setSwapInterval not available");
        }
    }

    private void showToast(final String msg) {
        try {
            mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
        } catch (Exception ignored) {
        }
    }
}
