package com.safeher.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * PinchAccessibilityService
 *
 * An Android AccessibilityService that captures raw touch events system-wide —
 * including on the lock screen — and detects a two-finger pinch-OUT (spread)
 * gesture to trigger SOS.
 *
 * WHY ACCESSIBILITY SERVICE?
 * ─────────────────────────
 * Touch events on a locked screen cannot be received by a normal activity or
 * overlay window without the SYSTEM_ALERT_WINDOW permission AND screen-on state.
 * An AccessibilityService is the only Android-sanctioned way to receive raw
 * touch input at the system level regardless of lock state, provided the user
 * has granted accessibility permission in Settings.
 *
 * HOW IT WORKS
 * ────────────
 * 1. User grants Accessibility permission in Settings → Accessibility → SaveSouls.
 * 2. Service starts and registers with FLAG_REQUEST_TOUCH_EXPLORATION_MODE so
 *    Android routes raw MotionEvents to onMotionEvent().
 * 3. We track two pointer positions and compute the span (distance between fingers).
 * 4. When the span grows by ≥ SPREAD_THRESHOLD_RATIO (60%) within GESTURE_WINDOW_MS
 *    AND the spread velocity is fast enough → SOS fires after a CONFIRM_DELAY_MS
 *    grace period (lets user abort if accidental).
 * 5. A vibration pulse confirms detection; a long vibration fires with SOS.
 *
 * IMPORTANT: This service does NOT draw any UI on top of the lock screen (that
 * would require SYSTEM_ALERT_WINDOW). It only intercepts touch and fires the
 * SOS intent silently. The SosActivity will appear over the lock screen because
 * it has showOnLockScreen + turnScreenOn flags set in the manifest.
 */
public class PinchAccessibilityService extends AccessibilityService {

    private static final String TAG = "PinchAccSvc";

    public static final String PREF_KEY       = "pinch_accessibility_enabled";
    public static volatile boolean isRunning  = false;

    // ── Tuning ────────────────────────────────────────────────────────────────
    /** How much the span must grow (ratio) to confirm gesture: 1.55 = 55% spread */
    private static final float SPREAD_THRESHOLD_RATIO = 1.55f;
    /** Max time window to complete the spread gesture */
    private static final long  GESTURE_WINDOW_MS      = 1800;
    /** Minimum span velocity (px/s) — prevents slow accidental spreads */
    private static final float MIN_VELOCITY_PX_S      = 180f;
    /** Grace period after detection before SOS fires (user can lift fingers to abort) */
    private static final long  CONFIRM_DELAY_MS        = 1500;
    /** Min initial span (px) to start tracking — requires real two-finger touch */
    private static final float MIN_INITIAL_SPAN_PX     = 60f;

    // ── Gesture state ─────────────────────────────────────────────────────────
    private float initialSpan   = 0f;
    private float lastSpan      = 0f;
    private long  gestureStart  = 0;
    private long  lastSpanTime  = 0;
    private float maxVelocity   = 0f;
    private boolean tracking    = false;
    private boolean sosPending  = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable sosRunnable;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isRunning = true;
        Log.d(TAG, "Accessibility service connected");

        // Configure to receive raw touch events
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Accessibility service destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't need accessibility events — only touch events
    }

    /**
     * This is called for every raw touch event system-wide.
     * Requires FLAG_REQUEST_TOUCH_EXPLORATION_MODE in service config.
     */
    @Override
    protected boolean onGesture(int gestureId) {
        // We handle raw MotionEvents instead of high-level gestures
        return false;
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        // Check if pinch SOS is still enabled in prefs
        SharedPreferences prefs = getSharedPreferences("SaveSouls", MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_KEY, false)) return;

        int action = event.getActionMasked();
        int count  = event.getPointerCount();

        switch (action) {

            case MotionEvent.ACTION_POINTER_DOWN:
                // Second finger touched — start tracking if we now have 2 fingers
                if (count == 2) {
                    float span = getSpan(event);
                    if (span >= MIN_INITIAL_SPAN_PX) {
                        initialSpan  = span;
                        lastSpan     = span;
                        gestureStart = System.currentTimeMillis();
                        lastSpanTime = gestureStart;
                        maxVelocity  = 0f;
                        tracking     = true;
                        sosPending   = false;
                        cancelPendingSos();
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!tracking || count < 2) break;

                // Timeout check
                long now = System.currentTimeMillis();
                if (now - gestureStart > GESTURE_WINDOW_MS) {
                    resetGesture();
                    break;
                }

                float span = getSpan(event);
                float dt   = (now - lastSpanTime) / 1000f;
                if (dt > 0) {
                    float vel = (span - lastSpan) / dt;
                    if (vel > maxVelocity) maxVelocity = vel;
                }
                lastSpan     = span;
                lastSpanTime = now;

                // Check if spread threshold met
                if (!sosPending && span >= initialSpan * SPREAD_THRESHOLD_RATIO
                        && maxVelocity >= MIN_VELOCITY_PX_S) {
                    sosPending = true;
                    vibrate(120); // confirmation pulse
                    // Schedule SOS — user can lift fingers to abort
                    sosRunnable = this::fireSOSNow;
                    handler.postDelayed(sosRunnable, CONFIRM_DELAY_MS);
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (count <= 2) {
                    // All fingers lifted — if SOS not yet fired, cancel it
                    if (sosPending && !isSOSFired) {
                        cancelPendingSos();
                        sosPending = false;
                    }
                    resetGesture();
                }
                break;
        }

        // void method — event is not consumed; system also handles it
    }

    private volatile boolean isSOSFired = false;

    private void fireSOSNow() {
        if (isSOSFired) return;
        isSOSFired = true;
        Log.d(TAG, "Pinch-out SOS fired from accessibility service!");

        vibrate(600); // long SOS confirmation buzz

        // Trigger SOS service
        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(SafeHerService.ACTION_TRIGGER_SOS);
        startService(svc);

        // Launch SOS activity over lock screen
        Intent ui = new Intent(this, SosActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                  | Intent.FLAG_ACTIVITY_CLEAR_TOP
                  | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(ui);

        // Reset so service can detect again after a cooldown
        handler.postDelayed(() -> {
            isSOSFired = false;
            resetGesture();
        }, 10_000); // 10s cooldown before re-arming
    }

    private void cancelPendingSos() {
        if (sosRunnable != null) {
            handler.removeCallbacks(sosRunnable);
            sosRunnable = null;
        }
    }

    private void resetGesture() {
        tracking    = false;
        initialSpan = 0f;
        lastSpan    = 0f;
        maxVelocity = 0f;
        sosPending  = false;
        cancelPendingSos();
    }

    /** Euclidean distance between pointer 0 and pointer 1 */
    private float getSpan(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
