package com.safeher.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestAllPermissions();
        startSosService();

        // Wire all cards
        ((MaterialCardView) findViewById(R.id.cardSos))
            .setOnClickListener(v -> startActivity(new Intent(this, SosActivity.class)));
        ((MaterialCardView) findViewById(R.id.cardChat))
            .setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)));
        ((MaterialCardView) findViewById(R.id.cardContacts))
            .setOnClickListener(v -> startActivity(new Intent(this, ContactsActivity.class)));
        ((MaterialCardView) findViewById(R.id.cardFakeCall))
            .setOnClickListener(v -> startActivity(new Intent(this, FakeCallActivity.class)));
        ((MaterialCardView) findViewById(R.id.cardHelplines))
            .setOnClickListener(v -> showHelplines());
        ((MaterialCardView) findViewById(R.id.cardSafeWalk))
            .setOnClickListener(v -> startActivity(new Intent(this, SafeWalkActivity.class)));
        ((MaterialCardView) findViewById(R.id.cardRecorder))
            .setOnClickListener(v -> startActivity(new Intent(this, RecorderActivity.class)));
    }

    private void startSosService() {
        Intent svc = new Intent(this, SafeHerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svc);
        else
            startService(svc);
    }

    private void requestAllPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERM_CODE);
    }

    private void showHelplines() {
        new AlertDialog.Builder(this)
            .setTitle("📞 Emergency Helplines")
            .setMessage(
                "👮 Women Helpline: 1091\n\n🚔 Police: 100\n\n" +
                "🏥 Ambulance: 108\n\n📞 CHILDLINE: 1098\n\n" +
                "💙 Mental Health: 1860-2662-345\n\n⚖️ Legal Aid: 15100")
            .setPositiveButton("Call 1091", (d, w) -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                        == PackageManager.PERMISSION_GRANTED)
                    startActivity(new Intent(Intent.ACTION_CALL, Uri.parse("tel:1091")));
            })
            .setNegativeButton("Close", null)
            .show();
    }
}
