package com.safeher.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.*;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import org.json.*;

import java.util.*;

public class SosActivity extends AppCompatActivity implements SensorEventListener {

    private TextView tvLocation, tvShakeStatus, tvCountdownLabel, tvAlarmStatus;
    private TextView tvCameraStatus, tvCameraLed;
    private MaterialButton btnSos, btnCancel, btnStopAlarm;

    // Evidence gallery
    private RecyclerView        rvPhotos;
    private TextView            tvNoPhotos, tvPhotoCount, tvDeleteAll;
    private EvidencePhotoAdapter photoAdapter;
    private java.util.List<java.io.File> photoList = new java.util.ArrayList<>();
    private ActivityResultLauncher<Intent> viewerLauncher;
    private ProgressBar progressBar;
    private View layoutCountdown, layoutAlarmActive;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private Sensor accelerometer;

    private double lat = 0, lng = 0;
    private boolean hasLocation = false;

    private float lastX, lastY, lastZ;
    private long lastShakeTime = 0;
    private int shakeCount = 0;
    private boolean shakeEnabled = true;

    private CountDownTimer countDownTimer;
    private boolean isCounting = false;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private boolean ledOn = true; // for blinking dot

    private final BroadcastReceiver cameraReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int count = intent.getIntExtra(CameraEvidenceService.EXTRA_PHOTO_COUNT, 0);
            if (tvCameraStatus != null) {
                tvCameraStatus.setText("📷 " + count + " photo(s) captured");
            }
            // Refresh gallery to show the new photo
            refreshGallery();
        }
    };

    private void refreshGallery() {
        java.io.File dir = new java.io.File(getExternalFilesDir(null), "SaveSouls_Evidence");
        photoList.clear();
        if (dir.exists()) {
            java.io.File[] files = dir.listFiles(
                f -> f.getName().startsWith("CAM_") && f.getName().endsWith(".jpg"));
            if (files != null) {
                java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (java.io.File f : files) photoList.add(f);
            }
        }
        photoAdapter.notifyDataSetChanged();
        int n = photoList.size();
        tvPhotoCount.setText(n + " photo" + (n == 1 ? "" : "s"));
        tvDeleteAll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        tvNoPhotos.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
        rvPhotos.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Wake screen and show over lock screen when triggered by gesture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }
        // Dismiss keyguard so SOS is visible without swiping
        android.app.KeyguardManager km =
            (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, null);
        }

        setContentView(R.layout.activity_sos);

        findViewById(R.id.tvBack).setOnClickListener(v -> finish());

        tvLocation       = findViewById(R.id.tvLocation);
        tvShakeStatus    = findViewById(R.id.tvShakeStatus);
        tvCountdownLabel = findViewById(R.id.tvCountdownLabel);
        tvAlarmStatus    = findViewById(R.id.tvAlarmStatus);
        tvCameraStatus   = findViewById(R.id.tvCameraStatus);
        tvCameraLed      = findViewById(R.id.tvCameraLed);
        btnSos           = findViewById(R.id.btnSos);

        // Evidence gallery
        rvPhotos     = findViewById(R.id.rvPhotos);
        tvNoPhotos   = findViewById(R.id.tvNoPhotos);
        tvPhotoCount = findViewById(R.id.tvPhotoCount);
        tvDeleteAll  = findViewById(R.id.tvDeleteAll);

        photoAdapter = new EvidencePhotoAdapter(photoList, file -> {
            Intent i = new Intent(this, PhotoViewerActivity.class);
            i.putExtra(PhotoViewerActivity.EXTRA_PHOTO_PATH, file.getAbsolutePath());
            viewerLauncher.launch(i);
        });
        rvPhotos.setLayoutManager(new GridLayoutManager(this, 3));
        rvPhotos.setAdapter(photoAdapter);

        tvDeleteAll.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Delete all evidence?")
                .setMessage("This will permanently remove all " + photoList.size() + " photos.")
                .setPositiveButton("Delete all", (d, w) -> {
                    for (java.io.File f : photoList) f.delete();
                    refreshGallery();
                })
                .setNegativeButton("Cancel", null)
                .show()
        );

        // Return from photo viewer → refresh gallery (photo may have been deleted)
        viewerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> { if (result.getResultCode() == Activity.RESULT_OK) refreshGallery(); }
        );

        refreshGallery();
        btnCancel        = findViewById(R.id.btnCancel);
        btnStopAlarm     = findViewById(R.id.btnStopAlarm);
        progressBar      = findViewById(R.id.progressCountdown);
        layoutCountdown  = findViewById(R.id.layoutCountdown);
        layoutAlarmActive = findViewById(R.id.layoutAlarmActive);

        Switch switchShake = findViewById(R.id.switchShake);

        // Pinch-out gesture card
        findViewById(R.id.cardGestureSos).setOnClickListener(v ->
            startActivity(new Intent(this, HandGestureActivity.class)));

        // Animate the gesture icon if present
        View ivGestureIcon = findViewById(R.id.ivGestureIcon);
        if (ivGestureIcon != null) {
            pulseView(ivGestureIcon);
        }

        switchShake.setOnCheckedChangeListener((btn, checked) -> {            shakeEnabled = checked;
            shakeCount = 0;
            tvShakeStatus.setText(checked
                ? "Active — shake 3× rapidly to trigger SOS"
                : "Shake detection is OFF");
        });

        btnSos.setOnClickListener(v -> startCountdown());
        btnCancel.setOnClickListener(v -> cancelSOS());

        // Stop alarm button
        btnStopAlarm.setOnClickListener(v -> {
            Intent svc = new Intent(this, SosService.class);
            svc.setAction(SosService.ACTION_STOP_ALARM);
            startService(svc);
            refreshAlarmUI();
        });

        initLocation();
        initShake();

        // Refresh alarm status every second
        refreshHandler.post(refreshLoop);
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refreshAlarmUI();
            refreshHandler.postDelayed(this, 1000);
        }
    };

    private void refreshAlarmUI() {
        if (SosService.isAlarmActive) {
            layoutAlarmActive.setVisibility(View.VISIBLE);
            btnSos.setEnabled(false);
            tvAlarmStatus.setText("🔴 ALARM ACTIVE — Recording evidence...");
            // Blink the red dot to show camera is live
            if (tvCameraLed != null) {
                ledOn = !ledOn;
                tvCameraLed.setAlpha(ledOn ? 1.0f : 0.2f);
            }
        } else {
            layoutAlarmActive.setVisibility(View.GONE);
            if (!isCounting) btnSos.setEnabled(true);
            tvAlarmStatus.setText("");
            // Reset camera indicator
            if (tvCameraStatus != null) tvCameraStatus.setText("📷 Camera: waiting...");
            if (tvCameraLed != null)    tvCameraLed.setAlpha(1.0f);
        }
    }

    // ── LOCATION ──────────────────────────────────────────────
    private void initLocation() {
        tvLocation.setText("Getting your location...");
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            tvLocation.setText("Location permission not granted");
            return;
        }
        LocationListener listener = loc -> {
            lat = loc.getLatitude();
            lng = loc.getLongitude();
            hasLocation = true;
            tvLocation.setText(String.format("📍 %.5f, %.5f", lat, lng));
        };
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 3000, 1, listener);
        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER, 3000, 1, listener);
        Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (last == null)
            last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (last != null) {
            lat = last.getLatitude();
            lng = last.getLongitude();
            hasLocation = true;
            tvLocation.setText(String.format("📍 %.5f, %.5f", lat, lng));
        }
    }

    // ── SHAKE ─────────────────────────────────────────────────
    private void initShake() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer,
                SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!shakeEnabled || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        float x = event.values[0], y = event.values[1], z = event.values[2];
        float delta = Math.abs(x-lastX)+Math.abs(y-lastY)+Math.abs(z-lastZ);
        long now = System.currentTimeMillis();
        if (delta > 18 && now - lastShakeTime > 350) {
            lastShakeTime = now;
            shakeCount++;
            runOnUiThread(() ->
                tvShakeStatus.setText("Shake (" + shakeCount + "/3)..."));
            if (shakeCount >= 3) {
                shakeCount = 0;
                runOnUiThread(() -> { if (!isCounting) startCountdown(); });
            }
            new Handler(Looper.getMainLooper())
                .postDelayed(() -> shakeCount = 0, 2500);
        }
        lastX = x; lastY = y; lastZ = z;
    }
    @Override public void onAccuracyChanged(Sensor s, int a) {}

    // ── COUNTDOWN ─────────────────────────────────────────────
    private void startCountdown() {
        if (isCounting || SosService.isAlarmActive) return;
        List<JSONObject> contacts = loadContacts();
        if (contacts.isEmpty()) {
            Toast.makeText(this, "⚠️ Add contacts first!", Toast.LENGTH_LONG).show();
            return;
        }
        isCounting = true;
        btnSos.setEnabled(false);
        layoutCountdown.setVisibility(View.VISIBLE);
        vibrate(150);

        countDownTimer = new CountDownTimer(5000, 50) {
            public void onTick(long ms) {
                progressBar.setProgress((int)(ms / 5000f * 100));
                tvCountdownLabel.setText("SOS in " + (ms/1000+1) + "s...");
            }
            public void onFinish() {
                layoutCountdown.setVisibility(View.GONE);
                // Trigger via service (handles alarm + recording)
                Intent svc = new Intent(SosActivity.this, SosService.class);
                svc.setAction(SosService.ACTION_TRIGGER);
                startService(svc);

                // Also send WhatsApp
                sendWhatsApp();
                isCounting = false;
            }
        }.start();
    }

    private void cancelSOS() {
        if (countDownTimer != null) countDownTimer.cancel();
        isCounting = false;
        btnSos.setEnabled(true);
        layoutCountdown.setVisibility(View.GONE);
        Toast.makeText(this, "SOS cancelled", Toast.LENGTH_SHORT).show();
    }

    private void sendWhatsApp() {
        String mapsUrl = hasLocation
            ? "https://maps.google.com/?q=" + lat + "," + lng
            : "Location unavailable";
        String waText = Uri.encode(
            "🆘 *SOS EMERGENCY!*\nI need immediate help!\n📍 " + mapsUrl +
            "\n_Sent via SaveSouls_");
        List<JSONObject> contacts = loadContacts();
        for (int i = 0; i < contacts.size(); i++) {
            try {
                String phone = contacts.get(i).getString("phone")
                    .replaceAll("[\\s\\-]", "");
                final String wa = waText;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Intent w = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://wa.me/"+phone+"?text="+wa));
                        w.setPackage("com.whatsapp");
                        startActivity(w);
                    } catch (Exception ignored) {}
                }, i * 1500L);
            } catch (JSONException ignored) {}
        }
    }

    private List<JSONObject> loadContacts() {
        List<JSONObject> list = new ArrayList<>();
        try {
            String json = getSharedPreferences("SaveSouls", MODE_PRIVATE)
                .getString("contacts", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        } catch (JSONException e) { e.printStackTrace(); }
        return list;
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms,
                VibrationEffect.DEFAULT_AMPLITUDE));
    }

    /** Infinite gentle pulse animation using ViewPropertyAnimator — crash-safe */
    private void pulseView(android.view.View v) {
        v.animate()
            .scaleX(1.10f).scaleY(1.10f).alpha(0.6f)
            .setDuration(900)
            .withEndAction(() -> v.animate()
                .scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                .setDuration(900)
                .withEndAction(() -> pulseView(v))
                .start())
            .start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (accelerometer != null)
            sensorManager.registerListener(this, accelerometer,
                SensorManager.SENSOR_DELAY_UI);
        // Listen for camera evidence updates
        registerReceiver(cameraReceiver,
                new IntentFilter(CameraEvidenceService.ACTION_PHOTO_TAKEN),
                Context.RECEIVER_NOT_EXPORTED);
        refreshGallery();

        // Refresh gesture badge
        android.widget.TextView tvGestureBadge = findViewById(R.id.tvGestureBadge);
        if (tvGestureBadge != null) {
            boolean gestureOn = HandGestureService.isRunning;
            tvGestureBadge.setText(gestureOn ? "ON" : "OFF");
            tvGestureBadge.setTextColor(gestureOn ? 0xFF34D399 : 0xFF6b6b8a);
        }
    }
    @Override protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        try { unregisterReceiver(cameraReceiver); } catch (Exception ignored) {}
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacksAndMessages(null);
    }
}
