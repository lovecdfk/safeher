package com.safeher.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.*;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class RecorderActivity extends AppCompatActivity {

    private TextView tvTimer, tvStatus, tvFilePath, tvBack;
    private MaterialButton btnRecord, btnStop, btnPlay;
    private View dotRecording;

    private MediaRecorder recorder;
    private String currentFilePath;
    private boolean isRecording = false;

    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private int secondsElapsed = 0;

    private final Runnable timerRunnable = new Runnable() {
        @Override public void run() {
            secondsElapsed++;
            int m = secondsElapsed / 60;
            int s = secondsElapsed % 60;
            tvTimer.setText(String.format(Locale.US, "%02d:%02d", m, s));
            timerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recorder);

        tvBack      = findViewById(R.id.tvBack);
        tvTimer     = findViewById(R.id.tvTimer);
        tvStatus    = findViewById(R.id.tvStatus);
        tvFilePath  = findViewById(R.id.tvFilePath);
        btnRecord   = findViewById(R.id.btnRecord);
        btnStop     = findViewById(R.id.btnStop);
        dotRecording = findViewById(R.id.dotRecording);

        tvBack.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());

        btnStop.setEnabled(false);

        loadRecordingsList();
    }

    private void startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }

        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(getExternalFilesDir(null), "SaveSouls_Evidence");
            if (!dir.exists()) dir.mkdirs();
            currentFilePath = dir.getAbsolutePath() + "/REC_" + ts + ".mp4";

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentFilePath);
            recorder.prepare();
            recorder.start();

            isRecording = true;
            secondsElapsed = 0;
            timerHandler.post(timerRunnable);

            btnRecord.setEnabled(false);
            btnStop.setEnabled(true);
            dotRecording.setVisibility(View.VISIBLE);
            tvStatus.setText("● Recording in progress...");
            tvStatus.setTextColor(getColor(R.color.red));
            tvFilePath.setText("Saving to: SaveSouls_Evidence/REC_" + ts + ".mp4");

        } catch (Exception e) {
            Toast.makeText(this, "Recording failed: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }
        } catch (Exception ignored) {}

        isRecording = false;
        timerHandler.removeCallbacks(timerRunnable);

        btnRecord.setEnabled(true);
        btnStop.setEnabled(false);
        dotRecording.setVisibility(View.GONE);
        tvStatus.setText("✅ Recording saved!");
        tvStatus.setTextColor(getColor(R.color.green));

        loadRecordingsList();
    }

    private void loadRecordingsList() {
        File dir = new File(getExternalFilesDir(null), "SaveSouls_Evidence");
        TextView tvList = findViewById(R.id.tvRecordingsList);
        if (!dir.exists() || dir.listFiles() == null) {
            tvList.setText("No recordings yet.");
            return;
        }

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            tvList.setText("No recordings yet.");
            return;
        }

        // Sort newest first
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        StringBuilder sb = new StringBuilder();
        for (File f : files) {
            long kb = f.length() / 1024;
            String date = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
                .format(new Date(f.lastModified()));
            sb.append("🎙️ ").append(f.getName()).append("\n")
              .append("   ").append(date).append("  (").append(kb).append(" KB)\n\n");
        }
        tvList.setText(sb.toString().trim());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRecording) stopRecording();
        timerHandler.removeCallbacks(timerRunnable);
    }
}
