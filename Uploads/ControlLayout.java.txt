package git.artdeell.dnbootstrap.input;

import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import git.artdeell.dnbootstrap.glfw.GLFW;
import git.artdeell.dnbootstrap.glfw.KeyCodes;
import git.artdeell.dnbootstrap.glfw.GrabListener;
import git.artdeell.dnbootstrap.glfw.MouseCodes;
import git.artdeell.dnbootstrap.input.model.InputConfiguration;
import git.artdeell.dnbootstrap.input.model.LayoutDescription;
import git.artdeell.dnbootstrap.input.model.ViewCreator;
import git.artdeell.dnbootstrap.input.model.VisibilityConfiguration;

public class ControlLayout extends LoadableButtonLayout implements GrabListener {
    private final Rect hitTestRect = new Rect();
    private final HashMap<Integer, HitTarget> lastHitTargets = new HashMap<>();
    private final List<HitTarget> allHitTargets = new ArrayList<>();
    private final HitTarget defaultHitTarget = new HitTarget(new DefaultConsumer());
    private Context cont = getContext();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    public boolean cursorToTouch = false;

    // Layout switching — scanned from assets on first use
    private String[] availableLayouts = null;
    private String currentLayoutName = "layout-default.json";

    public ControlLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        GLFW.addGrabListener(this);
    }

    public ControlLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ControlLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ControlLayout(@NonNull Context context) {
        super(context);
    }

    // Unwrap context wrappers to find the underlying Activity
    // AlertDialog requires an Activity context — View.getContext() may return a wrapper
    private Context getActivityContext() {
        Context ctx = getContext();
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof android.app.Activity) return ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return getContext(); // fallback
    }

    // Called by ControlButton when SPECIAL_KEY_SWITCH_LAYOUT is held
    public void showLayoutPicker() {
        if (availableLayouts == null) {
            try {
                AssetManager am = getContext().getAssets();
                String[] all = am.list("");
                List<String> layouts = new ArrayList<>();
                if (all != null) {
                    for (String name : all) {
                        if (name.startsWith("layout-") && name.endsWith(".json")) {
                            layouts.add(name);
                        }
                    }
                }
                availableLayouts = layouts.toArray(new String[0]);
            } catch (Exception e) {
                availableLayouts = new String[]{"layout-default.json"};
            }
        }

        if (availableLayouts.length <= 1) {
            Toast.makeText(getActivityContext(), "No other layouts found in assets", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] displayNames = new String[availableLayouts.length];
        for (int i = 0; i < availableLayouts.length; i++) {
            String name = availableLayouts[i]
                    .replace("layout-", "")
                    .replace(".json", "");
            name = name.substring(0, 1).toUpperCase() + name.substring(1);
            displayNames[i] = availableLayouts[i].equals(currentLayoutName)
                    ? name + "  \u2713" : name;
        }

        post(() -> new AlertDialog.Builder(getActivityContext())
                .setTitle("Switch HUD Layout")
                .setItems(displayNames, (dialog, which) -> {
                    String chosen = availableLayouts[which];
                    if (!chosen.equals(currentLayoutName)) {
                        currentLayoutName = chosen;
                        loadByName(chosen);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    private HitTarget hitTest(int x, int y) {
        for (HitTarget hitTarget : allHitTargets) {
            View child = ((View) hitTarget.consumer);
            if (child.getVisibility() != View.VISIBLE) continue;
            hitTestRect.set(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            if (hitTestRect.contains(x, y)) return hitTarget;
        }
        return defaultHitTarget;
    }

    @Override
    public void onViewAdded(View view) {
        super.onViewAdded(view);
        if (!(view instanceof LayoutTouchConsumer)) return;
        LayoutTouchConsumer layoutTouchConsumer = (LayoutTouchConsumer) view;
        allHitTargets.add(new HitTarget(layoutTouchConsumer));
        updateVisibility(layoutTouchConsumer);
    }

    @Override
    public void onViewRemoved(View view) {
        super.onViewRemoved(view);
        if (allHitTargets.isEmpty()) return;
        if (!(view instanceof LayoutTouchConsumer)) return;
        Iterator<HitTarget> iter = allHitTargets.iterator();
        while (iter.hasNext()) {
            if (iter.next().consumer != view) continue;
            iter.remove();
            break;
        }
    }

    @Override
    protected void onRemoveAllViews() {
        super.onRemoveAllViews();
        allHitTargets.clear();
    }

    public void load(LayoutDescription description) {
        removeAllViews();
        setGridPitch(description.gridPitch);
        for (ViewCreator creator : description.buttonList) {
            addView(creator.createView(getContext()));
        }
        this.cursorToTouch = description.cursorToTouch;
    }

    private void processPointer(MotionEvent event, int pointer, int action) {
        int pointerId = event.getPointerId(pointer);
        HitTarget lastHit = lastHitTargets.get(pointerId);
        float x = event.getX(pointer), y = event.getY(pointer);

        if (action == MotionEvent.ACTION_MOVE) {
            if (lastHit != null && lastHit.isInitialTarget) {
                lastHit.onTouchPosition(pointerId, x - lastHit.consumer.getLeft(),
                        y - lastHit.consumer.getTop());
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (lastHit != null) lastHit.onTouchState(pointerId, false);
            lastHitTargets.remove(pointerId);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            HitTarget hit = hitTest((int) x, (int) y);
            if (hit != null) {
                hit.isInitialTarget = true;
                hit.onTouchState(pointerId, true);
                hit.onTouchPosition(pointerId, x - hit.consumer.getLeft(), y - hit.consumer.getTop());
            }
            lastHitTargets.put(pointerId, hit);
        }
    }

    private void releaseAllPointers() {
        for (HitTarget target : lastHitTargets.values()) {
            if (target == null) continue;
            target.reset();
        }
        lastHitTargets.clear();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int affectedPointer = -1;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                releaseAllPointers();
                processPointer(event, 0, MotionEvent.ACTION_POINTER_DOWN);
                break;
            case MotionEvent.ACTION_UP:
                releaseAllPointers();
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_POINTER_DOWN:
                affectedPointer = event.getActionIndex();
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int reportedAction = MotionEvent.ACTION_MOVE;
                    if (affectedPointer == i) reportedAction = action;
                    processPointer(event, i, reportedAction);
                }
                break;
        }
        return true;
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(this::updateVisibility);
    }

    private void updateVisibility(LayoutTouchConsumer layoutTouchConsumer) {
        boolean isGrabbing = GLFW.isGrabbing();
        VisibilityConfiguration vc = layoutTouchConsumer.getVisibilityConfiguration();
        boolean visible = (vc.showInGame && isGrabbing) || (vc.showInMenu && !isGrabbing);
        layoutTouchConsumer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateVisibility() {
        for (HitTarget hitTarget : allHitTargets) {
            if (hitTarget == defaultHitTarget) continue;
            updateVisibility(hitTarget.consumer);
        }
    }

    private class HitTarget {
        public final @NonNull LayoutTouchConsumer consumer;
        private int firstTouchedPointer = -1;
        private boolean lastState;
        private boolean isInitialTarget = false;

        private HitTarget(@NonNull LayoutTouchConsumer consumer) {
            this.consumer = consumer;
        }

        public void onTouchState(int pointerId, boolean isTouched) {
            if (pointerId != firstTouchedPointer && firstTouchedPointer != -1) return;
            if (!isTouched) firstTouchedPointer = -1;
            if (isTouched && firstTouchedPointer == -1) firstTouchedPointer = pointerId;
            if (isTouched != lastState) {
                lastState = isTouched;
                consumer.onTouchState(isTouched);
            }
        }

        public void onTouchPosition(int pointerId, float x, float y) {
            if (pointerId != firstTouchedPointer) return;
            consumer.onTouchPosition(x - consumer.getLeft(), y - consumer.getTop());
        }

        public void reset() {
            firstTouchedPointer = -1;
            isInitialTarget = false;
            if (lastState) consumer.onTouchState(false);
            lastState = false;
        }
    }

    public class DefaultConsumer implements LayoutTouchConsumer {
        private final InputConfiguration defaultConfiguration = new InputConfiguration();
        private boolean deltaReady = false;
        private float lastX, lastY;
        private long touchDownTime = 0;
        private boolean isLongPress = false;
        private static final long LONG_PRESS_THRESHOLD = 200;
        private float touchDownX = 0f, touchDownY = 0f;
        private boolean touchMovedTooFar = false;
        private static final float MOVE_SLOP = 20f;

        @Override
        public void onTouchState(boolean isTouched) {
            if (isTouched) {
                touchDownTime = System.currentTimeMillis();
                isLongPress = false;
                touchMovedTooFar = false;
                deltaReady = false;
            } else {
                long touchDuration = System.currentTimeMillis() - touchDownTime;
                if (isLongPress) {
                    GLFW.sendMouseEvent(MouseCodes.GLFW_MOUSE_BUTTON_LEFT, KeyCodes.GLFW_RELEASE, 0);
                } else if (touchDuration < LONG_PRESS_THRESHOLD && !touchMovedTooFar) {
                    int shortTouch = GLFW.isGrabbing()
                            ? MouseCodes.GLFW_MOUSE_BUTTON_RIGHT
                            : MouseCodes.GLFW_MOUSE_BUTTON_LEFT;
                    GLFW.sendMouseEvent(shortTouch, KeyCodes.GLFW_PRESS, 0);
                    new Handler().postDelayed(() ->
                            GLFW.sendMouseEvent(shortTouch, KeyCodes.GLFW_RELEASE, 0), 20);
                }
            }
            lastX = lastY = 0;
            deltaReady = false;
        }

        @Override
        public void onTouchPosition(float x, float y) {
            if (!deltaReady) {
                lastX = x;
                lastY = y;
                touchDownX = x;
                touchDownY = y;
                deltaReady = true;
                if (cursorToTouch && !GLFW.isGrabbing()) {
                    GLFW.cursorX = x / getWidth();
                    GLFW.cursorY = y / getHeight();
                    GLFW.sendMousePos();
                }
                return;
            }

            float dxFromDown = x - touchDownX;
            float dyFromDown = y - touchDownY;
            if (!touchMovedTooFar && (dxFromDown * dxFromDown + dyFromDown * dyFromDown) > MOVE_SLOP * MOVE_SLOP) {
                touchMovedTooFar = true;
            }

            if (!isLongPress && !touchMovedTooFar && System.currentTimeMillis() - touchDownTime >= LONG_PRESS_THRESHOLD) {
                isLongPress = true;
                GLFW.sendMouseEvent(MouseCodes.GLFW_MOUSE_BUTTON_LEFT, KeyCodes.GLFW_PRESS, 0);
            }

            if (GLFW.isGrabbing()) {
                float deltaX = x - lastX;
                float deltaY = y - lastY;
                GLFW.cursorX += deltaX / getWidth();
                GLFW.cursorY += deltaY / getHeight();
                GLFW.sendMousePos();
            } else if (cursorToTouch) {
                GLFW.cursorX = x / getWidth();
                GLFW.cursorY = y / getHeight();
                GLFW.sendMousePos();
            } else {
                float deltaX = x - lastX;
                float deltaY = y - lastY;
                GLFW.cursorX += deltaX / getWidth();
                GLFW.cursorY += deltaY / getHeight();
                GLFW.sendMousePos();
            }

            lastX = x;
            lastY = y;
        }

        @Override public void setVisibility(int visibility) {}
        @Override public int getLeft() { return 0; }
        @Override public int getTop() { return 0; }

        @NonNull
        @Override
        public InputConfiguration getInputConfiguration() { return defaultConfiguration; }

        @Override
        public VisibilityConfiguration getVisibilityConfiguration() { return null; }
    }
}
