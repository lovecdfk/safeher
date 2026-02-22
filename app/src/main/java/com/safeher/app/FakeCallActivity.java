package com.safeher.app;

import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

public class FakeCallActivity extends AppCompatActivity {

    private TextView tvCallerName, tvCallStatus, tvCallTimer, tvEmoji;
    private MaterialButton btnAccept, btnReject;
    private MediaPlayer ringtone;
    private CountDownTimer callTimer;
    private int callSeconds = 0;
    private boolean accepted = false;

    // Caller presets
    private final String[] NAMES  = {"Mom", "Sister", "Best Friend", "Doctor", "Boss"};
    private final String[] EMOJIS = {"👩",  "👩‍🦱",     "🤝",          "👨‍⚕️",    "👨‍💼"};
    private int selectedIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show on lock screen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        setContentView(R.layout.activity_fake_call);

        tvCallerName = findViewById(R.id.tvCallerName);
        tvCallStatus = findViewById(R.id.tvCallStatus);
        tvCallTimer  = findViewById(R.id.tvCallTimer);
        tvEmoji      = findViewById(R.id.tvCallerEmoji);
        btnAccept    = findViewById(R.id.btnAccept);
        btnReject    = findViewById(R.id.btnReject);

        // Get caller from Intent (set by setup screen) or default to Mom
        selectedIndex = getIntent().getIntExtra("caller_index", 0);
        if (selectedIndex < 0 || selectedIndex >= NAMES.length) selectedIndex = 0;

        tvCallerName.setText(NAMES[selectedIndex]);
        tvEmoji.setText(EMOJIS[selectedIndex]);
        tvCallStatus.setText("Incoming call...");
        tvCallTimer.setVisibility(View.GONE);

        btnAccept.setOnClickListener(v -> acceptCall());
        btnReject.setOnClickListener(v -> endCall());

        startRingtone();
        startVibration();
    }

    private void startRingtone() {
        try {
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = MediaPlayer.create(this, ringtoneUri);
            if (ringtone != null) {
                ringtone.setAudioStreamType(AudioManager.STREAM_RING);
                ringtone.setLooping(true);
                ringtone.start();
            }
        } catch (Exception e) {
            // Silently fallback — vibration still works
        }
    }

    private void startVibration() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            long[] pattern = {0, 700, 500, 700, 500};
            v.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }
    }

    private void stopRingtoneAndVibration() {
        try {
            if (ringtone != null && ringtone.isPlaying()) {
                ringtone.stop();
                ringtone.release();
                ringtone = null;
            }
        } catch (Exception ignored) {}

        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.cancel();
    }

    private void acceptCall() {
        accepted = true;
        stopRingtoneAndVibration();
        tvCallStatus.setText("Connected");
        tvCallTimer.setVisibility(View.VISIBLE);
        btnAccept.setVisibility(View.GONE);

        callTimer = new CountDownTimer(Long.MAX_VALUE, 1000) {
            @Override
            public void onTick(long millisLeft) {
                callSeconds++;
                int m = callSeconds / 60;
                int s = callSeconds % 60;
                tvCallTimer.setText(String.format("%02d:%02d", m, s));
            }
            @Override
            public void onFinish() {}
        }.start();
    }

    private void endCall() {
        stopRingtoneAndVibration();
        if (callTimer != null) callTimer.cancel();
        finish();
    }

    // Disable back button during call (realistic behaviour)
    @Override
    public void onBackPressed() {
        // Intentionally blocked
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRingtoneAndVibration();
        if (callTimer != null) callTimer.cancel();
    }
}
