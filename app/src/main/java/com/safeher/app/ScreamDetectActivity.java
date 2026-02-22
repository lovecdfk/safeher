package com.safeher.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;

/**
 * ScreamDetectActivity
 * ─────────────────────
 * Lets the user toggle scream detection ON/OFF and watch a live
 * amplitude meter so they can see the mic is actually working.
 *
 * The actual detection + SOS triggering lives in SafeHerService.
 * This activity just sends intents to start/stop it and reads the
 * same SharedPreference to show current state.
 */
public class ScreamDetectActivity extends AppCompatActivity {

    // ── Live amplitude meter (UI only — does NOT trigger SOS) ──
    private static final int SAMPLE_RATE    = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord meterRecord;
    private Thread      meterThread;
    private volatile boolean meterRunning = false;

    // ── Views ──────────────────────────────────────────────────
    private TextView         tvStatus;
    private TextView         tvAmplitude;
    private TextView         tvDescription;
    private MaterialCardView btnToggle;
    private TextView         tvBtnLabel;
    private TextView         tvBtnSub;

    // Amplitude bar segments (10 TextViews used as bar segments)
    private TextView[] barSegments;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ──────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scream_detect);

        // Back button
        ImageButton btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        tvStatus      = findViewById(R.id.tvScreamStatus);
        tvAmplitude   = findViewById(R.id.tvAmplitude);
        tvDescription = findViewById(R.id.tvScreamDescription);
        btnToggle     = findViewById(R.id.cardScreamToggle);
        tvBtnLabel    = findViewById(R.id.tvToggleLabel);
        tvBtnSub      = findViewById(R.id.tvToggleSub);

        // Collect bar segment views
        barSegments = new TextView[]{
            findViewById(R.id.bar1),  findViewById(R.id.bar2),
            findViewById(R.id.bar3),  findViewById(R.id.bar4),
            findViewById(R.id.bar5),  findViewById(R.id.bar6),
            findViewById(R.id.bar7),  findViewById(R.id.bar8),
            findViewById(R.id.bar9),  findViewById(R.id.bar10)
        };

        btnToggle.setOnClickListener(v -> toggleDetection());

        updateUI(isEnabled());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always start the visual meter when screen is visible
        startMeter();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMeter();
    }

    // ── Toggle detection ───────────────────────────────────────

    private void toggleDetection() {
        boolean nowEnabled = !isEnabled();

        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(nowEnabled
            ? SafeHerService.ACTION_START_SCREAM_DETECT
            : SafeHerService.ACTION_STOP_SCREAM_DETECT);
        startService(svc);

        updateUI(nowEnabled);
    }

    private boolean isEnabled() {
        SharedPreferences prefs = getSharedPreferences("SaveSouls", MODE_PRIVATE);
        return prefs.getBoolean(SafeHerService.PREF_SCREAM_ENABLED, false);
    }

    private void updateUI(boolean enabled) {
        if (enabled) {
            tvStatus.setText("🎙️ SCREAM DETECTION  ON");
            tvStatus.setTextColor(getColor(R.color.green));
            tvBtnLabel.setText("TAP TO DISABLE");
            tvBtnSub.setText("Scream detection is active");
            tvDescription.setText(
                "SaveSouls is listening for screams in the background.\n\n" +
                "If a scream is detected for 300ms continuously at 2.8× your " +
                "background noise level, SOS fires automatically — alarm, " +
                "recording, and SMS to all your emergency contacts.\n\n" +
                "The live meter below shows your current microphone level.");
        } else {
            tvStatus.setText("⭕ SCREAM DETECTION  OFF");
            tvStatus.setTextColor(getColor(R.color.muted));
            tvBtnLabel.setText("TAP TO ENABLE");
            tvBtnSub.setText("Scream detection is disabled");
            tvDescription.setText(
                "When enabled, SafeHer monitors your microphone 24/7 in the " +
                "background — even when the screen is off.\n\n" +
                "A sudden loud scream will automatically trigger SOS: " +
                "alarm + recording + emergency SMS.\n\n" +
                "The live meter shows what the mic currently hears.");
        }
    }

    // ── Live amplitude meter (visual only) ────────────────────

    /**
     * Runs a separate AudioRecord just for the UI meter.
     * This is independent from the detection in SafeHerService.
     * When the service is detecting, Android allows two concurrent
     * AudioRecord instances (meter + detector share mic via audio policy).
     */
    private void startMeter() {
        if (meterRunning) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        try {
            meterRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                Math.max(bufferSize, 4096));
        } catch (SecurityException e) {
            return; // permission not granted yet
        }

        if (meterRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            meterRecord.release();
            meterRecord = null;
            return;
        }

        meterRunning = true;
        meterRecord.startRecording();

        final short[] buf = new short[bufferSize / 2];

        meterThread = new Thread(() -> {
            while (meterRunning) {
                int read = meterRecord.read(buf, 0, buf.length);
                if (read <= 0) continue;

                double sum = 0;
                for (int i = 0; i < read; i++) sum += (double) buf[i] * buf[i];
                final double rms = Math.sqrt(sum / read);

                uiHandler.post(() -> updateMeterUI(rms));

                try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
            }
            try {
                meterRecord.stop();
                meterRecord.release();
            } catch (Exception ignored) {}
            meterRecord = null;
        }, "MeterThread");

        meterThread.setDaemon(true);
        meterThread.start();
    }

    private void stopMeter() {
        meterRunning = false;
        if (meterThread != null) {
            meterThread.interrupt();
            meterThread = null;
        }
    }

    /**
     * Updates the 10-segment bar chart and the numeric readout.
     * rms range: 0 – 32767 (16-bit PCM max)
     */
    private void updateMeterUI(double rms) {
        if (tvAmplitude == null || barSegments == null) return;

        tvAmplitude.setText(String.format(Locale.US, "Amplitude: %d / 32767", (int) rms));

        // Map rms 0–32767 to 0–10 filled segments
        int filledSegments = (int) Math.min(10, (rms / 32767.0) * 10);

        // Colour zones: green (0-6), orange (7-8), red (9-10)
        for (int i = 0; i < barSegments.length; i++) {
            if (barSegments[i] == null) continue;
            if (i < filledSegments) {
                int color;
                if (i < 6)      color = 0xFF34D399; // green
                else if (i < 8) color = 0xFFFFBB00; // amber
                else            color = 0xFFFF2D55; // red (scream zone)
                barSegments[i].setBackgroundColor(color);
                barSegments[i].setAlpha(1.0f);
            } else {
                barSegments[i].setBackgroundColor(0xFF1E1E2E);
                barSegments[i].setAlpha(0.4f);
            }
        }

        // Flash the status label red when in scream zone
        if (filledSegments >= 9) {
            tvStatus.setTextColor(0xFFFF2D55);
        } else if (isEnabled()) {
            tvStatus.setTextColor(getColor(R.color.green));
        }
    }

    private static final java.util.Locale Locale = java.util.Locale.US;
}
