package com.safeher.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.telephony.SmsManager;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import org.json.*;

import java.util.*;

/**
 * Safe Walk Mode — user sets a timer (e.g. 15 min).
 * If they don't check in before it expires, SOS fires automatically.
 * Also shares live location every 2 min with contacts while walking.
 */
public class SafeWalkActivity extends AppCompatActivity {

    private TextView tvBack, tvCountdown, tvStatus, tvLocation;
    private MaterialButton btnStart, btnCheckin, btnStop;
    private NumberPicker npMinutes;

    private CountDownTimer walkTimer;
    private boolean walkActive = false;

    private LocationManager locationManager;
    private double lat = 0, lng = 0;
    private boolean hasLocation = false;

    private final Handler locationShareHandler = new Handler(Looper.getMainLooper());
    private static final long SHARE_INTERVAL_MS = 2 * 60 * 1000; // every 2 min

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safe_walk);

        tvBack      = findViewById(R.id.tvBack);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvStatus    = findViewById(R.id.tvStatus);
        tvLocation  = findViewById(R.id.tvWalkLocation);
        btnStart    = findViewById(R.id.btnStartWalk);
        btnCheckin  = findViewById(R.id.btnCheckin);
        btnStop     = findViewById(R.id.btnStopWalk);
        npMinutes   = findViewById(R.id.npMinutes);

        tvBack.setOnClickListener(v -> finish());

        // Minute picker: 5 to 60 minutes
        // Minute picker: 5 to 60 minutes
        npMinutes.setMinValue(5);
        npMinutes.setMaxValue(60);
        npMinutes.setValue(15);
        npMinutes.setWrapSelectorWheel(false);

        btnStart.setOnClickListener(v -> startWalk());
        btnCheckin.setOnClickListener(v -> checkIn());
        btnStop.setOnClickListener(v -> stopWalk());

        btnCheckin.setEnabled(false);
        btnStop.setEnabled(false);

        initLocation();
    }

    private void initLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            LocationListener listener = loc -> {
                lat = loc.getLatitude();
                lng = loc.getLongitude();
                hasLocation = true;
                tvLocation.setText(String.format("📍 %.5f, %.5f", lat, lng));
            };
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 5000, 1, listener);
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000, 1, listener);

            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                lat = last.getLatitude(); lng = last.getLongitude();
                hasLocation = true;
                tvLocation.setText(String.format("📍 %.5f, %.5f", lat, lng));
            }
        } catch (SecurityException e) {
            tvLocation.setText("Location permission needed");
        }
    }

    private void startWalk() {
        int minutes = npMinutes.getValue();
        long durationMs = minutes * 60 * 1000L;

        walkActive = true;
        btnStart.setEnabled(false);
        btnCheckin.setEnabled(true);
        btnStop.setEnabled(true);
        npMinutes.setEnabled(false);

        tvStatus.setText("🟢 Safe Walk Active — Stay safe!");
        tvStatus.setTextColor(getColor(R.color.green));

        // Send start SMS to contacts
        sendSms("🚶 Safe Walk STARTED. I'll be walking for " + minutes +
            " minutes. If you don't hear from me, please check in. " +
            (hasLocation ? "My location: https://maps.google.com/?q=" + lat + "," + lng : ""));

        // Share location every 2 min
        locationShareHandler.post(locationShareLoop);

        // Countdown timer — auto-SOS if no check-in
        walkTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long ms) {
                long m = ms / 60000;
                long s = (ms % 60000) / 1000;
                tvCountdown.setText(String.format(Locale.US, "%02d:%02d remaining", m, s));

                // Warn at 1 minute left
                if (ms < 61000) {
                    tvCountdown.setTextColor(getColor(R.color.red));
                    tvStatus.setText("⚠️ 1 minute left! Check in or SOS fires!");
                }
            }

            @Override
            public void onFinish() {
                tvCountdown.setText("00:00");
                if (walkActive) {
                    tvStatus.setText("🆘 No check-in — SOS TRIGGERED!");
                    tvStatus.setTextColor(getColor(R.color.red));
                    autoTriggerSOS();
                }
            }
        }.start();
    }

    private void checkIn() {
        if (!walkActive) return;

        // Reset timer with same duration
        if (walkTimer != null) walkTimer.cancel();
        int minutes = npMinutes.getValue();
        long durationMs = minutes * 60 * 1000L;

        tvStatus.setText("✅ Checked in! Timer reset.");
        tvStatus.setTextColor(getColor(R.color.green));
        tvCountdown.setTextColor(getColor(R.color.white));

        sendSms("✅ Safe Walk CHECK-IN: I'm safe! " +
            (hasLocation ? "Location: https://maps.google.com/?q=" + lat + "," + lng : ""));

        walkTimer = new CountDownTimer(durationMs, 1000) {
            @Override public void onTick(long ms) {
                long m = ms / 60000;
                long s = (ms % 60000) / 1000;
                tvCountdown.setText(String.format(Locale.US, "%02d:%02d remaining", m, s));
                if (ms < 61000) {
                    tvCountdown.setTextColor(getColor(R.color.red));
                    tvStatus.setText("⚠️ 1 minute left! Check in!");
                }
            }
            @Override public void onFinish() {
                if (walkActive) autoTriggerSOS();
            }
        }.start();
    }

    private void stopWalk() {
        walkActive = false;
        if (walkTimer != null) walkTimer.cancel();
        locationShareHandler.removeCallbacksAndMessages(null);

        btnStart.setEnabled(true);
        btnCheckin.setEnabled(false);
        btnStop.setEnabled(false);
        npMinutes.setEnabled(true);
        tvCountdown.setText("--:--");
        tvCountdown.setTextColor(getColor(R.color.white));
        tvStatus.setText("Safe Walk ended. Stay safe! 💙");
        tvStatus.setTextColor(getColor(R.color.muted));

        sendSms("🏠 Safe Walk ENDED — I have arrived safely! " +
            (hasLocation ? "Final location: https://maps.google.com/?q=" + lat + "," + lng : ""));
    }

    private void autoTriggerSOS() {
        // Trigger SOS via service
        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(SafeHerService.ACTION_TRIGGER_SOS);
        startService(svc);

        sendSms("🆘 SOS AUTO-TRIGGERED! Safe Walk timer expired with no check-in. " +
            (hasLocation ? "Last location: https://maps.google.com/?q=" + lat + "," + lng : ""));

        stopWalk();
    }

    private final Runnable locationShareLoop = new Runnable() {
        @Override public void run() {
            if (!walkActive) return;
            if (hasLocation) {
                sendSms("📍 Safe Walk location update: " +
                    "https://maps.google.com/?q=" + lat + "," + lng);
            }
            locationShareHandler.postDelayed(this, SHARE_INTERVAL_MS);
        }
    };

    private void sendSms(String msg) {
        new Thread(() -> {
            try {
                String json = getSharedPreferences("SaveSouls", MODE_PRIVATE)
                    .getString("contacts", "[]");
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String phone = arr.getJSONObject(i).getString("phone")
                        .replaceAll("[\\s\\-]", "");
                    try {
                        SmsManager sms = SmsManager.getDefault();
                        ArrayList<String> parts = sms.divideMessage(msg);
                        sms.sendMultipartTextMessage(phone, null, parts, null, null);
                    } catch (Exception ignored) {}
                }
            } catch (JSONException e) { e.printStackTrace(); }
        }).start();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (walkActive) stopWalk();
        locationShareHandler.removeCallbacksAndMessages(null);
    }
}
