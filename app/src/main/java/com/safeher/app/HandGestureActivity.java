package com.safeher.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * HandGestureActivity — Pinch-Out SOS (v3)
 *
 * Two modes:
 *
 * 1. IN-APP (always works):
 *    ScaleGestureDetector on the overlay — spread fingers 55%+ within 1.8s → SOS.
 *
 * 2. LOCK SCREEN (via PinchAccessibilityService):
 *    User enables accessibility permission → PinchAccessibilityService intercepts
 *    raw touch events system-wide, including on the lock screen. Same spread
 *    gesture → SOS fires and SosActivity launches over the lock screen.
 */
public class HandGestureActivity extends AppCompatActivity {

    private static final float SCALE_THRESHOLD   = 1.55f;
    private static final long  GESTURE_WINDOW_MS = 1800;
    private static final float MIN_SPAN_VELOCITY = 200f;
    private static final long  HOLD_MS           = 1500;

    // UI
    private PinchOverlayView pinchOverlay;
    private TextView         tvBack, tvStatus, tvCountdown, tvArcHint;
    private TextView         tvAccessibilityStatus, tvAccessibilityBtn;
    private View             cardAccessibility;
    private ProgressBar      progressBar;
    private View             layoutCountdown, layoutIdle;
    private Switch           switchEnable;

    // Gesture
    private ScaleGestureDetector scaleDetector;
    private volatile boolean enabled      = true;
    private volatile boolean isCounting   = false;
    private float            totalScale   = 1f;
    private long             gestureStart = 0;
    private boolean          speedMet     = false;
    private float            prevSpan     = 0f;
    private long             prevSpanTime = 0;
    private CountDownTimer   holdTimer;
    private final Handler    ui = new Handler(Looper.getMainLooper());
    private float f1x, f1y, f2x, f2y;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hand_gesture);

        pinchOverlay         = findViewById(R.id.pinchOverlayView);
        tvBack               = findViewById(R.id.tvGestureBack);
        tvStatus             = findViewById(R.id.tvGestureStatus);
        tvCountdown          = findViewById(R.id.tvGestureCountdown);
        tvArcHint            = findViewById(R.id.tvArcHint);
        progressBar          = findViewById(R.id.progressGestureRing);
        layoutCountdown      = findViewById(R.id.layoutGestureOverlay);
        layoutIdle           = findViewById(R.id.layoutGestureReady);
        switchEnable         = findViewById(R.id.switchGestureEnable);
        cardAccessibility    = findViewById(R.id.cardAccessibilitySetup);
        tvAccessibilityStatus= findViewById(R.id.tvAccessibilityStatus);
        tvAccessibilityBtn   = findViewById(R.id.tvAccessibilityBtn);

        tvBack.setOnClickListener(v -> finish());

        switchEnable.setChecked(true);
        switchEnable.setOnCheckedChangeListener((btn, on) -> {
            enabled = on;
            if (!on) cancelHold();
            tvStatus.setText(on ? "🤏 Place two fingers and spread apart" : "Detection paused");
        });

        // Accessibility card button → open system accessibility settings
        if (tvAccessibilityBtn != null) {
            tvAccessibilityBtn.setOnClickListener(v -> openAccessibilitySettings());
        }
        if (cardAccessibility != null) {
            cardAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        }

        setupScaleDetector();

        pinchOverlay.setOnTouchListener((v, event) -> {
            int count = event.getPointerCount();
            if (count >= 1) { f1x = event.getX(0); f1y = event.getY(0); }
            if (count >= 2) { f2x = event.getX(1); f2y = event.getY(1); }
            scaleDetector.onTouchEvent(event);
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_UP) && !isCounting) {
                pinchOverlay.clearTouch();
                tvArcHint.setVisibility(View.INVISIBLE);
            }
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessibilityCard();
    }

    // ── Accessibility service state ───────────────────────────────────────────

    private boolean isAccessibilityEnabled() {
        String serviceName = getPackageName() + "/" + PinchAccessibilityService.class.getName();
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled == 0) return false;
            String services = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;
            for (String s : services.split(":")) {
                if (s.equalsIgnoreCase(serviceName)) return true;
            }
        } catch (Exception e) { return false; }
        return false;
    }

    private void refreshAccessibilityCard() {
        if (cardAccessibility == null) return;
        boolean enabled = isAccessibilityEnabled();

        // Save to prefs so service can read it
        getSharedPreferences("SaveSouls", MODE_PRIVATE)
                .edit().putBoolean(PinchAccessibilityService.PREF_KEY, enabled).apply();

        if (tvAccessibilityStatus != null) {
            if (enabled) {
                tvAccessibilityStatus.setText("✅ Active — pinch works on locked screen");
                tvAccessibilityStatus.setTextColor(0xFF34D399);
            } else {
                tvAccessibilityStatus.setText("⚠️ Not enabled — tap to set up");
                tvAccessibilityStatus.setTextColor(0xFFF59E0B);
            }
        }
        if (tvAccessibilityBtn != null) {
            tvAccessibilityBtn.setText(enabled ? "Open Settings" : "Enable Now →");
        }
    }

    private void openAccessibilitySettings() {
        Toast.makeText(this,
                "Find 'SaveSouls' and turn it ON", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ── Scale gesture detector ────────────────────────────────────────────────

    private void setupScaleDetector() {
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {

            @Override
            public boolean onScaleBegin(ScaleGestureDetector det) {
                if (!enabled || isCounting) return false;
                totalScale   = 1f;
                speedMet     = false;
                gestureStart = System.currentTimeMillis();
                prevSpan     = det.getCurrentSpan();
                prevSpanTime = System.currentTimeMillis();
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector det) {
                if (!enabled || isCounting) return false;
                float scale = det.getScaleFactor();
                if (scale > 1f) totalScale *= scale;

                float span = det.getCurrentSpan();
                long  now  = System.currentTimeMillis();
                float dt   = (now - prevSpanTime) / 1000f;
                if (dt > 0 && (span - prevSpan) / dt > MIN_SPAN_VELOCITY) speedMet = true;
                prevSpan = span; prevSpanTime = now;

                float prog = Math.min(1f, (totalScale - 1f) / (SCALE_THRESHOLD - 1f));
                pinchOverlay.update(f1x, f1y, f2x, f2y, prog, false);
                tvArcHint.setText("Spread: " + (int)(prog * 100) + "%");
                tvArcHint.setVisibility(View.VISIBLE);

                if (now - gestureStart > GESTURE_WINDOW_MS) {
                    resetGesture("Too slow — try again faster"); return true;
                }
                if (totalScale >= SCALE_THRESHOLD && speedMet) {
                    pinchOverlay.update(f1x, f1y, f2x, f2y, 1f, true);
                    startCountdown();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector det) {
                if (!isCounting)
                    ui.postDelayed(() -> { if (!isCounting) resetGesture("🤏 Place two fingers and spread apart"); }, 500);
            }
        });
    }

    // ── Countdown & SOS ───────────────────────────────────────────────────────

    private void startCountdown() {
        if (isCounting) return;
        isCounting = true;
        layoutCountdown.setVisibility(View.VISIBLE);
        layoutIdle.setVisibility(View.GONE);
        tvStatus.setText("🤏 Pinch-out detected! SOS in 2 seconds…");
        tvArcHint.setVisibility(View.INVISIBLE);
        vibrate(100);
        holdTimer = new CountDownTimer(HOLD_MS, 40) {
            @Override public void onTick(long msLeft) {
                progressBar.setProgress((int)((HOLD_MS - msLeft) * 100f / HOLD_MS));
                tvCountdown.setText(String.valueOf(msLeft / 1000 + 1));
            }
            @Override public void onFinish() {
                progressBar.setProgress(100);
                isCounting = false;
                triggerSOS();
            }
        }.start();
    }

    private void resetGesture(String msg) {
        totalScale = 1f; speedMet = false;
        pinchOverlay.clearTouch();
        tvArcHint.setVisibility(View.INVISIBLE);
        tvStatus.setText(msg);
    }

    private void cancelHold() {
        if (holdTimer != null) holdTimer.cancel();
        isCounting = false;
        progressBar.setProgress(0);
        layoutCountdown.setVisibility(View.GONE);
        layoutIdle.setVisibility(View.VISIBLE);
        pinchOverlay.clearTouch();
        tvStatus.setText("🤏 Place two fingers and spread apart");
        tvArcHint.setVisibility(View.INVISIBLE);
    }

    private void triggerSOS() {
        vibrate(700);
        tvStatus.setText("🆘 SOS TRIGGERED!");
        Toast.makeText(this, "🆘 SOS via pinch-out gesture!", Toast.LENGTH_LONG).show();
        Intent svc = new Intent(this, SosService.class);
        svc.setAction(SosService.ACTION_TRIGGER);
        startService(svc);
        ui.postDelayed(() -> { startActivity(new Intent(this, SosActivity.class)); finish(); }, 1200);
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override protected void onPause()   { super.onPause();   cancelHold(); }
    @Override protected void onDestroy() { super.onDestroy(); if (holdTimer != null) holdTimer.cancel(); }
}
