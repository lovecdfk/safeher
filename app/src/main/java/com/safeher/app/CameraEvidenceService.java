package com.safeher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * CameraEvidenceService
 * Opens the back camera silently in the background (no preview needed).
 * Takes a JPEG photo every 5 seconds and saves to SaveSouls_Evidence folder.
 * Uses Camera2 API with a dummy SurfaceTexture so no preview is shown.
 */
public class CameraEvidenceService extends Service {

    private static final String TAG           = "CameraEvidence";
    private static final long   CAPTURE_INTERVAL_MS = 5000; // photo every 5s
    private static final int    MAX_PHOTOS    = 60; // max 60 photos = 5 min

    /** Broadcast sent after each photo: includes EXTRA_PHOTO_COUNT */
    public static final String ACTION_PHOTO_TAKEN  = "com.safeher.app.PHOTO_TAKEN";
    public static final String EXTRA_PHOTO_COUNT   = "photo_count";

    public static boolean isRunning = false;

    private CameraManager       cameraManager;
    private CameraDevice        cameraDevice;
    private CaptureRequest.Builder captureBuilder;
    private CameraCaptureSession captureSession;
    private ImageReader         imageReader;
    private HandlerThread       cameraThread;
    private Handler             cameraHandler;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    private File   evidenceDir;
    private int    photoCount = 0;
    private boolean capturing = false;

    // ── LIFECYCLE ─────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;

        // Required: start as foreground service (camera type requires it on Android 9+)
        createNotificationChannel();
        Notification notification = new Notification.Builder(this, "evidence_channel")
                .setContentTitle("SaveSouls Evidence Recording")
                .setContentText("📷 Collecting evidence during SOS…")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(1003, notification);

        // Create evidence directory
        evidenceDir = new File(getExternalFilesDir(null), "SaveSouls_Evidence");
        if (!evidenceDir.exists()) evidenceDir.mkdirs();

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        // Start camera on a background thread
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        openCamera();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_NOT_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        capturing = false;
        isRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
        Log.d(TAG, "CameraEvidenceService stopped, saved " + photoCount + " photos");
    }

    // ── CAMERA OPEN ───────────────────────────────────────────

    private void openCamera() {
        try {
            // Find back camera
            String cameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                Log.e(TAG, "No back camera found");
                stopSelf();
                return;
            }

            // Pick a reasonable capture size (1280x720 or smaller for speed)
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = ch.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size captureSize = chooseCaptureSize(map);

            // ImageReader to receive JPEG frames
            imageReader = ImageReader.newInstance(
                captureSize.getWidth(), captureSize.getHeight(),
                ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::saveImage, cameraHandler);

            // Open camera (requires permission — already declared in manifest)
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); }
                @Override public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    stopSelf();
                }
            }, cameraHandler);

        } catch (SecurityException e) {
            Log.e(TAG, "Camera permission denied");
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Camera open failed: " + e.getMessage());
            stopSelf();
        }
    }

    // ── CAPTURE SESSION ───────────────────────────────────────

    private void createCaptureSession() {
        try {
            // Dummy SurfaceTexture — camera needs at least one output surface
            // but we don't want to show any preview to the user
            SurfaceTexture dummyTexture = new SurfaceTexture(10);
            dummyTexture.setDefaultBufferSize(320, 240);
            Surface dummySurface = new Surface(dummyTexture);

            cameraDevice.createCaptureSession(
                Arrays.asList(imageReader.getSurface(), dummySurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        captureSession = session;
                        capturing = true;
                        scheduleNextCapture(0); // start immediately
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Capture session configure failed");
                        stopSelf();
                    }
                }, cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "Session creation failed: " + e.getMessage());
            stopSelf();
        }
    }

    // ── PHOTO CAPTURE ─────────────────────────────────────────

    private void scheduleNextCapture(long delayMs) {
        if (!capturing || photoCount >= MAX_PHOTOS) {
            stopSelf();
            return;
        }
        mainHandler.postDelayed(this::capturePhoto, delayMs);
    }

    private void capturePhoto() {
        if (!capturing || captureSession == null || cameraDevice == null) return;
        try {
            captureBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 80);

            captureSession.capture(captureBuilder.build(),
                new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session,
                        CaptureRequest request, TotalCaptureResult result) {
                        photoCount++;
                        Log.d(TAG, "Photo #" + photoCount + " captured");

                        // Broadcast so SosActivity can show live count
                        Intent broadcast = new Intent(ACTION_PHOTO_TAKEN);
                        broadcast.putExtra(EXTRA_PHOTO_COUNT, photoCount);
                        sendBroadcast(broadcast);

                        // Update foreground notification with live count
                        Notification updated = new Notification.Builder(
                                CameraEvidenceService.this, "evidence_channel")
                                .setContentTitle("SaveSouls — Evidence Recording")
                                .setContentText("📷 " + photoCount + " photo(s) captured")
                                .setSmallIcon(android.R.drawable.ic_menu_camera)
                                .setOngoing(true)
                                .build();
                        getSystemService(NotificationManager.class).notify(1003, updated);

                        scheduleNextCapture(CAPTURE_INTERVAL_MS);
                    }
                }, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Capture failed: " + e.getMessage());
            scheduleNextCapture(CAPTURE_INTERVAL_MS);
        }
    }

    // ── SAVE JPEG ─────────────────────────────────────────────

    private void saveImage(ImageReader reader) {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(evidenceDir, "CAM_" + ts + "_" + photoCount + ".jpg");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
                Log.d(TAG, "Photo saved: " + file.getName());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed: " + e.getMessage());
        }
    }

    // ── HELPERS ───────────────────────────────────────────────

    private Size chooseCaptureSize(StreamConfigurationMap map) {
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        // Pick 1280x720 or the closest smaller size for speed
        Size best = sizes[sizes.length - 1]; // smallest
        for (Size s : sizes) {
            if (s.getWidth() <= 1280 && s.getHeight() <= 720) {
                if (s.getWidth() > best.getWidth()) best = s;
            }
        }
        return best;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                "evidence_channel", "Evidence Recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Camera evidence during SOS activation");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } }
        catch (Exception ignored) {}
        try { if (cameraDevice  != null) { cameraDevice.close();  cameraDevice  = null; } }
        catch (Exception ignored) {}
        try { if (imageReader   != null) { imageReader.close();   imageReader   = null; } }
        catch (Exception ignored) {}
    }
}
