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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SosService extends Service {

    private static final String CHANNEL_ID = "safeher_bg";
    private static final String ALARM_CHANNEL = "safeher_alarm";
    public  static final String ACTION_TRIGGER = "com.safeher.app.TRIGGER_SOS";
    public  static final String ACTION_STOP_ALARM = "com.safeher.app.STOP_ALARM";
    public  static boolean isAlarmActive = false;

    // Volume button detection
    private ContentObserver volumeObserver;
    private AudioManager audioManager;
    private int lastVolume = -1;
    private int volumePressCount = 0;
    private long firstPressTime = 0;
    private static final int REQUIRED_PRESSES = 3;   // Hold +/- 3 times
    private static final long PRESS_WINDOW_MS = 2000; // Within 2 seconds

    // Alarm
    private MediaPlayer alarmPlayer;
    private static final long ALARM_DURATION_MS = 5 * 60 * 1000; // 5 minutes
    private Handler alarmHandler = new Handler(Looper.getMainLooper());

    // Voice Recorder
    private MediaRecorder mediaRecorder;
    private String recordingPath;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(1001, buildNotification());
        setupVolumeListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TRIGGER.equals(action)) {
                triggerSOS();
            } else if (ACTION_STOP_ALARM.equals(action)) {
                stopAlarmAndRecording();
            }
        }
        return START_STICKY;
    }

    // ── VOLUME BUTTON LISTENER ─────────────────────────────────
    private void setupVolumeListener() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

                if (currentVolume != lastVolume) {
                    long now = System.currentTimeMillis();

                    // Reset counter if too much time passed
                    if (now - firstPressTime > PRESS_WINDOW_MS) {
                        volumePressCount = 0;
                        firstPressTime = now;
                    }

                    volumePressCount++;

                    if (volumePressCount == 1) firstPressTime = now;

                    if (volumePressCount >= REQUIRED_PRESSES
                            && (now - firstPressTime) <= PRESS_WINDOW_MS) {
                        volumePressCount = 0;
                        // Trigger SOS on main thread
                        new Handler(Looper.getMainLooper()).post(() -> triggerSOS());
                    }

                    lastVolume = currentVolume;
                }
            }
        };

        getContentResolver().registerContentObserver(
            Settings.System.CONTENT_URI, true, volumeObserver);
    }

    // ── TRIGGER SOS ────────────────────────────────────────────
    public void triggerSOS() {
        if (isAlarmActive) return; // Already active
        isAlarmActive = true;

        // 1. Vibrate phone hard
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            long[] pattern = {0, 500, 200, 500, 200, 500};
            v.vibrate(VibrationEffect.createWaveform(pattern, 0));
        }

        // 2. Start loud alarm
        startAlarm();

        // 3. Start voice recording
        startVoiceRecording();

        // 4. Send SMS + WhatsApp to all contacts
        sendSmsToAllContacts();

        // 5. Show SOS active notification
        showSosNotification();

        // 6. Auto-stop alarm after 5 minutes
        alarmHandler.postDelayed(this::stopAlarmAndRecording, ALARM_DURATION_MS);
    }

    // ── LOUD ALARM ─────────────────────────────────────────────
    private void startAlarm() {
        try {
            // Max out volume
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

            // Use built-in alarm ringtone — guaranteed loud
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            alarmPlayer = new MediaPlayer();
            alarmPlayer.setDataSource(this, alarmUri);
            alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            alarmPlayer.setLooping(true);
            alarmPlayer.prepare();
            alarmPlayer.start();

        } catch (Exception e) {
            // Fallback: use Ringtone API
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                Ringtone ringtone = RingtoneManager.getRingtone(this, uri);
                if (ringtone != null) ringtone.play();
            } catch (Exception ex) {
                Log.e("SosService", "Alarm failed: " + ex.getMessage());
            }
        }
    }

    // ── VOICE RECORDER ─────────────────────────────────────────
    private void startVoiceRecording() {
        try {
            // Save to Downloads folder
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
            File dir = new File(getExternalFilesDir(null), "SafeHer_Evidence");
            if (!dir.exists()) dir.mkdirs();

            recordingPath = dir.getAbsolutePath() + "/SOS_" + timeStamp + ".mp3";

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setOutputFile(recordingPath);
            mediaRecorder.prepare();
            mediaRecorder.start();

        } catch (Exception e) {
            Log.e("SosService", "Recording failed: " + e.getMessage());
        }
    }

    // ── STOP EVERYTHING ────────────────────────────────────────
    public void stopAlarmAndRecording() {
        isAlarmActive = false;
        alarmHandler.removeCallbacksAndMessages(null);

        // Stop alarm
        try {
            if (alarmPlayer != null) {
                alarmPlayer.stop();
                alarmPlayer.release();
                alarmPlayer = null;
            }
        } catch (Exception e) { /* ignore */ }

        // Stop vibration
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.cancel();

        // Stop recording
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) { /* ignore */ }

        // Restore notification
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1001, buildNotification());
        nm.cancel(1002); // dismiss SOS alert
    }

    // ── SEND SMS ────────────────────────────────────────────────
    private void sendSmsToAllContacts() {
        new Thread(() -> {
            try {
                String json = getSharedPreferences("SafeHer", MODE_PRIVATE)
                    .getString("contacts", "[]");
                JSONArray arr = new JSONArray(json);

                String msg = "🆘 SOS EMERGENCY!\nI need immediate help!\n" +
                    "Sent via SafeHer Safety App\n" +
                    "Call me or contact police NOW!";

                for (int i = 0; i < arr.length(); i++) {
                    String phone = arr.getJSONObject(i).getString("phone")
                        .replaceAll("[\\s\\-]", "");
                    try {
                        SmsManager sms = SmsManager.getDefault();
                        ArrayList<String> parts = sms.divideMessage(msg);
                        sms.sendMultipartTextMessage(phone, null, parts, null, null);
                    } catch (Exception e) {
                        Log.e("SosService", "SMS failed: " + e.getMessage());
                    }
                }
            } catch (JSONException e) {
                Log.e("SosService", "Contacts error: " + e.getMessage());
            }
        }).start();
    }

    // ── NOTIFICATIONS ──────────────────────────────────────────
    private void showSosNotification() {
        Intent stopIntent = new Intent(this, SosService.class);
        stopIntent.setAction(ACTION_STOP_ALARM);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setContentTitle("🆘 SOS ACTIVE — ALARM SOUNDING")
            .setContentText("Recording evidence. SMS sent to contacts. Tap to STOP.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setColor(0xFF2D55)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(android.R.drawable.ic_delete, "STOP ALARM", stopPi)
            .setOngoing(true)
            .build();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1002, n);
    }

    private Notification buildNotification() {
        Intent tapIntent = new Intent(this, SosActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SafeHer is protecting you 🛡️")
            .setContentText("Volume ±×3 = SOS | Widget active")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel bg = new NotificationChannel(
            CHANNEL_ID, "SafeHer Protection", NotificationManager.IMPORTANCE_LOW);
        bg.setDescription("Background protection service");

        NotificationChannel alarm = new NotificationChannel(
            ALARM_CHANNEL, "SafeHer SOS Alarm", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("SOS emergency alerts");
        alarm.enableVibration(true);
        alarm.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).build());

        nm.createNotificationChannel(bg);
        nm.createNotificationChannel(alarm);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarmAndRecording();
        if (volumeObserver != null)
            getContentResolver().unregisterContentObserver(volumeObserver);
    }
}
