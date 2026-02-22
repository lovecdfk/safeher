package com.safeher.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * RadarView — custom View that draws a real-time radar display:
 *
 *   • Dark circular radar background with grid rings
 *   • Rotating green sweep line (like a real radar)
 *   • 🟢 Green dot  = user (center)
 *   • 🔴 Red dots   = saved contacts (GPS position)
 *   • 🔵 Blue blips = BT/BLE devices (RSSI estimated distance)
 *   • 🟣 Purple dots = other SafeHer users
 *   • Fade-trail effect on sweep
 *   • Labels on each dot
 *
 * Radar range = 100 metres (configurable).
 */
public class RadarView extends View {

    // ── Dot types ────────────────────────────────────────────────────────────
    public enum DotType { CONTACT, BLUETOOTH, SAFEHER_USER }

    public static class RadarDot {
        public double distanceMeters;   // from user
        public double bearingDeg;       // 0 = North, 90 = East
        public DotType type;
        public String label;
        public String sublabel;
        public long lastSeenMs;

        public RadarDot(double distanceMeters, double bearingDeg,
                        DotType type, String label, String sublabel) {
            this.distanceMeters = distanceMeters;
            this.bearingDeg = bearingDeg;
            this.type = type;
            this.label = label;
            this.sublabel = sublabel;
            this.lastSeenMs = System.currentTimeMillis();
        }
    }

    // ── Config ───────────────────────────────────────────────────────────────
    private static final double RADAR_RANGE_M = 100.0;  // metres shown on radar
    private static final int SWEEP_INTERVAL_MS = 16;    // ~60fps
    private static final float SWEEP_SPEED_DEG = 2.0f;  // degrees per frame

    // ── Paint objects ─────────────────────────────────────────────────────────
    private final Paint bgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crossPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meDotPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mePulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contactPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint safeHerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subLabelPaint= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ── State ─────────────────────────────────────────────────────────────────
    private float sweepAngle = 0f;
    private float mePulse = 0f;
    private boolean mePulseGrowing = true;
    private double myLat = 0, myLng = 0;

    private final List<RadarDot> contactDots    = new ArrayList<>();
    private final List<RadarDot> btBlips        = new ArrayList<>();
    private final List<RadarDot> safeHerUsers   = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable animLoop = new Runnable() {
        @Override public void run() {
            sweepAngle = (sweepAngle + SWEEP_SPEED_DEG) % 360f;
            // Pulse
            if (mePulseGrowing) { mePulse += 0.4f; if (mePulse > 12f) mePulseGrowing = false; }
            else                { mePulse -= 0.4f; if (mePulse < 0f)  mePulseGrowing = true;  }
            invalidate();
            handler.postDelayed(this, SWEEP_INTERVAL_MS);
        }
    };

    // ── Constructors ──────────────────────────────────────────────────────────
    public RadarView(Context ctx) { super(ctx); init(); }
    public RadarView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public RadarView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle); init(); }

    private void init() {
        // BG
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.parseColor("#080C0A"));

        // Rings
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.parseColor("#1A34D399"));
        ringPaint.setStrokeWidth(1.5f);

        // Cross-hair
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setColor(Color.parseColor("#0F34D399"));
        crossPaint.setStrokeWidth(1f);

        // Sweep line
        sweepPaint.setStyle(Paint.Style.FILL);
        sweepPaint.setAntiAlias(true);

        // Me dot
        meDotPaint.setStyle(Paint.Style.FILL);
        meDotPaint.setColor(Color.parseColor("#34D399")); // green

        // Me pulse ring
        mePulsePaint.setStyle(Paint.Style.STROKE);
        mePulsePaint.setColor(Color.parseColor("#8034D399"));
        mePulsePaint.setStrokeWidth(2f);

        // Contact (red)
        contactPaint.setStyle(Paint.Style.FILL);
        contactPaint.setColor(Color.parseColor("#FF2D55"));

        // BT blip (blue)
        btPaint.setStyle(Paint.Style.FILL);
        btPaint.setColor(Color.parseColor("#3B82F6"));

        // SafeHer user (purple)
        safeHerPaint.setStyle(Paint.Style.FILL);
        safeHerPaint.setColor(Color.parseColor("#A855F7"));

        // Labels
        labelPaint.setColor(Color.parseColor("#F0EEF8"));
        labelPaint.setTextSize(22f);
        labelPaint.setAntiAlias(true);
        labelPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        subLabelPaint.setColor(Color.parseColor("#7A7899"));
        subLabelPaint.setTextSize(16f);
        subLabelPaint.setAntiAlias(true);

        // Range text
        rangePaint.setColor(Color.parseColor("#2034D399"));
        rangePaint.setTextSize(18f);
        rangePaint.setAntiAlias(true);

        handler.post(animLoop);
    }

    // ── Public setters ────────────────────────────────────────────────────────
    public void setMyLocation(double lat, double lng) { myLat = lat; myLng = lng; }

    public synchronized void setContactDots(List<RadarDot> dots) {
        contactDots.clear(); contactDots.addAll(dots); }

    public synchronized void setBluetoothBlips(List<RadarDot> dots) {
        btBlips.clear(); btBlips.addAll(dots); }

    public synchronized void setSafeHerUsers(List<RadarDot> dots) {
        safeHerUsers.clear(); safeHerUsers.addAll(dots); }

    // ── Drawing ───────────────────────────────────────────────────────────────
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(cx, cy) * 0.92f;

        drawBackground(canvas, cx, cy, r);
        drawRings(canvas, cx, cy, r);
        drawCrossHair(canvas, cx, cy, r);
        drawSweep(canvas, cx, cy, r);
        drawDots(canvas, cx, cy, r);
        drawMe(canvas, cx, cy);
        drawLegend(canvas, w, h);
    }

    private void drawBackground(Canvas canvas, float cx, float cy, float r) {
        // Outer clip circle
        bgPaint.setColor(Color.parseColor("#080C0A"));
        canvas.drawCircle(cx, cy, r, bgPaint);

        // Radial gradient for atmosphere
        RadialGradient grad = new RadialGradient(
                cx, cy, r,
                new int[]{
                        Color.parseColor("#0D1F17"),
                        Color.parseColor("#080C0A")
                },
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP);
        bgPaint.setShader(grad);
        canvas.drawCircle(cx, cy, r, bgPaint);
        bgPaint.setShader(null);
    }

    private void drawRings(Canvas canvas, float cx, float cy, float r) {
        // 4 range rings: 25m, 50m, 75m, 100m
        float[] fracs = {0.25f, 0.50f, 0.75f, 1.0f};
        String[] labels = {"25m", "50m", "75m", "100m"};
        for (int i = 0; i < fracs.length; i++) {
            float rr = r * fracs[i];
            ringPaint.setAlpha(i == fracs.length - 1 ? 80 : 40);
            canvas.drawCircle(cx, cy, rr, ringPaint);
            // Range label
            canvas.drawText(labels[i], cx + rr + 4, cy - 4, rangePaint);
        }
    }

    private void drawCrossHair(Canvas canvas, float cx, float cy, float r) {
        canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint);
        canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint);
        // N/S/E/W labels
        Paint compassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        compassPaint.setColor(Color.parseColor("#1A34D399"));
        compassPaint.setTextSize(20f);
        compassPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("N", cx, cy - r + 20, compassPaint);
        canvas.drawText("S", cx, cy + r - 6, compassPaint);
        canvas.drawText("E", cx + r - 6, cy + 6, compassPaint);
        canvas.drawText("W", cx - r + 6, cy + 6, compassPaint);
    }

    private void drawSweep(Canvas canvas, float cx, float cy, float r) {
        // Sweep gradient arc (fade trail)
        for (int i = 0; i < 60; i++) {
            float alpha = (float) i / 60f;
            float startA = sweepAngle - i;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb((int) (alpha * 60), 52, 211, 153));
            RectF rect = new RectF(cx - r, cy - r, cx + r, cy + r);
            Path path = new Path();
            path.moveTo(cx, cy);
            path.arcTo(rect, startA, 1f);
            path.close();
            canvas.drawPath(path, p);
        }
        // Bright leading line
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#AA34D399"));
        linePaint.setStrokeWidth(2f);
        linePaint.setStyle(Paint.Style.STROKE);
        double rad = Math.toRadians(sweepAngle);
        canvas.drawLine(cx, cy,
                cx + (float)(r * Math.cos(rad)),
                cy + (float)(r * Math.sin(rad)),
                linePaint);
    }

    private void drawDots(Canvas canvas, float cx, float cy, float r) {
        synchronized (this) {
            for (RadarDot dot : btBlips)      drawDot(canvas, cx, cy, r, dot, btPaint,      5f);
            for (RadarDot dot : safeHerUsers) drawDot(canvas, cx, cy, r, dot, safeHerPaint, 9f);
            for (RadarDot dot : contactDots)  drawDot(canvas, cx, cy, r, dot, contactPaint, 11f);
        }
    }

    private void drawDot(Canvas canvas, float cx, float cy, float r,
                         RadarDot dot, Paint paint, float radius) {
        // Convert distance + bearing to canvas XY
        // bearing 0 = North = up on screen = negative Y
        double capped = Math.min(dot.distanceMeters, RADAR_RANGE_M);
        float frac = (float)(capped / RADAR_RANGE_M);
        double bearRad = Math.toRadians(dot.bearingDeg - 90); // rotate: 0° = top
        float dx = (float)(r * frac * Math.cos(bearRad));
        float dy = (float)(r * frac * Math.sin(bearRad));
        float x = cx + dx, y = cy + dy;

        // Glow ring
        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(paint.getColor());
        glowPaint.setAlpha(60);
        glowPaint.setStrokeWidth(3f);
        canvas.drawCircle(x, y, radius + 5f, glowPaint);

        // Filled dot
        canvas.drawCircle(x, y, radius, paint);

        // Label
        canvas.drawText(dot.label, x + radius + 4, y - 4, labelPaint);
        canvas.drawText(dot.sublabel, x + radius + 4, y + 16, subLabelPaint);
    }

    private void drawMe(Canvas canvas, float cx, float cy) {
        // Pulse ring
        canvas.drawCircle(cx, cy, 14f + mePulse, mePulsePaint);
        // Core dot
        canvas.drawCircle(cx, cy, 10f, meDotPaint);
        // "ME" label
        labelPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ME", cx, cy - 16f, labelPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawLegend(Canvas canvas, float w, float h) {
        float x = 16f, y = h - 90f;
        float sz = 10f;
        float gap = 26f;

        drawLegendItem(canvas, x, y,       sz, Color.parseColor("#34D399"), "You");
        drawLegendItem(canvas, x, y+gap,   sz, Color.parseColor("#FF2D55"), "Contact");
        drawLegendItem(canvas, x, y+gap*2, sz, Color.parseColor("#3B82F6"), "BT Device");
        drawLegendItem(canvas, x, y+gap*3, sz, Color.parseColor("#A855F7"), "SafeHer User");
    }

    private void drawLegendItem(Canvas canvas, float x, float y, float sz, int color, String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        canvas.drawCircle(x + sz, y, sz, p);
        subLabelPaint.setColor(Color.parseColor("#B0AEC8"));
        canvas.drawText(text, x + sz * 2 + 8f, y + 5f, subLabelPaint);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        // Square view
        int size = Math.min(
                MeasureSpec.getSize(wSpec),
                MeasureSpec.getSize(hSpec));
        setMeasuredDimension(size, size);
    }
}
