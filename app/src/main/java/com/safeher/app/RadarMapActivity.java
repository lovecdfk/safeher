package com.safeher.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RadarMapActivity — real-time radar map with full contact sharing:
 *
 *  • GREEN  dot = user (center, live GPS)
 *  • RED    dots = saved contacts sharing location via Firebase
 *  • BLUE   blips = BT/BLE anonymous devices (RSSI estimated distance)
 *  • PURPLE dots = other SafeHer users nearby
 *
 *  Sharing options:
 *  📱 SMS  — sends location message to ALL contacts
 *  💬 WhatsApp — opens WhatsApp with location for each contact
 *  🔁 Live Share — pushes location to Firebase every 10s (contacts see it on their radar)
 *  🆘 SOS Share — sends urgent SMS + WhatsApp to all contacts simultaneously
 */
public class RadarMapActivity extends AppCompatActivity {

    // ── UI ─────────────────────────────────────────────────────────────────
    private RadarView radarView;
    private TextView tvBack, tvStatus, tvPeopleCount, tvLocation, tvContactStatus;
    private TextView tvLiveShareStatus;
    private MaterialButton btnSms, btnWhatsApp, btnSosAlert, btnToggleLiveShare;

    // ── Location ───────────────────────────────────────────────────────────
    private LocationManager locationManager;
    private double myLat = 0, myLng = 0;
    private boolean hasLocation = false;
    private boolean liveShareActive = false;

    // ── Bluetooth ──────────────────────────────────────────────────────────
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean bleScanning = false;
    private boolean btReceiverRegistered = false;
    private final Map<String, Integer> btDeviceRssi = new HashMap<>();

    // ── Dots ───────────────────────────────────────────────────────────────
    private final ArrayList<RadarView.RadarDot> contactDots = new ArrayList<>();
    private final ArrayList<RadarView.RadarDot> safeHerDots = new ArrayList<>();
    private final ArrayList<RadarView.RadarDot> btBlips     = new ArrayList<>();

    // ── Intervals ──────────────────────────────────────────────────────────
    private static final long BT_SCAN_INTERVAL       = 20_000L;
    private static final long FIREBASE_POLL_INTERVAL =  8_000L;
    private static final long LIVE_SHARE_INTERVAL    = 10_000L;

    // ── Firebase ───────────────────────────────────────────────────────────
    // 👇 Replace with your Firebase Realtime Database URL
    private static final String FB_BASE =
            "https://safeher-radar-default-rtdb.firebaseio.com";

    private String myPhoneKey;
    private okhttp3.OkHttpClient http;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int PERM_REQ = 401;

    // ──────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radar_map);

        bindViews();
        setupClickListeners();
        initHttp();
        initFirebaseKey();
        initLocation();
        initBluetooth();
        requestPermissionsIfNeeded();
    }

    private void bindViews() {
        radarView           = findViewById(R.id.radarView);
        tvBack              = findViewById(R.id.tvBack);
        tvStatus            = findViewById(R.id.tvStatus);
        tvPeopleCount       = findViewById(R.id.tvPeopleCount);
        tvLocation          = findViewById(R.id.tvLocation);
        tvContactStatus     = findViewById(R.id.tvContactStatus);
        tvLiveShareStatus   = findViewById(R.id.tvLiveShareStatus);
        btnSms              = findViewById(R.id.btnSms);
        btnWhatsApp         = findViewById(R.id.btnWhatsApp);
        btnSosAlert         = findViewById(R.id.btnSosAlert);
        btnToggleLiveShare  = findViewById(R.id.btnToggleLiveShare);
    }

    private void setupClickListeners() {
        tvBack.setOnClickListener(v -> finish());
        btnSms.setOnClickListener(v -> sendSmsToAllContacts(buildLocationMessage(false)));
        btnWhatsApp.setOnClickListener(v -> sendWhatsAppToAllContacts(buildLocationMessage(false)));
        btnSosAlert.setOnClickListener(v -> triggerSosAlert());
        btnToggleLiveShare.setOnClickListener(v -> toggleLiveShare());
    }

    private void initHttp() {
        http = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    private void initFirebaseKey() {
        SharedPreferences prefs = getSharedPreferences("SaveSouls", MODE_PRIVATE);
        myPhoneKey = prefs.getString("my_phone_key", null);
        if (myPhoneKey == null) {
            myPhoneKey = "user_" + System.currentTimeMillis();
            prefs.edit().putString("my_phone_key", myPhoneKey).apply();
        }
    }

    // ── Location ──────────────────────────────────────────────────────────

    private void initLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            LocationListener listener = loc -> {
                myLat = loc.getLatitude();
                myLng = loc.getLongitude();
                hasLocation = true;
                String coordText = String.format(Locale.US, "📍 %.5f, %.5f", myLat, myLng);
                tvLocation.setText(coordText);
                radarView.setMyLocation(myLat, myLng);
                updateStatusBar();
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 1, listener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 1, listener);

            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null) {
                myLat = last.getLatitude(); myLng = last.getLongitude(); hasLocation = true;
                tvLocation.setText(String.format(Locale.US, "📍 %.5f, %.5f", myLat, myLng));
                radarView.setMyLocation(myLat, myLng);
            }
        } catch (SecurityException e) {
            tvLocation.setText("Location permission needed");
        }
        handler.postDelayed(firebaseLoop, FIREBASE_POLL_INTERVAL);
    }

    private final Runnable firebaseLoop = new Runnable() {
        @Override public void run() {
            if (hasLocation && liveShareActive) pushMyLocation();
            pollContactLocations();
            pollSafeHerUsers();
            handler.postDelayed(this, FIREBASE_POLL_INTERVAL);
        }
    };

    // ── Live Share toggle ─────────────────────────────────────────────────

    private void toggleLiveShare() {
        liveShareActive = !liveShareActive;
        if (liveShareActive) {
            btnToggleLiveShare.setText("🔴  Stop Live Sharing");
            tvLiveShareStatus.setText("🟢 Live sharing ON — contacts can see you on their radar");
            tvLiveShareStatus.setTextColor(getColor(R.color.green));
            if (hasLocation) pushMyLocation();
            // Notify contacts that live share started
            sendSmsToAllContacts(buildLocationMessage(false)
                    + "\n\n📡 I've started live location sharing. Open SafeHer to see me on radar.");
        } else {
            btnToggleLiveShare.setText("📡  Start Live Sharing");
            tvLiveShareStatus.setText("⚫ Live sharing OFF");
            tvLiveShareStatus.setTextColor(getColor(R.color.muted));
            // Remove from Firebase
            firebasePut("/users/" + myPhoneKey + ".json",
                    "{\"lat\":0,\"lng\":0,\"ts\":0}");
        }
    }

    // ── Build message ─────────────────────────────────────────────────────

    private String buildLocationMessage(boolean isSos) {
        String time = new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date());
        String mapLink = hasLocation
                ? "https://maps.google.com/?q=" + myLat + "," + myLng
                : "Location unavailable";
        String googleMapsLink = hasLocation
                ? "https://www.google.com/maps/search/?api=1&query=" + myLat + "," + myLng
                : "";

        if (isSos) {
            return "🆘 *SOS EMERGENCY ALERT* 🆘\n"
                    + "SafeHer user needs help!\n\n"
                    + "📍 *Live Location:*\n" + mapLink + "\n\n"
                    + "🗺️ Open in Maps: " + googleMapsLink + "\n\n"
                    + "📋 Coordinates: " + myLat + ", " + myLng + "\n"
                    + "🕐 Time: " + time + "\n\n"
                    + "Please contact immediately or call emergency services!";
        } else {
            return "📍 *SafeHer Location Update*\n\n"
                    + "I'm sharing my current location with you.\n\n"
                    + "🗺️ My location: " + mapLink + "\n\n"
                    + "📋 Coordinates: " + myLat + ", " + myLng + "\n"
                    + "🕐 Time: " + time + "\n\n"
                    + "Sent via SafeHer Safety App 🛡️";
        }
    }

    // ── SMS sending ───────────────────────────────────────────────────────

    /**
     * Sends SMS to ALL saved contacts with the given message.
     */
    private void sendSmsToAllContacts(String message) {
        if (!hasLocation) {
            Toast.makeText(this, "Getting location, please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            int sent = 0;
            try {
                JSONArray contacts = getContacts();
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);
                    String phone = c.optString("phone", "").replaceAll("[\\s\\-]", "");
                    String name  = c.optString("name", "Contact");
                    if (phone.isEmpty()) continue;
                    try {
                        SmsManager sms = SmsManager.getDefault();
                        ArrayList<String> parts = sms.divideMessage(message);
                        sms.sendMultipartTextMessage(phone, null, parts, null, null);
                        sent++;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                final int finalSent = sent;
                handler.post(() -> {
                    Toast.makeText(this,
                            "📱 SMS sent to " + finalSent + " contact(s)",
                            Toast.LENGTH_SHORT).show();
                    tvStatus.setText("✅ SMS sent to " + finalSent + " contact(s)");
                    tvStatus.setTextColor(getColor(R.color.green));
                });
            } catch (JSONException e) { e.printStackTrace(); }
        }).start();
    }

    // ── WhatsApp sending ──────────────────────────────────────────────────

    /**
     * Opens WhatsApp for each contact sequentially.
     * On first contact it opens immediately; remaining contacts get a Toast
     * with instructions since Android only allows one WhatsApp intent at a time.
     */
    private void sendWhatsAppToAllContacts(String message) {
        if (!hasLocation) {
            Toast.makeText(this, "Getting location, please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONArray contacts = getContacts();
            if (contacts.length() == 0) {
                Toast.makeText(this, "No contacts saved", Toast.LENGTH_SHORT).show();
                return;
            }

            // Open WhatsApp for all contacts — chain them with delays
            for (int i = 0; i < contacts.length(); i++) {
                JSONObject c = contacts.getJSONObject(i);
                String phone = c.optString("phone", "").replaceAll("[\\s\\-]", "");
                String name  = c.optString("name", "Contact");
                if (phone.isEmpty()) continue;

                final String finalPhone = phone;
                final String finalName  = name;
                final int delay = i * 1500; // stagger by 1.5s each

                handler.postDelayed(() ->
                        openWhatsApp(finalPhone, finalName, message), delay);
            }

            tvStatus.setText("💬 Opening WhatsApp for " + contacts.length() + " contact(s)…");
            tvStatus.setTextColor(getColor(R.color.green));

        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error reading contacts", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Opens WhatsApp chat with the given phone and pre-filled message.
     * Falls back to WhatsApp web if app not installed.
     */
    private void openWhatsApp(String phone, String contactName, String message) {
        // Ensure phone has country code — if starts with 0, replace with +91
        String formattedPhone = phone;
        if (formattedPhone.startsWith("0") && formattedPhone.length() <= 11) {
            formattedPhone = "91" + formattedPhone.substring(1);
        }

        try {
            // Try WhatsApp app direct
            String url = "https://api.whatsapp.com/send?phone=" + formattedPhone
                    + "&text=" + Uri.encode(message);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.setPackage("com.whatsapp");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (getPackageManager().resolveActivity(intent, 0) != null) {
                startActivity(intent);
            } else {
                // WhatsApp not installed — open browser fallback
                intent.setPackage(null);
                startActivity(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this,
                    "Could not open WhatsApp for " + contactName,
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ── SOS Alert ─────────────────────────────────────────────────────────

    /**
     * Fires SOS: SMS to all contacts + WhatsApp to all contacts simultaneously.
     */
    private void triggerSosAlert() {
        String sosMessage = buildLocationMessage(true);

        // Flash UI
        tvStatus.setText("🆘 SOS ALERT SENT!");
        tvStatus.setTextColor(getColor(R.color.red));
        Toast.makeText(this, "🆘 SOS sent via SMS + WhatsApp!", Toast.LENGTH_LONG).show();

        // SMS — runs in background thread
        sendSmsToAllContacts(sosMessage);

        // WhatsApp — stagger each contact
        sendWhatsAppToAllContacts(sosMessage);

        // Push location to Firebase immediately regardless of live share state
        if (hasLocation) pushMyLocation();
    }

    // ── Firebase: push my location ────────────────────────────────────────

    private void pushMyLocation() {
        String myName = getSharedPreferences("SaveSouls", MODE_PRIVATE)
                .getString("my_name", "Me");
        String body = String.format(Locale.US,
                "{\"lat\":%.7f,\"lng\":%.7f,\"name\":\"%s\",\"app\":\"safeher\",\"ts\":%d}",
                myLat, myLng, myName, System.currentTimeMillis());
        firebasePut("/users/" + myPhoneKey + ".json", body);
    }

    // ── Firebase: contacts' live locations ────────────────────────────────

    private void pollContactLocations() {
        try {
            JSONArray arr = getContacts();
            if (arr.length() == 0) {
                tvContactStatus.setText("No contacts — add some in Contacts screen");
                return;
            }
            tvContactStatus.setText("👥 Tracking " + arr.length() + " contact(s) on radar");

            for (int i = 0; i < arr.length(); i++) {
                JSONObject c    = arr.getJSONObject(i);
                String name     = c.optString("name", "Contact");
                String phoneKey = c.optString("phone", "").replaceAll("[^0-9]", "");
                if (phoneKey.isEmpty()) continue;
                final String cName = name;

                firebaseGet("/users/" + phoneKey + ".json", response -> {
                    try {
                        JSONObject u = new JSONObject(response);
                        double lat = u.optDouble("lat", 0);
                        double lng = u.optDouble("lng", 0);
                        long ts    = u.optLong("ts", 0);
                        if (lat == 0 || lng == 0) return;
                        if (System.currentTimeMillis() - ts > 10 * 60 * 1000L) return;
                        if (!hasLocation) return;

                        double distM   = distMeters(myLat, myLng, lat, lng);
                        double bearDeg = bearing(myLat, myLng, lat, lng);
                        RadarView.RadarDot dot = new RadarView.RadarDot(
                                distM, bearDeg, RadarView.DotType.CONTACT,
                                cName, String.format(Locale.US, "%.0fm", distM));
                        synchronized (contactDots) {
                            contactDots.removeIf(d -> d.label.equals(cName));
                            contactDots.add(dot);
                        }
                        radarView.setContactDots(new ArrayList<>(contactDots));
                        updateStatusBar();
                    } catch (JSONException ignored) {}
                });
            }
        } catch (JSONException e) { e.printStackTrace(); }
    }

    // ── Firebase: other SafeHer users ─────────────────────────────────────

    private void pollSafeHerUsers() {
        firebaseGet("/users.json", response -> {
            try {
                JSONObject all = new JSONObject(response);
                ArrayList<RadarView.RadarDot> nearby = new ArrayList<>();

                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (key.equals(myPhoneKey)) continue;
                    JSONObject u    = all.getJSONObject(key);
                    double lat      = u.optDouble("lat", 0);
                    double lng      = u.optDouble("lng", 0);
                    String name     = u.optString("name", "SafeHer User");
                    boolean safeHer = "safeher".equals(u.optString("app", ""));
                    long ts         = u.optLong("ts", 0);

                    if (!safeHer || lat == 0 || lng == 0) continue;
                    if (System.currentTimeMillis() - ts > 5 * 60 * 1000L) continue;
                    boolean isContact = false;
                    synchronized (contactDots) {
                        for (RadarView.RadarDot d : contactDots)
                            if (d.label.equals(name)) { isContact = true; break; }
                    }
                    if (isContact) continue;

                    if (hasLocation) {
                        double distM   = distMeters(myLat, myLng, lat, lng);
                        double bearDeg = bearing(myLat, myLng, lat, lng);
                        if (distM < 500)
                            nearby.add(new RadarView.RadarDot(distM, bearDeg,
                                    RadarView.DotType.SAFEHER_USER, name,
                                    String.format(Locale.US, "%.0fm", distM)));
                    }
                }
                safeHerDots.clear(); safeHerDots.addAll(nearby);
                radarView.setSafeHerUsers(safeHerDots);
                updateStatusBar();
            } catch (JSONException ignored) {}
        });
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────

    private void initBluetooth() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) return;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(btRx, f);
        btReceiverRegistered = true;
        if (bluetoothAdapter.isEnabled()) bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        handler.post(btScanLoop);
    }

    private final Runnable btScanLoop = new Runnable() {
        @Override public void run() { triggerBtScan(); handler.postDelayed(this, BT_SCAN_INTERVAL); }
    };

    private void triggerBtScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        try {
            if (bluetoothAdapter.isDiscovering()) bluetoothAdapter.cancelDiscovery();
            bluetoothAdapter.startDiscovery();
            if (bleScanner != null && !bleScanning) {
                bleScanner.startScan(bleCb); bleScanning = true;
                handler.postDelayed(() -> {
                    try { if (bleScanning) { bleScanner.stopScan(bleCb); bleScanning = false; } }
                    catch (SecurityException ignored) {}
                }, 10_000);
            }
        } catch (SecurityException ignored) {}
    }

    private final BroadcastReceiver btRx = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                BluetoothDevice dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short)-70);
                if (dev != null) { btDeviceRssi.put(dev.getAddress(), rssi); refreshBtBlips(); }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) {
                refreshBtBlips();
            }
        }
    };

    private final ScanCallback bleCb = new ScanCallback() {
        @Override public void onScanResult(int t, ScanResult r) {
            btDeviceRssi.put(r.getDevice().getAddress(), r.getRssi()); refreshBtBlips();
        }
    };

    private void refreshBtBlips() {
        btBlips.clear();
        for (Map.Entry<String, Integer> e : btDeviceRssi.entrySet()) {
            double distM = rssiToMeters(e.getValue());
            double angle = Math.abs(e.getKey().hashCode() % 360);
            btBlips.add(new RadarView.RadarDot(distM, angle,
                    RadarView.DotType.BLUETOOTH, "BT", e.getValue() + " dBm"));
        }
        radarView.setBluetoothBlips(btBlips);
        updateStatusBar();
    }

    private double rssiToMeters(int rssi) { return Math.pow(10.0, (-59.0 - rssi) / 20.0); }

    // ── Status bar ────────────────────────────────────────────────────────

    private void updateStatusBar() {
        int total = contactDots.size() + safeHerDots.size() + btBlips.size();
        tvPeopleCount.setText(total + " detected");
        if (!contactDots.isEmpty()) {
            tvStatus.setText("🟢 " + contactDots.size() + " contact(s) visible");
            tvStatus.setTextColor(getColor(R.color.green));
        } else if (total > 0) {
            tvStatus.setText("🟡 " + total + " anonymous devices");
            tvStatus.setTextColor(getColor(R.color.orange_warn));
        } else {
            tvStatus.setText("🔴 No one detected nearby");
            tvStatus.setTextColor(getColor(R.color.red));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private JSONArray getContacts() throws JSONException {
        String json = getSharedPreferences("SaveSouls", MODE_PRIVATE)
                .getString("contacts", "[]");
        return new JSONArray(json);
    }

    private void firebasePut(String path, String json) {
        new Thread(() -> {
            try {
                okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        json, okhttp3.MediaType.parse("application/json"));
                http.newCall(new okhttp3.Request.Builder()
                        .url(FB_BASE + path).put(body).build()).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

    private void firebaseGet(String path, java.util.function.Consumer<String> cb) {
        new Thread(() -> {
            try {
                try (okhttp3.Response resp = http.newCall(
                        new okhttp3.Request.Builder().url(FB_BASE + path).get().build()).execute()) {
                    if (resp.body() != null) {
                        String body = resp.body().string();
                        if (body != null && !body.equals("null"))
                            handler.post(() -> cb.accept(body));
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private double distMeters(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000, dLat = Math.toRadians(lat2-lat1), dLng = Math.toRadians(lng2-lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                  *Math.sin(dLng/2)*Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private double bearing(double lat1, double lng1, double lat2, double lng2) {
        double dLng = Math.toRadians(lng2-lng1);
        double y = Math.sin(dLng)*Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1))*Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y,x))+360)%360;
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private void requestPermissionsIfNeeded() {
        String[] perms = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
        };
        boolean ok = true;
        for (String p : perms)
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
            { ok = false; break; }
        if (!ok) ActivityCompat.requestPermissions(this, perms, PERM_REQ);
        else triggerBtScan();
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r); triggerBtScan();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (btReceiverRegistered) { unregisterReceiver(btRx); btReceiverRegistered = false; }
        try {
            if (bluetoothAdapter != null) bluetoothAdapter.cancelDiscovery();
            if (bleScanner != null && bleScanning) bleScanner.stopScan(bleCb);
        } catch (SecurityException ignored) {}
        firebasePut("/users/" + myPhoneKey + ".json", "{\"lat\":0,\"lng\":0,\"ts\":0}");
    }
}
