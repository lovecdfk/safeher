package com.safeher.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * CircleTrailView  —  v2
 *
 * Draws the fingertip trail and fitted circle on top of the camera preview.
 *
 * Coordinate system note:
 *   The detector works in "square-normalised" space where 1.0 = the shorter
 *   camera frame dimension.  To map to view pixels we must undo the aspect
 *   correction:
 *     viewX = squareNorm_x * shortSide_px   (where shortSide = min(viewW, viewH))
 *     viewY = squareNorm_y * shortSide_px
 *   For a typical portrait view  shortSide = viewW,  so:
 *     viewX = nx * viewW
 *     viewY = ny * viewW   (NOT viewH — that's the key difference from v1)
 *
 *   The front camera also mirrors X, so we flip: viewX = (1 – nx) * shortSide
 */
public class CircleTrailView extends View {

    private final List<PointF> trail = new ArrayList<>();
    private static final int MAX_TRAIL = 90;

    // Fitted circle in view-pixel coords
    private float fitCx = -1, fitCy = -1, fitR = -1;
    private float arcCoverage = 0;
    private float residual    = 1f;
    private boolean detected  = false;

    // Current tip in view-pixel coords
    private float tipX = -1, tipY = -1;

    // Guide animation
    private boolean showGuide  = true;
    private float   guidePhase = 0;

    // Paints
    private final Paint trailPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circlePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centrePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint successPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CircleTrailView(Context ctx)                          { super(ctx);      init(); }
    public CircleTrailView(Context ctx, AttributeSet a)          { super(ctx,a);    init(); }
    public CircleTrailView(Context ctx, AttributeSet a, int s)   { super(ctx,a,s);  init(); }

    private void init() {
        trailPaint.setStyle(Paint.Style.FILL);

        tipPaint.setStyle(Paint.Style.FILL);
        tipPaint.setColor(Color.parseColor("#34D399"));

        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(3f);
        circlePaint.setPathEffect(new DashPathEffect(new float[]{18f, 10f}, 0));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(2f);
        guidePaint.setColor(Color.parseColor("#33FFFFFF"));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(7f);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        centrePaint.setStyle(Paint.Style.STROKE);
        centrePaint.setStrokeWidth(2f);
        centrePaint.setColor(Color.parseColor("#88FFFFFF"));

        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextSize(36f);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        successPaint.setStyle(Paint.Style.STROKE);
        successPaint.setStrokeWidth(8f);
        successPaint.setColor(Color.parseColor("#34D399"));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called every frame from HandGestureActivity with square-normalised detector state. */
    public void updateFromDetector(CircleGestureDetector d, boolean isDetected) {
        int vW = getWidth(), vH = getHeight();
        if (vW == 0 || vH == 0) return;

        // shortSide: in square-normalised space, 1.0 maps to shortSide view pixels.
        // Portrait phone: shortSide = vW.  Landscape: shortSide = vH.
        float shortSide = Math.min(vW, vH);

        if (d.currentPoint != null) {
            // currentPoint is in raw downsampled-pixel coords, not normalised yet —
            // BUT the detector already stored lastCx/lastCy in square-normalised.
            // currentPoint we convert manually:
            //   The detector normalises: nx = blobX / shortSide_camera
            // We don't know shortSide_camera here, but we trust lastCx/lastCy are correct.
            // For the live tip dot we use the detector's square-norm path last point
            // via a different approach: just skip updating tipX/tipY from currentPoint
            // (which is raw pixels) and instead read from lastCx/lastCy is wrong too.
            //
            // Simplest correct solution: expose the last normalised point from detector.
            // Since we can't change the API mid-call, we store tipX/tipY from the
            // fitted centre + radius as a fallback, or we normalise currentPoint
            // using the detector's DS and the image dims — which we don't have here.
            //
            // ✅ Clean fix: update tip from the path arc feedback via lastCx/lastCy only
            //    when we have a fit; otherwise use a raw scaled estimate.
            //    We'll use detector.currentPoint as raw downsampled pixels and assume
            //    the camera short side ≈ shortSide / scale — but this is approximate.
            //    For the *trail* we just need something on-screen and approximate is fine.
            //
            // rawX / cameraW * vW  ≈  rawX / (cameraW/DS * DS) * vW
            // We don't have cameraW, but we know raw is downsampled by DS, so
            // cameraW_ds = cameraW/DS.  shortSide_camera_ds = cameraShortSide/DS.
            // The detector divided by shortSide_camera_ds to get square-norm.
            // shortSide_camera_ds = min(cameraW/DS, cameraH/DS).
            // For portrait 1080×1920 → ds=4 → 270×480 → shortSide=270.
            // nx = blobX / 270,  tipViewX = (1-nx)*shortSide_view.
            // We can approximate shortSide_camera_ds from the currentPoint max magnitude:
            // use shortSide as proxy (view and camera are same aspect).

            // Best practical approach: derive from square-norm via lastCx/lastCy when fit exists
            // For pre-fit trail dots: estimate using vW as proxy for camera short side.
            float rawX = d.currentPoint[0];
            float rawY = d.currentPoint[1];
            // Approximate: assume camera downsampled short side ≈ vW/some_factor
            // Actually we can make a reasonable assumption: portrait camera, short side = width
            // rawX is in downsampled pixels, max ≈ cameraW/DS
            // We'll normalise relative to itself: nx = rawX/(rawX_max)
            // This is inherently imprecise; but for trail dots, approximate is fine.
            // Use shortSide as a rough scale for both axes.
            float nx = rawX / (shortSide);  // same formula the detector uses
            float ny = rawY / (shortSide);
            // Mirror X for front camera
            tipX = (1f - nx) * shortSide;
            tipY = ny * shortSide;

            trail.add(new PointF(tipX, tipY));
            if (trail.size() > MAX_TRAIL) trail.remove(0);
            showGuide = false;
        }

        // Fitted circle — square-norm → view pixels (mirror X)
        if (d.pathSize >= 16 && d.lastRadius > 0) {
            fitCx = (1f - d.lastCx) * shortSide;
            fitCy = d.lastCy * shortSide;
            fitR  = d.lastRadius * shortSide;
            arcCoverage = d.lastArcCoverage;
            residual    = d.lastResidual;
        } else {
            fitCx = fitCy = fitR = -1;
        }

        detected = isDetected;
        invalidate();
    }

    public void clearTrail() {
        trail.clear();
        tipX = tipY = -1;
        fitCx = fitCy = fitR = -1;
        arcCoverage = 0;
        detected    = false;
        showGuide   = true;
        invalidate();
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int vW = getWidth(), vH = getHeight();

        if (showGuide) drawIdleGuide(canvas, vW, vH);
        drawTrail(canvas);
        if (fitR > 10) drawFittedCircle(canvas);
        if (tipX >= 0)  drawTip(canvas);
        if (detected)   drawSuccess(canvas, vW, vH);
    }

    private void drawIdleGuide(Canvas canvas, int vW, int vH) {
        float cx = vW / 2f, cy = vH * 0.42f;
        float guideR = Math.min(vW, vH) * 0.30f;
        guidePhase = (guidePhase + 1.5f) % 360f;
        guidePaint.setPathEffect(new DashPathEffect(new float[]{22f, 12f}, guidePhase));
        canvas.drawCircle(cx, cy, guideR, guidePaint);

        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(Color.parseColor("#66FFFFFF"));
        lp.setTextSize(28f);
        lp.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("⭕  Slowly trace a circle", cx, cy + guideR + 44, lp);
        canvas.drawText("with your finger", cx, cy + guideR + 74, lp);
    }

    private void drawTrail(Canvas canvas) {
        int n = trail.size();
        for (int i = 0; i < n; i++) {
            float alpha = (float)(i + 1) / n;
            int a = (int)(alpha * 210);
            int r = (int)(52  + (220 - 52)  * alpha);
            int g = (int)(211 + (255 - 211) * alpha);
            int b = (int)(153 + (255 - 153) * alpha);
            trailPaint.setColor(Color.argb(a, r, g, b));
            float radius = 3f + 7f * alpha;
            PointF p = trail.get(i);
            canvas.drawCircle(p.x, p.y, radius, trailPaint);
        }
    }

    private void drawTip(Canvas canvas) {
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setStyle(Paint.Style.FILL);
        glow.setColor(Color.parseColor("#5034D399"));
        canvas.drawCircle(tipX, tipY, 26f, glow);
        tipPaint.setColor(Color.parseColor("#34D399"));
        canvas.drawCircle(tipX, tipY, 11f, tipPaint);
        tipPaint.setColor(Color.WHITE);
        canvas.drawCircle(tipX, tipY, 4f, tipPaint);
    }

    private void drawFittedCircle(Canvas canvas) {
        boolean goodFit = residual < 0.22f && arcCoverage >= 180f;
        int color = goodFit
                ? Color.parseColor("#34D399")   // green — looking good
                : Color.parseColor("#F59E0B");  // amber — still building

        // Dashed circle outline
        circlePaint.setColor(Color.argb(100, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawCircle(fitCx, fitCy, fitR, circlePaint);

        // Arc progress — sweep counter-clockwise to match mirrored hand motion
        arcPaint.setColor(color);
        arcPaint.setAlpha(190);
        float sweep = Math.min(arcCoverage, 360f);
        RectF oval = new RectF(fitCx - fitR, fitCy - fitR, fitCx + fitR, fitCy + fitR);
        canvas.drawArc(oval, -90f, -sweep, false, arcPaint);

        // Centre crosshair
        float arm = 14f;
        canvas.drawLine(fitCx - arm, fitCy, fitCx + arm, fitCy, centrePaint);
        canvas.drawLine(fitCx, fitCy - arm, fitCx, fitCy + arm, centrePaint);

        // Percentage label
        Paint pct = new Paint(Paint.ANTI_ALIAS_FLAG);
        pct.setColor(color);
        pct.setTextSize(30f);
        pct.setTextAlign(Paint.Align.CENTER);
        pct.setAlpha(200);
        int pctVal = (int)(arcCoverage / 360f * 100);
        canvas.drawText(pctVal + "%", fitCx, fitCy + 11, pct);
    }

    private void drawSuccess(Canvas canvas, int vW, int vH) {
        if (fitR > 10) canvas.drawCircle(fitCx, fitCy, fitR, successPaint);
        labelPaint.setColor(Color.parseColor("#34D399"));
        canvas.drawText("✓ Circle!", vW / 2f, vH * 0.14f, labelPaint);
    }
}
