package com.safeher.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * PinchOverlayView
 *
 * Draws real-time feedback for the pinch-out (spread) SOS gesture:
 *  • Two finger dots with a line between them
 *  • A pulsing spread-indicator arc showing how far the pinch has gone
 *  • A percentage label showing pinch progress toward the threshold
 *  • Success flash when the gesture is confirmed
 *  • Idle guide when no touch is active
 */
public class PinchOverlayView extends View {

    // Finger positions (view pixels), -1 = not active
    private float f1x = -1, f1y = -1;
    private float f2x = -1, f2y = -1;

    // Progress 0.0 → 1.0 (how far toward SOS threshold)
    private float progress = 0f;

    private boolean detected  = false;
    private boolean showGuide = true;
    private float   guidePhase = 0f;

    // Paints
    private final Paint dotPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint successPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PinchOverlayView(Context ctx)                        { super(ctx);     init(); }
    public PinchOverlayView(Context ctx, AttributeSet a)        { super(ctx,a);   init(); }
    public PinchOverlayView(Context ctx, AttributeSet a, int s) { super(ctx,a,s); init(); }

    private void init() {
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.parseColor("#34D399"));

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(Color.parseColor("#4034D399"));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setPathEffect(new DashPathEffect(new float[]{14f, 8f}, 0));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f);
        guidePaint.setColor(Color.parseColor("#44FFFFFF"));

        labelPaint.setTextSize(36f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));

        successPaint.setStyle(Paint.Style.STROKE);
        successPaint.setStrokeWidth(10f);
        successPaint.setColor(Color.parseColor("#34D399"));

        pctPaint.setTextSize(28f);
        pctPaint.setTextAlign(Paint.Align.CENTER);
        pctPaint.setAntiAlias(true);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Update with live touch state. finger1/2 in view pixels; prog 0-1; detected=confirmed */
    public void update(float x1, float y1, float x2, float y2, float prog, boolean det) {
        f1x = x1; f1y = y1;
        f2x = x2; f2y = y2;
        progress  = prog;
        detected  = det;
        showGuide = false;
        invalidate();
    }

    /** Call when touch ends or is cancelled */
    public void clearTouch() {
        f1x = f1y = f2x = f2y = -1;
        progress  = 0f;
        detected  = false;
        showGuide = true;
        invalidate();
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int W = getWidth(), H = getHeight();

        if (showGuide || f1x < 0) {
            drawIdleGuide(canvas, W, H);
            return;
        }

        float cx = (f1x + f2x) / 2f;
        float cy = (f1y + f2y) / 2f;
        float dx = f2x - f1x, dy = f2y - f1y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        // Color: amber while building, green when detected
        int color = detected
                ? Color.parseColor("#34D399")
                : (progress > 0.6f ? Color.parseColor("#F59E0B") : Color.parseColor("#60FFFFFF"));

        // Line between fingers
        linePaint.setColor(Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawLine(f1x, f1y, f2x, f2y, linePaint);

        // Spread arc — drawn as a semicircle centred between the two fingers
        float arcR = dist / 2f;
        if (arcR > 20) {
            arcPaint.setColor(color);
            arcPaint.setAlpha(160);
            arcPaint.setStrokeWidth(6f + 6f * progress);
            float sweep = Math.min(progress * 200f, 200f); // up to 200° sweep
            float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            android.graphics.RectF oval = new android.graphics.RectF(
                    cx - arcR, cy - arcR, cx + arcR, cy + arcR);
            canvas.drawArc(oval, angle + 90, sweep, false, arcPaint);
            canvas.drawArc(oval, angle - 90, -sweep, false, arcPaint);
        }

        // Finger dots
        drawFingerDot(canvas, f1x, f1y, color);
        drawFingerDot(canvas, f2x, f2y, color);

        // Progress label above centre
        if (progress > 0.05f) {
            int pct = (int)(progress * 100);
            pctPaint.setColor(color);
            pctPaint.setAlpha(200);
            canvas.drawText("Spread " + pct + "%", cx, cy - arcR - 20, pctPaint);
        }

        // Success flash
        if (detected) {
            canvas.drawCircle(cx, cy, arcR + 20, successPaint);
            labelPaint.setColor(Color.parseColor("#34D399"));
            canvas.drawText("✓ Pinch Out!", W / 2f, H * 0.15f, labelPaint);
        }
    }

    private void drawFingerDot(Canvas canvas, float x, float y, int color) {
        glowPaint.setColor(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(x, y, 34f, glowPaint);
        dotPaint.setColor(color);
        canvas.drawCircle(x, y, 14f, dotPaint);
        dotPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, 5f, dotPaint);
        dotPaint.setColor(color); // reset
    }

    private void drawIdleGuide(Canvas canvas, int W, int H) {
        float cx = W / 2f, cy = H * 0.42f;
        float r  = Math.min(W, H) * 0.22f;
        guidePhase = (guidePhase + 1.5f) % 360f;
        guidePaint.setPathEffect(new DashPathEffect(new float[]{16f, 10f}, guidePhase));

        // Two finger hint circles
        float gap = r * 0.5f;
        canvas.drawCircle(cx - gap, cy, 22f, guidePaint);
        canvas.drawCircle(cx + gap, cy, 22f, guidePaint);

        // Arrow lines pointing outward
        Paint arr = new Paint(Paint.ANTI_ALIAS_FLAG);
        arr.setStyle(Paint.Style.STROKE);
        arr.setStrokeWidth(3f);
        arr.setColor(Color.parseColor("#55FFFFFF"));
        canvas.drawLine(cx - gap - 24, cy, cx - gap - 54, cy, arr);
        canvas.drawLine(cx + gap + 24, cy, cx + gap + 54, cy, arr);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(Color.parseColor("#66FFFFFF"));
        lp.setTextSize(28f);
        lp.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("🤏  Place two fingers then", cx, cy + r + 30, lp);
        canvas.drawText("spread them apart quickly", cx, cy + r + 60, lp);
    }
}
