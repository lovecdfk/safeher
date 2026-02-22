package com.safeher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SafeHerService extends Service {

    private static final String TAG           = "SafeHerService";
    private static final String CHANNEL_ID    = "safeher_main";
    private static final String CHANNEL_ALARM = "safeher_alarm";
    private static final int    NOTIF_ID       = 1001;
    private static final int    ALARM_NOTIF_ID = 1002;
    private static final int    ALARM_DURATION_MS = 5 * 60 * 1000;

    public static final String ACTION_STOP_ALARM  = "com.safeher.app.STOP_ALARM";
    public static final String ACTION_TRIGGER_SOS = "com.safeher.app.TRIGGER_SOS";
    public static boolean isAlarmActive = false;

    // Volume detection
    private ContentObserver volumeObserver;
    private AudioManager audioManager;
    private int  lastVolume         = -1;
    private long lastVolumeChangeTime = 0;
    private int  rapidChangeCount   = 0;
    private boolean sosTriggered    = false;

    // Alarm
    private MediaPlayer alarmPlayer;
    private final Handler alarmStopHandler = new Handler(Looper.getMainLooper());

    // Recorder
    private MediaRecorder mediaRecorder;
    private String recordingPath;
    private boolean isRecording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(NOTIF_ID, buildProtectionNotification());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        startVolumeObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_ALARM.equals(action))  stopAlarm();
            if (ACTION_TRIGGER_SOS.equals(action)) triggerSOS();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── VOLUME OBSERVER ───────────────────────────────────────
    private void startVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (current == lastVolume) return;

                long now = System.currentTimeMillis();
                if (now - lastVolumeChangeTime < 2000) {
                    rapidChangeCount++;
                } else {
                    rapidChangeCount = 1;
                }
                lastVolumeChangeTime = now;
                lastVolume = current;

                if (rapidChangeCount >= 4 && !sosTriggered) {
                    sosTriggered = true;
                    rapidChangeCount = 0;
                    new Handler(Looper.getMainLooper()).post(() -> triggerSOS());
                    new Handler(Looper.getMainLooper())
                        .postDelayed(() -> sosTriggered = false, 10000);
                }
            }
        };

        getContentResolver().registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver);
    }

    // ── TRIGGER SOS ───────────────────────────────────────────
    public void triggerSOS() {
        if (isAlarmActive) return;
        isAlarmActive = true;
        Log.d(TAG, "SOS TRIGGERED");

        vibratePhone();
        startAlarm();
        startVoiceRecording();
        sendSOSAlerts();

        Intent open = new Intent(this, SosActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(open);
    }

    // ── ALARM ─────────────────────────────────────────────────
    private void startAlarm() {
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null)
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);

            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(this, alarmUri);
            alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();

            showAlarmNotification();
            alarmStopHandler.postDelayed(this::stopAlarm, ALARM_DURATION_MS);
        } catch (Exception e) {
            Log.e(TAG, "Alarm error: " + e.getMessage());
        }
    }

    private void stopAlarm() {
        isAlarmActive = false;
        try {
            if (alarmPlayer != null) {
                alarmPlayer.stop();
                alarmPlayer.release();
                alarmPlayer = null;
            }
        } catch (Exception ignored) {}

        alarmStopHandler.removeCallbacksAndMessages(null);
        stopVoiceRecording();

        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.cancel();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(ALARM_NOTIF_ID);
    }

    private void showAlarmNotification() {
        Intent stopIntent = new Intent(this, SafeHerService.class);
        stopIntent.setAction(ACTION_STOP_ALARM);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("🔊 SafeHer ALARM ACTIVE")
            .setContentText("Alarm sounding + recording. Tap STOP to silence.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "⛔ STOP ALARM", stopPi)
            .build();

        getSystemService(NotificationManager.class).notify(ALARM_NOTIF_ID, n);
    }

    // ── VOICE RECORDER ────────────────────────────────────────
    private void startVoiceRecording() {
        try {
            if (isRecording) return;
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(getExternalFilesDir(null), "SafeHer_Evidence");
            if (!dir.exists()) dir.mkdirs();
            recordingPath = dir.getAbsolutePath() + "/SOS_" + ts + ".mp4";

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(recordingPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            new Handler(Looper.getMainLooper())
                .postDelayed(this::stopVoiceRecording, ALARM_DURATION_MS);
        } catch (Exception e) {
            Log.e(TAG, "Recording error: " + e.getMessage());
        }
    }

    private void stopVoiceRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
            }
        } catch (Exception ignored) {}
    }

    // ── SMS ALERTS ────────────────────────────────────────────
    private void sendSOSAlerts() {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("SafeHer", MODE_PRIVATE);
                JSONArray arr = new JSONArray(prefs.getString("contacts", "[]"));
                String msg = "🆘 SOS EMERGENCY!\nI need immediate help!\nSent via SafeHer Safety App";

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    String phone = c.getString("phone").replaceAll("[\\s\\-]", "");
                    try {
                        SmsManager sms = SmsManager.getDefault();
                        ArrayList<String> parts = sms.divideMessage(msg);
                        sms.sendMultipartTextMessage(phone, null, parts, null, null);
                    } catch (Exception e) {
                        Log.e(TAG, "SMS failed: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Alert error: " + e.getMessage());
            }
        }).start();
    }

    // ── VIBRATE ───────────────────────────────────────────────
    private void vibratePhone() {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            long[] pattern = {0, 500, 200, 500, 200, 500};
            v.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }
    }

    // ── NOTIFICATIONS ─────────────────────────────────────────
    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel bg = new NotificationChannel(
            CHANNEL_ID, "SafeHer Protection", NotificationManager.IMPORTANCE_LOW);
        bg.setDescription("Background protection");
        nm.createNotificationChannel(bg);

        NotificationChannel alarm = new NotificationChannel(
            CHANNEL_ALARM, "SafeHer Alarm", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("SOS emergency alarm");
        alarm.enableVibration(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);
    }

    private Notification buildProtectionNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent tapPi = PendingIntent.getActivity(this, 0, tap,
            PendingIntent.FLAG_IMMUTABLE);

        Intent sos = new Intent(this, SafeHerService.class);
        sos.setAction(ACTION_TRIGGER_SOS);
        PendingIntent sosPi = PendingIntent.getService(this, 1, sos,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeHer is protecting you 🛡️")
            .setContentText("Vol ±×3 quick = SOS | Widget on home screen")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(tapPi)
            .addAction(android.R.drawable.ic_dialog_alert, "🆘 SOS NOW", sosPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarm();
        if (volumeObserver != null)
            getContentResolver().unregisterContentObserver(volumeObserver);
    }
}
