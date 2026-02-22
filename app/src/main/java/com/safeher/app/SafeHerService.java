package com.safeher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;

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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class SafeHerService extends Service {

    private static final String TAG           = "SafeHerService";
    private static final String CHANNEL_ID    = "savesouls_main";
    private static final String CHANNEL_ALARM = "savesouls_alarm";
    private static final int    NOTIF_ID       = 1001;
    private static final int    ALARM_NOTIF_ID = 1002;
    private static final int    LOC_NOTIF_ID   = 1003;
    private static final int    ALARM_DURATION_MS = 5 * 60 * 1000;

    public static final String ACTION_STOP_ALARM          = "com.safeher.app.STOP_ALARM";
    public static final String ACTION_TRIGGER_SOS         = "com.safeher.app.TRIGGER_SOS";
    public static final String ACTION_START_SCREAM_DETECT = "com.safeher.app.START_SCREAM_DETECT";
    public static final String ACTION_STOP_SCREAM_DETECT  = "com.safeher.app.STOP_SCREAM_DETECT";

    public static boolean isAlarmActive        = false;
    public static boolean isScreamDetectActive = false;

    public static final String PREF_SCREAM_ENABLED = "scream_detection_enabled";

    // ── Volume detection ──────────────────────────────────────
    private ContentObserver volumeObserver;
    private AudioManager audioManager;
    private int  lastVolume           = -1;
    private long lastVolumeChangeTime = 0;
    private int  rapidChangeCount     = 0;
    private boolean sosTriggered      = false;

    // ── Alarm ─────────────────────────────────────────────────
    private MediaPlayer alarmPlayer;
    private final Handler alarmStopHandler = new Handler(Looper.getMainLooper());

    // ── SOS Recorder ──────────────────────────────────────────
    private MediaRecorder mediaRecorder;
    private String recordingPath;
    private boolean isRecording = false;

    // ── Location ──────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocation;

    // ── Scream Detection ──────────────────────────────────────
    private static final int    SAMPLE_RATE              = 44100;
    private static final int    CHANNEL_CONFIG           = AudioFormat.CHANNEL_IN_MONO;
    private static final int    AUDIO_FORMAT             = AudioFormat.ENCODING_PCM_16BIT;
    private static final int    SCREAM_AMPLITUDE_THRESHOLD = 18000;
    private static final double SCREAM_MULTIPLIER        = 2.8;
    private static final int    SCREAM_CONFIRM_COUNT     = 3;
    private static final long   SCREAM_LOCKOUT_MS        = 30_000;
    private static final int    WINDOW_SIZE_MS           = 100;

    private AudioRecord audioRecord;
    private Thread screamThread;
    private volatile boolean screamRunning = false;

    private double backgroundNoise   = 500.0;
    private int    loudWindowCount   = 0;
    private long   lastScreamTrigger = 0;

    // ── LIFECYCLE ─────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannels();
        startForeground(NOTIF_ID, buildProtectionNotification());
        audioManager  = (AudioManager) getSystemService(AUDIO_SERVICE);
        fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        startVolumeObserver();

        SharedPreferences prefs = getSharedPreferences("SaveSouls", MODE_PRIVATE);
        if (prefs.getBoolean(PREF_SCREAM_ENABLED, false)) startScreamDetection();

        // Only restart gesture service if user had explicitly enabled it
        if (prefs.getBoolean(HandGestureService.PREF_ENABLED, false)
                && !HandGestureService.isRunning) {
            try {
                HandGestureService.start(this);
            } catch (Exception e) {
                Log.e(TAG, "Could not restart gesture service: " + e.getMessage());
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP_ALARM.equals(action))          stopAlarm();
            if (ACTION_TRIGGER_SOS.equals(action))         triggerSOS();
            if (ACTION_START_SCREAM_DETECT.equals(action)) startScreamDetection();
            if (ACTION_STOP_SCREAM_DETECT.equals(action))  stopScreamDetection();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ── VOLUME OBSERVER ───────────────────────────────────────

    private void startVolumeObserver() {
        lastVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (current == lastVolume) return;
                long now = System.currentTimeMillis();
                if (now - lastVolumeChangeTime < 2000) rapidChangeCount++;
                else rapidChangeCount = 1;
                lastVolumeChangeTime = now;
                lastVolume = current;
                if (rapidChangeCount >= 4 && !sosTriggered) {
                    sosTriggered = true;
                    rapidChangeCount = 0;
                    new Handler(Looper.getMainLooper()).post(() -> triggerSOS());
                    new Handler(Looper.getMainLooper()).postDelayed(() -> sosTriggered = false, 10000);
                }
            }
        };
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver);
    }

    // ── SCREAM DETECTION ──────────────────────────────────────

    public void startScreamDetection() {
        if (screamRunning) return;
        int bufferSize   = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int windowSamples = (SAMPLE_RATE * WINDOW_SIZE_MS) / 1000;
        if (bufferSize < windowSamples * 2) bufferSize = windowSamples * 2;
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        } catch (SecurityException e) { Log.e(TAG, "No mic permission"); return; }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release(); audioRecord = null; return;
        }
        screamRunning = true;
        isScreamDetectActive = true;
        backgroundNoise  = 500.0;
        loudWindowCount  = 0;
        getSharedPreferences("SaveSouls", MODE_PRIVATE).edit()
            .putBoolean(PREF_SCREAM_ENABLED, true).apply();
        audioRecord.startRecording();
        final short[] buffer = new short[windowSamples];
        screamThread = new Thread(() -> {
            while (screamRunning) {
                int read = audioRecord.read(buffer, 0, windowSamples);
                if (read <= 0) continue;
                double sum = 0;
                for (int i = 0; i < read; i++) sum += (double) buffer[i] * buffer[i];
                double rms = Math.sqrt(sum / read);
                if (rms < backgroundNoise) backgroundNoise = backgroundNoise * 0.95 + rms * 0.05;
                else backgroundNoise = backgroundNoise * 0.995 + rms * 0.005;
                boolean isLoud   = rms > SCREAM_AMPLITUDE_THRESHOLD;
                boolean isSpike  = rms > (backgroundNoise * SCREAM_MULTIPLIER);
                boolean inLockout = (System.currentTimeMillis() - lastScreamTrigger) < SCREAM_LOCKOUT_MS;
                if (isLoud && isSpike && !inLockout) {
                    loudWindowCount++;
                    if (loudWindowCount >= SCREAM_CONFIRM_COUNT) {
                        loudWindowCount   = 0;
                        lastScreamTrigger = System.currentTimeMillis();
                        screamRunning     = false;
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                if (audioRecord != null) {
                                    audioRecord.stop(); audioRecord.release(); audioRecord = null;
                                }
                            } catch (Exception ignored) {}
                            if (!isAlarmActive) triggerSOS();
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (getSharedPreferences("SaveSouls", MODE_PRIVATE)
                                        .getBoolean(PREF_SCREAM_ENABLED, false))
                                    startScreamDetection();
                            }, ALARM_DURATION_MS + 2000);
                        });
                        break;
                    }
                } else { if (loudWindowCount > 0) loudWindowCount--; }
            }
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }, "ScreamDetectorThread");
        screamThread.setPriority(Thread.MIN_PRIORITY);
        screamThread.setDaemon(true);
        screamThread.start();
        refreshProtectionNotification();
    }

    public void stopScreamDetection() {
        screamRunning = false;
        isScreamDetectActive = false;
        getSharedPreferences("SaveSouls", MODE_PRIVATE).edit()
            .putBoolean(PREF_SCREAM_ENABLED, false).apply();
        if (screamThread != null) { screamThread.interrupt(); screamThread = null; }
        refreshProtectionNotification();
    }

    // ── TRIGGER SOS ───────────────────────────────────────────

    public void triggerSOS() {
        if (isAlarmActive) return;
        isAlarmActive = true;
        Log.d(TAG, "SOS TRIGGERED");

        vibratePhone();
        startAlarm();

        // 1. Get location and send notifications + SMS
        sendLocationAlert();

        // 2. Start voice recording after 300ms (let scream detect release mic first)
        new Handler(Looper.getMainLooper()).postDelayed(this::startVoiceRecording, 300);

        // 3. Start camera evidence capture
        startCameraEvidence();

        // 4. Open SOS screen
        Intent open = new Intent(this, SosActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(open);
    }

    // ── CAMERA EVIDENCE ───────────────────────────────────────

    private void startCameraEvidence() {
        try {
            Intent camIntent = new Intent(this, CameraEvidenceService.class);
            startService(camIntent);
            Log.d(TAG, "Camera evidence service started");
        } catch (Exception e) {
            Log.e(TAG, "Camera start failed: " + e.getMessage());
        }
    }

    // ── LOCATION ALERT ────────────────────────────────────────

    private void sendLocationAlert() {
        try {
            fusedLocation.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    String locationText;
                    String mapsUrl;
                    if (location != null) {
                        double lat = location.getLatitude();
                        double lng = location.getLongitude();
                        mapsUrl      = "https://maps.google.com/?q=" + lat + "," + lng;
                        locationText = "📍 Location: " + mapsUrl;
                    } else {
                        locationText = "📍 Location unavailable (GPS off?)";
                        mapsUrl      = "";
                    }
                    // Show notification with location
                    showLocationNotification(locationText, mapsUrl);
                    // Send SMS with location
                    sendSOSAlerts(locationText);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Location failed: " + e.getMessage());
                    showLocationNotification("📍 Location unavailable", "");
                    sendSOSAlerts("📍 Location unavailable");
                });
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission denied");
            sendSOSAlerts("📍 Location permission not granted");
        }
    }

    private void showLocationNotification(String locationText, String mapsUrl) {
        // Tapping the notification opens Google Maps if we have a URL
        PendingIntent mapPi = null;
        if (!mapsUrl.isEmpty()) {
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mapPi = PendingIntent.getActivity(this, 99, mapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("🆘 SOS ACTIVATED — Current Location")
            .setContentText(locationText)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(
                "SOS has been triggered!\n\n" + locationText +
                "\n\nAlarm is sounding. Evidence is being recorded."))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(true);

        if (mapPi != null) {
            builder.setContentIntent(mapPi);
            builder.addAction(android.R.drawable.ic_dialog_map, "📍 Open Maps", mapPi);
        }

        getSystemService(NotificationManager.class).notify(LOC_NOTIF_ID, builder.build());
    }

    // ── ALARM ─────────────────────────────────────────────────

    /**
     * Plays the app's built-in SOS alarm sound (res/raw/sos_alarm.wav).
     * Sets stream volume to max and USAGE_ALARM so it bypasses silent mode.
     * No ringtone or external storage access needed.
     */
    private void startAlarm() {
        try {
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

            alarmPlayer = MediaPlayer.create(this, R.raw.sos_alarm);
            if (alarmPlayer == null) {
                Log.e(TAG, "Failed to create MediaPlayer for sos_alarm");
                return;
            }
            alarmPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            alarmPlayer.setLooping(true);
            alarmPlayer.start();
            Log.d(TAG, "SOS alarm started");

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

        // Stop camera
        stopService(new Intent(this, CameraEvidenceService.class));

        alarmStopHandler.removeCallbacksAndMessages(null);
        stopVoiceRecording();

        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null) v.cancel();

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(ALARM_NOTIF_ID);
        nm.cancel(LOC_NOTIF_ID);
    }

    private void showAlarmNotification() {
        Intent stopIntent = new Intent(this, SafeHerService.class);
        stopIntent.setAction(ACTION_STOP_ALARM);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ALARM)
            .setContentTitle("🔊 SaveSouls ALARM ACTIVE")
            .setContentText("Alarm + recording + camera active. Tap STOP to silence.")
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
        if (isRecording) return;
        try {
            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir   = new File(getExternalFilesDir(null), "SaveSouls_Evidence");
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
            Log.d(TAG, "Voice recording started: " + recordingPath);

            new Handler(Looper.getMainLooper()).postDelayed(this::stopVoiceRecording, ALARM_DURATION_MS);
        } catch (Exception e) {
            Log.e(TAG, "Recording error: " + e.getMessage());
            // Retry once after 1s if mic might still be held by scream detect
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isRecording) startVoiceRecording();
            }, 1000);
        }
    }

    private void stopVoiceRecording() {
        try {
            if (mediaRecorder != null && isRecording) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording   = false;
            }
        } catch (Exception ignored) {}
    }

    // ── SMS ALERTS ────────────────────────────────────────────

    private void sendSOSAlerts(String locationText) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("SaveSouls", MODE_PRIVATE);
                JSONArray arr = new JSONArray(prefs.getString("contacts", "[]"));
                String msg = "🆘 SOS EMERGENCY!\nI need immediate help!\n"
                    + locationText + "\nSent via SaveSouls Safety App";

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    String phone = c.getString("phone").replaceAll("[\\s\\-]", "");
                    try {
                        SmsManager sms = SmsManager.getDefault();
                        ArrayList<String> parts = sms.divideMessage(msg);
                        sms.sendMultipartTextMessage(phone, null, parts, null, null);
                    } catch (Exception e) {
                        Log.e(TAG, "SMS failed to " + phone + ": " + e.getMessage());
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
            CHANNEL_ID, "SaveSouls Protection", NotificationManager.IMPORTANCE_LOW);
        bg.setDescription("Background protection service");
        nm.createNotificationChannel(bg);

        NotificationChannel alarm = new NotificationChannel(
            CHANNEL_ALARM, "SaveSouls Alarm", NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription("SOS emergency alarm");
        alarm.enableVibration(true);
        alarm.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alarm);
    }

    private Notification buildProtectionNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent tapPi = PendingIntent.getActivity(this, 0, tap, PendingIntent.FLAG_IMMUTABLE);

        Intent sos = new Intent(this, SafeHerService.class);
        sos.setAction(ACTION_TRIGGER_SOS);
        PendingIntent sosPi = PendingIntent.getService(this, 1, sos, PendingIntent.FLAG_IMMUTABLE);

        String screamStatus = isScreamDetectActive ? " | 🎙️ Scream ON" : "";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SaveSouls is protecting you 🛡️")
            .setContentText("Vol ±×4 = SOS | Camera+GPS+Audio on trigger" + screamStatus)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(tapPi)
            .addAction(android.R.drawable.ic_dialog_alert, "🆘 SOS NOW", sosPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void refreshProtectionNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildProtectionNotification());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopAlarm();
        stopScreamDetection();
        if (volumeObserver != null)
            getContentResolver().unregisterContentObserver(volumeObserver);
    }
}
