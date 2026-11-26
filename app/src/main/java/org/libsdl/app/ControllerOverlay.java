package org.libsdl.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.ikemen_engine.ikemen_go.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ControllerOverlay extends RelativeLayout {
    private int virtualDeviceId;
    private int hatX, hatY = 0;
    private final Map<Integer, Set<Integer>> pointerStates = new HashMap<>(); // PointerID -> Keycode/Axis
    private boolean isInitialized = false;

    // =====================================================================
    // Layout editor: long-press the gear (top-left) to move / resize controls
    // =====================================================================
    private static final String PREFS_NAME = "controller_layout";
    private static final float MIN_SCALE = 0.4f;
    private static final float MAX_SCALE = 3.0f;

    private final int[] editableIds = {
            R.id.dpad_container, R.id.left_analog, R.id.right_analog,
            R.id.btn_a, R.id.btn_b, R.id.btn_c, R.id.btn_d,
            R.id.btn_w, R.id.btn_x, R.id.btn_y, R.id.btn_z,
            R.id.btn_back, R.id.btn_start
    };
    private final String[] editableNames = {
            "dpad", "left_analog", "right_analog",
            "btn_a", "btn_b", "btn_c", "btn_d",
            "btn_w", "btn_x", "btn_y", "btn_z",
            "btn_back", "btn_start"
    };

    private boolean editMode = false;
    private View selectedView = null;
    private boolean dragging = false;
    private float lastTouchX, lastTouchY;
    private int togglePointerId = -1;
    private TextView editToggle;
    private LinearLayout editToolbar;
    private ScaleGestureDetector scaleDetector;
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable enterEditRunnable = new Runnable() {
        @Override
        public void run() {
            if (togglePointerId != -1) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                enterEditMode();
            }
        }
    };

    private SharedPreferences layoutPrefs() {
        return getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadLayout() {
        SharedPreferences p = layoutPrefs();
        for (int i = 0; i < editableIds.length; i++) {
            View v = findViewById(editableIds[i]);
            if (v == null) continue;
            String n = editableNames[i];
            v.setTranslationX(p.getFloat(n + "_tx", 0f));
            v.setTranslationY(p.getFloat(n + "_ty", 0f));
            float sc = p.getFloat(n + "_s", 1f);
            v.setScaleX(sc);
            v.setScaleY(sc);
        }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = layoutPrefs().edit();
        for (int i = 0; i < editableIds.length; i++) {
            View v = findViewById(editableIds[i]);
            if (v == null) continue;
            String n = editableNames[i];
            e.putFloat(n + "_tx", v.getTranslationX());
            e.putFloat(n + "_ty", v.getTranslationY());
            e.putFloat(n + "_s", v.getScaleX());
        }
        e.apply();
    }

    private void resetLayout() {
        for (int id : editableIds) {
            View v = findViewById(id);
            if (v == null) continue;
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        saveLayout();
        invalidate();
    }

    private void buildEditorUi(Context context) {
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(dpToPx(2));
        outlinePaint.setColor(0xAAFFFFFF);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(dpToPx(3));
        selectedPaint.setColor(0xFFFFC107);

        // Gear: long-press (~0.7s) to enter edit mode
        editToggle = new TextView(context);
        editToggle.setText("\u2699");
        editToggle.setTextColor(Color.WHITE);
        editToggle.setTextSize(22);
        editToggle.setGravity(Gravity.CENTER);
        editToggle.setAlpha(0.4f);
        RelativeLayout.LayoutParams tp = new RelativeLayout.LayoutParams(dpToPx(44), dpToPx(44));
        tp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        tp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        tp.setMargins(dpToPx(8), dpToPx(8), 0, 0);
        addView(editToggle, tp);

        // Toolbar (only visible in edit mode)
        editToolbar = new LinearLayout(context);
        editToolbar.setOrientation(LinearLayout.HORIZONTAL);
        editToolbar.setGravity(Gravity.CENTER_VERTICAL);
        editToolbar.setBackgroundColor(0xCC000000);
        editToolbar.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        editToolbar.setVisibility(View.GONE);

        TextView hint = new TextView(context);
        hint.setText("Arraste: mover  |  Pin\u00e7a ou \u2212 / +: tamanho");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setPadding(0, 0, dpToPx(8), 0);
        editToolbar.addView(hint);

        addToolbarButton("\u2212", new Runnable() {
            @Override public void run() { scaleSelected(0.9f); }
        });
        addToolbarButton("+", new Runnable() {
            @Override public void run() { scaleSelected(1.1f); }
        });
        addToolbarButton("Reset", new Runnable() {
            @Override public void run() { resetLayout(); }
        });
        addToolbarButton("OK", new Runnable() {
            @Override public void run() { exitEditMode(); }
        });

        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        addView(editToolbar, lp);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        if (selectedView == null) {
                            selectedView = editableAt(d.getFocusX(), d.getFocusY());
                            invalidate();
                        }
                        return selectedView != null;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        scaleSelected(d.getScaleFactor());
                        return true;
                    }
                });

        setWillNotDraw(false);
    }

    private void addToolbarButton(String label, final Runnable action) {
        Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setMinWidth(dpToPx(44));
        b.setMinimumWidth(dpToPx(44));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        editToolbar.addView(b);
    }

    private void enterEditMode() {
        togglePointerId = -1;
        // Release anything currently held so nothing gets stuck while editing
        for (Integer id : new ArrayList<>(pointerStates.keySet())) {
            releasePointer(id);
        }
        if (leftJoyPointerId != -1) {
            resetJoystick(findViewById(R.id.left_analog));
            leftJoyPointerId = -1;
        }
        if (rightJoyPointerId != -1) {
            resetJoystick(findViewById(R.id.right_analog));
            rightJoyPointerId = -1;
        }
        if (hatX != 0 || hatY != 0) {
            hatX = 0;
            hatY = 0;
            SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
            updateDpadVisual(0, 0);
        }
        editMode = true;
        selectedView = null;
        dragging = false;
        editToolbar.setVisibility(View.VISIBLE);
        editToggle.setVisibility(View.GONE);
        invalidate();
    }

    private void exitEditMode() {
        saveLayout();
        editMode = false;
        selectedView = null;
        dragging = false;
        editToolbar.setVisibility(View.GONE);
        editToggle.setVisibility(View.VISIBLE);
        invalidate();
    }

    private void scaleSelected(float factor) {
        if (selectedView == null) return;
        float sc = selectedView.getScaleX() * factor;
        sc = Math.max(MIN_SCALE, Math.min(MAX_SCALE, sc));
        selectedView.setScaleX(sc);
        selectedView.setScaleY(sc);
        invalidate();
    }

    private void moveSelected(float dx, float dy) {
        View v = selectedView;
        if (v == null) return;
        float ox = v.getTranslationX();
        float oy = v.getTranslationY();
        v.setTranslationX(ox + dx);
        v.setTranslationY(oy + dy);
        RectF r = rectOf(v);
        // Keep the control's center on screen so it can't get lost
        if (r.centerX() < 0 || r.centerX() > getWidth() || r.centerY() < 0 || r.centerY() > getHeight()) {
            v.setTranslationX(ox);
            v.setTranslationY(oy);
        }
        invalidate();
    }

    // Picks the smallest visible control under the finger (so buttons win over big areas)
    private View editableAt(float x, float y) {
        View best = null;
        float bestArea = Float.MAX_VALUE;
        for (int id : editableIds) {
            View v = findViewById(id);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            RectF r = rectOf(v);
            if (r.contains(x, y)) {
                float area = r.width() * r.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = v;
                }
            }
        }
        return best;
    }

    private View toolbarButtonAt(float x, float y) {
        if (editToolbar == null || editToolbar.getVisibility() != View.VISIBLE) return null;
        for (int i = 0; i < editToolbar.getChildCount(); i++) {
            View c = editToolbar.getChildAt(i);
            if (!(c instanceof Button)) continue;
            RectF r = rectOf(c);
            r.inset(-dpToPx(4), -dpToPx(4));
            if (r.contains(x, y)) return c;
        }
        return null;
    }

    private boolean handleEditTouch(MotionEvent ev) {
        scaleDetector.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = ev.getX();
                float y = ev.getY();
                View tb = toolbarButtonAt(x, y);
                if (tb != null) {
                    dragging = false;
                    tb.performClick();
                    return true;
                }
                View hit = editableAt(x, y);
                if (hit != null) {
                    selectedView = hit;
                    dragging = true;
                    lastTouchX = x;
                    lastTouchY = y;
                } else {
                    dragging = false;
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && ev.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float x = ev.getX();
                    float y = ev.getY();
                    moveSelected(x - lastTouchX, y - lastTouchY);
                    lastTouchX = x;
                    lastTouchY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                saveLayout();
                break;
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!editMode) return;
        for (int id : editableIds) {
            View v = findViewById(id);
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            canvas.drawRect(rectOf(v), v == selectedView ? selectedPaint : outlinePaint);
        }
    }

    // Position/size of a control in this overlay's coordinates, including
    // translation and scale (getLocationOnScreen already applies transforms).
    private RectF rectOf(View v) {
        int[] loc = new int[2];
        int[] pLoc = new int[2];
        v.getLocationOnScreen(loc);
        getLocationOnScreen(pLoc);
        float left = loc[0] - pLoc[0];
        float top = loc[1] - pLoc[1];
        return new RectF(left, top,
                left + v.getWidth() * v.getScaleX(),
                top + v.getHeight() * v.getScaleY());
    }

    public int getPhysicalJoystickCount() {
        int count = 0;
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            // Check if the device is a physical joystick or gamepad
            // OLD LOGIC: Checked only flags (Let sensors through)
            // NEW LOGIC: Checks isRealGamepad (Stricter filtering by querying for buttons)
            if (isRealGamepad(dev)) {
                count++;
            }
        }
        return count;
    }

    // Helper to distinguish real controllers from "Ghost" sensors
    private boolean isRealGamepad(InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        boolean isJoystick = ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ||
                ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);

        // Must identify as joystick/gamepad
        if (!isJoystick) return false;

        // Ignore virtual devices (optional, prevents software loopbacks)
        if (device.isVirtual()) return false;

        // Ghost filter, does it have actual buttons?
        // Sensors report as Joysticks but have NO buttons.
        // Real gamepads always have at least one of these.
        int[] keysToCheck = {
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
        };

        boolean[] hasKeys = device.hasKeys(keysToCheck);
        for (boolean exists : hasKeys) {
            if (exists) return true; // It has any of these buttons, it's a real gamepad
        }

        return false; // No buttons, its a sensor, ignore it
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isInitialized) {
            initializeVirtualController();
            isInitialized = true;
        }
        return true;
    }

    private int leftJoyPointerId = -1;
    private int rightJoyPointerId = -1;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode) {
            return handleEditTouch(event);
        }

        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pId = event.getPointerId(index);

        // Get raw coordinates for global hit detection
        float x = event.getX(index);
        float y = event.getY(index);

        // Gear (top-left): long-press opens the layout editor
        if (action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(enterEditRunnable);
            togglePointerId = -1;
        } else if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                && isViewAtLocation(editToggle, x, y)) {
            togglePointerId = pId;
            postDelayed(enterEditRunnable, 700);
            return true;
        } else if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                && pId == togglePointerId) {
            removeCallbacks(enterEditRunnable);
            togglePointerId = -1;
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            // Check Joysticks first
            if (isViewAtLocation(findViewById(R.id.left_analog), x, y)) {
                leftJoyPointerId = pId;
            } else if (isViewAtLocation(findViewById(R.id.right_analog), x, y)) {
                rightJoyPointerId = pId;
            } else {
                // If not a stick, it's a button/dpad
                updatePointer(pId, getButtonsAt(x, y));
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int movePId = event.getPointerId(i); // The stable ID
                if (movePId == togglePointerId) continue; // finger resting on the gear

                // Get coordinates specifically for THIS pointer index
                float mx = event.getX(i);
                float my = event.getY(i);

                if (movePId == leftJoyPointerId) {
                    updateJoystickLogic(findViewById(R.id.left_analog), mx, my);
                } else if (movePId == rightJoyPointerId) {
                    updateJoystickLogic(findViewById(R.id.right_analog), mx, my);
                } else {
                    Set<Integer> currentButtons = getButtonsAt(mx, my);
                    Set<Integer> lastButtons = pointerStates.get(movePId);

                    // If the sets are different, update the physical state
                    if (!currentButtons.equals(lastButtons)) {
                        updatePointer(movePId, currentButtons);
                    }
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pId == leftJoyPointerId) {
                resetJoystick(findViewById(R.id.left_analog));
                leftJoyPointerId = -1;
            } else if (pId == rightJoyPointerId) {
                resetJoystick(findViewById(R.id.right_analog));
                rightJoyPointerId = -1;
            } else {
                releasePointer(pId);
            }
        }

        updateDpadState(event);
        return true;
    }

    private void updateJoystickLogic(View v, float x, float y) {
        if (!(v instanceof JoystickOverlay)) return;
        JoystickOverlay joy = (JoystickOverlay) v;

        // On-screen center/radius (includes the user's move/resize)
        RectF r = rectOf(v);
        float centerX = r.centerX();
        float centerY = r.centerY();
        float radius = r.width() / 3f;

        float dx = x - centerX;
        float dy = y - centerY;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);

        if (distance > radius) {
            dx = (dx / distance) * radius;
            dy = (dy / distance) * radius;
        }

        float nx = dx / radius;
        float ny = dy / radius;
        // The view draws in its own (unscaled) coordinates
        joy.updateVisualPos((v.getWidth() / 2f) + nx * (v.getWidth() / 3f),
                (v.getHeight() / 2f) + ny * (v.getWidth() / 3f));
        joy.sendToSDL(nx, ny);
    }

    private void resetJoystick(View v) {
        if (!(v instanceof JoystickOverlay)) return;
        JoystickOverlay joy = (JoystickOverlay) v;
        joy.updateVisualPos(v.getWidth()/2f, v.getHeight()/2f);
        joy.sendToSDL(0, 0);
    }

    private void updatePointer(int pointerId, Set<Integer> newCodes) {
        Set<Integer> oldCodes = pointerStates.get(pointerId);
        if (oldCodes == null) oldCodes = new HashSet<>();
        // Find buttons that were RELEASED (in old set, not in new)
        for (Integer oldCode : oldCodes) {
            if (!newCodes.contains(oldCode)) {
                handleInput(oldCode, false);
            }
        }
        // Find buttons that were PRESSED (in new set, not in old)
        for (Integer newCode : newCodes) {
            if (!oldCodes.contains(newCode)) {
                handleInput(newCode, true);
            }
        }
        // Save the new state for this finger
        pointerStates.put(pointerId, newCodes);
    }

    private void releasePointer(int pointerId) {
        Set<Integer> lastCodes = pointerStates.remove(pointerId);
        if (lastCodes != null) {
            for (Integer code : lastCodes) {
                handleInput(code, false);
            }
        }
    }

    private void handleInput(final int code, boolean pressed) {
        if (code == -1) return;

        if (code >= 4000) { // TRIGGERS
            int axis = code - 4000;
            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
        }
        else if (code >= 1000) {
            // D-PAD visuals only (Logic remains in updateDpadState)
        }
        else { // STANDARD BUTTONS
            if (pressed) {
                SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
                int result = SDLControllerManager.onNativePadDown(virtualDeviceId, code);
                if (result < 0) {
                    android.util.Log.e("SDL", "INPUT FAILURE: Device " + virtualDeviceId + " rejected button " + code);
                }
            } else {
                SDLControllerManager.onNativePadUp(virtualDeviceId, code);
            }
        }

        // Always update visuals
        updateButtonVisual(code, pressed);
    }

    private void updateButtonVisual(int code, boolean pressed) {
        int viewId = -1;
        switch (code) {
            case 96:  viewId = R.id.btn_a; break;
            case 97:  viewId = R.id.btn_b; break;
            case 99:  viewId = R.id.btn_x; break;
            case 100: viewId = R.id.btn_y; break;
            case 102: viewId = R.id.btn_d; break;
            case 103: viewId = R.id.btn_z; break;
            case 108: viewId = R.id.btn_start; break;
            case 109: viewId = R.id.btn_back; break;
            case 1001: viewId = R.id.dp_up; break;
            case 1002: viewId = R.id.dp_down; break;
            case 1003: viewId = R.id.dp_left; break;
            case 1004: viewId = R.id.dp_right; break;
//            case 1005: viewId = R.id.dp_upleft; break;
//            case 1006: viewId = R.id.dp_downright; break;
//            case 1007: viewId = R.id.dp_downleft; break;
//            case 1008: viewId = R.id.dp_upright; break;
            case 4004: viewId = R.id.btn_w; break;
            case 4005: viewId = R.id.btn_c; break;
        }

        if (viewId != -1) {
            View v = findViewById(viewId);
            if (v != null) v.setPressed(pressed);
        }
    }

    private void updateDpadState(MotionEvent event) {
        int newX = 0, newY = 0;
        int action = event.getActionMasked();

        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            View container = findViewById(R.id.dpad_container);
            if (container == null) return;

            // On-screen center/radius (includes the user's move/resize)
            RectF cr = rectOf(container);
            float centerX = cr.centerX();
            float centerY = cr.centerY();
            float radius = cr.width() / 2f;

            for (int i = 0; i < event.getPointerCount(); i++) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex()) continue;

                float x = event.getX(i);
                float y = event.getY(i);

                // Calculate distance from center (Pythagoras)
                float dx = x - centerX;
                float dy = y - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                // Only process if the finger is actually touching the D-Pad area
                // (with  a small "deadzone" in the middle so neutral is easy to hit)
                if (distance > (radius * 0.2f) && distance < (radius * 1.5f)) {
                    // Calculate angle in degrees (0 to 360)
                    double angle = Math.toDegrees(Math.atan2(dy, dx));
                    if (angle < 0) angle += 360;

                    // Map angle to 8 directions with wider diagonals
                    // Cardinals get 30 degrees, Diagonals get 60 degrees
                    if (angle >= 345 || angle < 15)        newX = 1;  // Right (Narrower)
                    else if (angle >= 15  && angle < 75)   { newX = 1; newY = 1; }   // Down-Right (Wider)
                    else if (angle >= 75  && angle < 105)  newY = 1; // Down
                    else if (angle >= 105 && angle < 165)  { newX = -1; newY = 1; }  // Down-Left
                    else if (angle >= 165 && angle < 195)  newX = -1; // Left
                    else if (angle >= 195 && angle < 255)  { newX = -1; newY = -1; } // Up-Left
                    else if (angle >= 255 && angle < 285)  newY = -1; // Up
                    else if (angle >= 285 && angle < 345)  { newX = 1; newY = -1; }  // Up-Right

                    // Map angle to 8 directions
//                    if (angle >= 337.5 || angle < 22.5)   newX = 1;  // Right
//                    else if (angle >= 22.5  && angle < 67.5)  { newX = 1; newY = 1; }   // Down-Right
//                    else if (angle >= 67.5  && angle < 112.5) newY = 1; // Down
//                    else if (angle >= 112.5 && angle < 157.5) { newX = -1; newY = 1; }  // Down-Left
//                    else if (angle >= 157.5 && angle < 202.5) newX = -1; // Left
//                    else if (angle >= 202.5 && angle < 247.5) { newX = -1; newY = -1; } // Up-Left
//                    else if (angle >= 247.5 && angle < 292.5) newY = -1; // Up
//                    else if (angle >= 292.5 && angle < 337.5) { newX = 1; newY = -1; }  // Up-Right

                    break; // One finger is enough for the D-pad
                }
            }
        }

        if (newX != hatX || newY != hatY) {
            hatX = newX;
            hatY = newY;
            SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
            updateDpadVisual(hatX, hatY);
        }
    }

    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private Set<Integer> getButtonsAt(float x, float y) {
        Set<Integer> detectedButtons = new HashSet<>();

        // Define a "padding" factor in pixels. This allows the thumb to trigger
        // two buttons if it's in the gap between them.
        int padding = dpToPx(16);

        checkAndAdd(detectedButtons, R.id.btn_a, 96, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_b, 97, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_x, 99, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_y, 100, x, y, padding);
        checkAndAdd(detectedButtons, R.id.btn_d, 102, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_z, 103, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_w, 4004, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_c, 4005, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_start, 108, x,y, padding);
        checkAndAdd(detectedButtons, R.id.btn_back, 109, x,y, padding);

        return detectedButtons;
    }

    private void checkAndAdd(Set<Integer> set, int viewId, int code, float x, float y, int pad) {
        View v = findViewById(viewId);
        if (v == null) return;

        // Region (with move/resize applied) + padding
        RectF r = rectOf(v);
        r.inset(-pad, -pad);
        if (r.contains(x, y)) {
            set.add(code);
        }
    }

    private void updateDpadVisual(int hX, int hY) {
        findViewById(R.id.dp_up).setPressed(hY == -1);
        findViewById(R.id.dp_down).setPressed(hY == 1);
        findViewById(R.id.dp_left).setPressed(hX == -1);
        findViewById(R.id.dp_right).setPressed(hX == 1);
    }

    // Helper to check if a finger is inside a specific button's area
    private boolean isViewAtLocation(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;

        return rectOf(v).contains(x, y);
    }

    public ControllerOverlay(Context context) {
        super(context);
        this.setClickable(true);
        this.setFocusable(true);
        this.setEnabled(true);
        this.setMotionEventSplittingEnabled(true);

        // Inflate the buttons
        LayoutInflater inflater = LayoutInflater.from(context);
        View vc = inflater.inflate(R.layout.virtual_controller, this, true);
        disableAllTouches(vc);
        setClipChildren(false);
        buildEditorUi(context);
        loadLayout();
    }

    private void disableAllTouches(View v) {
        v.setClickable(false);
        v.setFocusable(false);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // Moved/scaled controls must be allowed to draw outside their old parent bounds
            vg.setClipChildren(false);
            vg.setClipToPadding(false);
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableAllTouches(vg.getChildAt(i));
            }
        }
    }

    public void initializeVirtualController() {
        android.util.Log.i("ControllerOverlay", "DEBUG: initializing virtual joystick...");
        virtualDeviceId = getPhysicalJoystickCount();
        pointerStates.clear();
        hatX = 0;
        hatY = 0;
        android.util.Log.i("ControllerOverlay", String.format("DEBUG: virtualDeviceID = %d", virtualDeviceId));
        SDLControllerManager.nativeRemoveJoystick(virtualDeviceId);
        ensureJoystickAlive();
        // Initialize the joysticks
        JoystickOverlay ls = findViewById(R.id.left_analog);
        ls.setAttrs(virtualDeviceId, 0, 1);

        JoystickOverlay rs = findViewById(R.id.right_analog);
        rs.setAttrs(virtualDeviceId, 2, 3);

        for (int i = 0; i < 6; i++) {
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.00390625f);
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.0f);
        }
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
        android.util.Log.i("ControllerOverlay", "DEBUG: virtual joystick initialized!");
        isInitialized = true;
    }

    private int ensureJoystickAlive() {
        return SDLControllerManager.nativeAddJoystick(virtualDeviceId, "Xbox 360 Controller", "Gamepad", 0x045E, 0x028E, false, 0xFFF, 6, 0x3F, 1, 0);
    }
}
