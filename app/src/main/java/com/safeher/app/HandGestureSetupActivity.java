package com.safeher.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Animatable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class HandGestureSetupActivity extends AppCompatActivity {

    private static final int PERM_CODE = 301;

    private TextView   tvBack, tvStatus, tvState, tvHint;
    private Switch     switchGesture;
    private ImageView  ivFinger;
    private ProgressBar progressHold;
    private View       layoutActive, layoutDetecting;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Animation pulseAnim;

    private final BroadcastReceiver gestureReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            boolean detected = intent.getBooleanExtra(HandGestureService.EXTRA_DETECTED, false);
            int progress     = intent.getIntExtra(HandGestureService.EXTRA_PROGRESS, 0);
            updateDetectionUI(detected, progress);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hand_gesture_setup);

        tvBack         = findViewById(R.id.tvGestureBack);
        tvStatus       = findViewById(R.id.tvGestureStatus);
        tvState        = findViewById(R.id.tvGestureState);
        tvHint         = findViewById(R.id.tvGestureHint);
        switchGesture  = findViewById(R.id.switchGesture);
        ivFinger       = findViewById(R.id.ivFingerVector);
        progressHold   = findViewById(R.id.progressHold);
        layoutActive   = findViewById(R.id.layoutActive);
        layoutDetecting = findViewById(R.id.layoutDetecting);

        tvBack.setOnClickListener(v -> finish());

        // Load pulse animation (uses existing pulse.xml)
        pulseAnim = AnimationUtils.loadAnimation(this, R.anim.pulse);

        // Restore saved toggle state
        boolean enabled = getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .getBoolean(HandGestureService.PREF_ENABLED, false);
        switchGesture.setChecked(enabled);
        updateToggleUI(enabled);

        switchGesture.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                requestCameraAndStart();
            } else {
                stopGestureService();
            }
        });

        // Start finger pulse animation
        ivFinger.startAnimation(pulseAnim);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(gestureReceiver,
            new IntentFilter(HandGestureService.ACTION_GESTURE_STATE),
            Context.RECEIVER_NOT_EXPORTED);

        // Sync switch with actual service state
        switchGesture.setChecked(HandGestureService.isRunning);
        updateToggleUI(HandGestureService.isRunning);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(gestureReceiver); } catch (Exception ignored) {}
    }

    // ── TOGGLE LOGIC ──────────────────────────────────────────────────────────

    private void requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startGestureService();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, PERM_CODE);
        }
    }

    private void startGestureService() {
        getSharedPreferences("SaveSouls", MODE_PRIVATE).edit()
            .putBoolean(HandGestureService.PREF_ENABLED, true).apply();

        Intent svc = new Intent(this, HandGestureService.class);
        startForegroundService(svc);

        updateToggleUI(true);
        Toast.makeText(this, "☝ Gesture SOS enabled — running in background",
            Toast.LENGTH_LONG).show();
    }

    private void stopGestureService() {
        getSharedPreferences("SaveSouls", MODE_PRIVATE).edit()
            .putBoolean(HandGestureService.PREF_ENABLED, false).apply();

        Intent svc = new Intent(this, HandGestureService.class);
        svc.setAction(HandGestureService.ACTION_STOP);
        startService(svc);

        updateToggleUI(false);
        updateDetectionUI(false, 0);
        Toast.makeText(this, "Gesture SOS disabled", Toast.LENGTH_SHORT).show();
    }

    // ── UI UPDATE ─────────────────────────────────────────────────────────────

    private void updateToggleUI(boolean enabled) {
        if (enabled) {
            tvStatus.setText("✅ Active — watching for your finger");
            layoutActive.setVisibility(View.VISIBLE);
            // Keep finger image animated
            ivFinger.startAnimation(pulseAnim);
        } else {
            tvStatus.setText("Disabled — toggle ON to activate");
            layoutActive.setVisibility(View.GONE);
            ivFinger.clearAnimation();
        }
    }

    private void updateDetectionUI(boolean detected, int progress) {
        if (detected) {
            layoutDetecting.setVisibility(View.VISIBLE);
            progressHold.setProgress(progress);
            long secsLeft = (HandGestureService.NOTIF_ID > 0)
                ? ((4000 - (progress * 4000L / 100)) / 1000 + 1) : 4;
            tvState.setText("☝ Finger detected! SOS in " + secsLeft + "s…");
            tvState.setTextColor(0xFFFF2D55);

            // Make finger icon pulse faster when detecting
            ivFinger.clearAnimation();
            Animation fast = AnimationUtils.loadAnimation(this, R.anim.pulse);
            ivFinger.startAnimation(fast);
        } else {
            layoutDetecting.setVisibility(View.GONE);
            progressHold.setProgress(0);
            if (HandGestureService.isRunning) {
                tvState.setText("Watching… show ☝ finger to front camera");
                tvState.setTextColor(0xFF9DB4BF);
            }
            // Back to slow pulse
            ivFinger.clearAnimation();
            ivFinger.startAnimation(pulseAnim);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_CODE && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startGestureService();
        } else {
            switchGesture.setChecked(false);
            Toast.makeText(this, "Camera permission needed for gesture detection",
                Toast.LENGTH_LONG).show();
        }
    }
}
