package com.safeher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.Image;import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HandGestureService — background foreground service.
 * Detects a single upright index finger via front camera.
 * If held for 4 seconds → triggers SOS.
 */
public class HandGestureService extends Service implements LifecycleOwner {

    private LifecycleRegistry lifecycleRegistry;

    private static final String TAG        = "HandGestureService";
    public  static final String CHANNEL_ID = "hand_gesture_ch";
    public  static final int    NOTIF_ID   = 3001;

    public  static final String ACTION_STOP          = "com.safeher.app.GESTURE_STOP";
    public  static final String ACTION_GESTURE_STATE = "com.safeher.app.GESTURE_STATE";
    public  static final String EXTRA_DETECTED       = "detected";
    public  static final String EXTRA_PROGRESS       = "progress";
    public  static final String PREF_ENABLED         = "hand_gesture_enabled";

    public  static volatile boolean isRunning = false;

    // ── STATIC HELPERS ───────────────────────────────────────────────────────

    public static void start(Context ctx) {
        ctx.getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, true).apply();
        Intent i = new Intent(ctx, HandGestureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(i);
        else
            ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, false).apply();
        Intent i = new Intent(ctx, HandGestureService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ── Detection ────────────────────────────────────────────────────────────
    private static final long HOLD_MS       = 2000;
    private static final int  CONFIRM_STREAK = 3;

    // ── State ────────────────────────────────────────────────────────────────
    private ExecutorService       cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private final Handler         uiHandler = new Handler(Looper.getMainLooper());

    private final CircleGestureDetector circleDetector = new CircleGestureDetector();
    private volatile int     goodStreak       = 0;
    private volatile boolean gestureHeld      = false;
    private volatile long    gestureStartTime = 0;
    private volatile boolean sosTriggered     = false;

    // ── LIFECYCLE ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        // Must initialize on main thread
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        createChannel();
        isRunning = true;
        startForeground(NOTIF_ID, buildNotif(false, 0));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor();
            // Post to main thread so ProcessLifecycleOwner is ready
            uiHandler.post(this::startCamera);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        uiHandler.removeCallbacksAndMessages(null);
        if (cameraProvider  != null) {
            try { cameraProvider.unbindAll(); } catch (Exception ignored) {}
        }
        if (cameraExecutor  != null) cameraExecutor.shutdown();
        broadcast(false, 0);
        getSharedPreferences("SaveSouls", MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, false).apply();
    }

    // ── CAMERA SETUP ─────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> fut =
            ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                cameraProvider = fut.get();
                bindAnalysis();
            } catch (Exception e) {
                Log.e(TAG, "Camera init error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindAnalysis() {
        if (cameraProvider == null) return;
        try { cameraProvider.unbindAll(); } catch (Exception ignored) {}

        ImageAnalysis analysis = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();
        analysis.setAnalyzer(cameraExecutor, this::analyseFrame);

        try {
            // Use this service as LifecycleOwner — safe since we manage the lifecycle manually
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis
            );
            Log.d(TAG, "Camera bound successfully");
        } catch (Exception e) {
            Log.e(TAG, "Camera bind failed: " + e.getMessage());
        }
    }

    // ── FRAME ANALYSIS ───────────────────────────────────────────────────────

    // ── FRAME ANALYSIS ───────────────────────────────────────────────────────

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyseFrame(ImageProxy proxy) {
        if (sosTriggered) { proxy.close(); return; }
        Image img = proxy.getImage();
        if (img == null) { proxy.close(); return; }

        boolean circle = circleDetector.processFrame(img);
        proxy.close();

        if (circle) goodStreak = Math.min(goodStreak + 1, CONFIRM_STREAK + 2);
        else         goodStreak = Math.max(0, goodStreak - 1);

        final boolean confirmed = goodStreak >= CONFIRM_STREAK;
        uiHandler.post(() -> onDetection(confirmed));
    }

    private void onDetection(boolean detected) {
        if (sosTriggered) return;

        if (detected) {
            if (!gestureHeld) {
                gestureHeld      = true;
                gestureStartTime = System.currentTimeMillis();
                vibrate(70);
            }
            long  elapsed  = System.currentTimeMillis() - gestureStartTime;
            int   progress = (int) Math.min(100, elapsed * 100 / HOLD_MS);
            updateNotif(true, progress);
            broadcast(true, progress);
            if (elapsed >= HOLD_MS) {
                sosTriggered = true;
                vibrate(500);
                fireSOSNow();
            }
        } else {
            if (gestureHeld) {
                gestureHeld = false;
                updateNotif(false, 0);
                broadcast(false, 0);
            }
        }
    }

    // ── FIRE SOS ─────────────────────────────────────────────────────────────

    private void fireSOSNow() {
        Log.d(TAG, "Hand gesture SOS fired!");
        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(SafeHerService.ACTION_TRIGGER_SOS);
        startService(svc);

        Intent ui = new Intent(this, SosActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(ui);

        uiHandler.postDelayed(this::stopSelf, 2500);
    }

    // ── BROADCAST ────────────────────────────────────────────────────────────

    private void broadcast(boolean detected, int progress) {
        Intent i = new Intent(ACTION_GESTURE_STATE);
        i.putExtra(EXTRA_DETECTED, detected);
        i.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(i);
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ── NOTIFICATION ─────────────────────────────────────────────────────────

    private Notification buildNotif(boolean active, int progress) {
        Intent stopI = new Intent(this, HandGestureService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopI,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = active ? "🤏 Pinch detected — SOS activating…" : "🤏 Pinch-Out SOS Active";
        String text  = active
            ? "Hold steady… " + progress + "% — release to cancel"
            : "Open the app and spread two fingers apart → SOS";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(active ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW);

        if (active) {
            b.setProgress(100, progress, false);
            b.setColor(0xFFFF2D55);
        }
        return b.build();
    }

    private void updateNotif(boolean active, int progress) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif(active, progress));
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Pinch-Out SOS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Background pinch-out SOS gesture monitor");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
