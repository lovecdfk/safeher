package com.safeher.app;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * CircleGestureDetector  —  v2 rewrite
 *
 * Why v1 didn't work:
 *  • Motion centroid averaged ALL moving pixels — face/background dragged it off
 *    the finger, producing a random-walk path that never traced a real circle.
 *  • Raw normalised (0-1) coords ignore portrait aspect ratio (~9:16).  A circle
 *    drawn by the user is a tall ellipse in those units → Kåsa fit always failed.
 *  • Arc-coverage bins had no ordering check → random scatter filled 360° bins.
 *  • No inter-frame jump filter → one bad frame poisoned the whole path.
 *
 * v2 fixes:
 *  1. LARGEST-BLOB centroid: connected-component flood-fill on the motion mask,
 *     centroid of the biggest blob only.  Ignores background/face blobs.
 *  2. ASPECT-RATIO correction: all coords mapped to square space
 *     (divide by the shorter frame dimension), so a physical circle is a circle
 *     in the math, regardless of whether the phone is portrait or landscape.
 *  3. JUMP FILTER: if centroid jumps > MAX_JUMP_FRAC in one frame → tracking
 *     error, point skipped.  Path survives; just that one bad sample is dropped.
 *  4. ORDERED ARC (unwrapped angle sweep): walk path in time order, unwrap
 *     angular changes, take |total sweep|.  Random scatter winds back → ~0°.
 *     A clean circle sweep → ~360°.  This is the key reliability fix.
 *  5. Gradual path decay: path is NOT cleared on a missed frame; it slowly ages
 *     out over MAX_MISS_FRAMES, so momentary tracking gaps don't restart the user.
 */
public class CircleGestureDetector {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private static final int   PATH_BUFFER_SIZE   = 90;   // ~3.6 s @ 25fps
    private static final float MIN_ARC_DEGREES    = 260f; // unwrapped sweep needed
    private static final float MAX_RESIDUAL_RATIO = 0.32f;
    private static final float MIN_RADIUS_FRAC    = 0.07f; // fraction of short side
    private static final float MAX_RADIUS_FRAC    = 0.60f;
    private static final int   MOTION_THRESH      = 18;
    private static final int   DS                 = 4;     // downsample factor
    private static final int   MIN_BLOB_PX        = 25;    // min downsampled pixels
    private static final int   MIN_FIT_POINTS     = 16;
    private static final float MAX_JUMP_FRAC      = 0.20f; // fraction of short side
    private static final int   MAX_MISS_FRAMES    = 10;

    // ── State ─────────────────────────────────────────────────────────────────
    private byte[] prevLuma  = null;
    private int    prevW     = 0, prevH = 0;
    private int    missCount = 0;

    private final List<float[]> path = new ArrayList<>(); // square-normalised coords

    // ── Public read-outs ──────────────────────────────────────────────────────
    public float   lastCx          = 0.5f;
    public float   lastCy          = 0.5f;
    public float   lastRadius      = 0f;
    public float   lastArcCoverage = 0f;
    public float   lastResidual    = 0f;
    public int     pathSize        = 0;
    public float[] currentPoint    = null; // raw downsampled-pixel centroid

    // ── Main entry point ──────────────────────────────────────────────────────

    public boolean processFrame(Image img) {
        byte[] luma = extractLuma(img);
        if (luma == null) return false;

        int W = img.getWidth()  / DS;
        int H = img.getHeight() / DS;
        float shortSide = Math.min(W, H);

        boolean detected = false;

        if (prevLuma != null && prevW == W && prevH == H) {
            float[] blob = largestMotionBlobCentroid(luma, prevLuma, W, H);

            if (blob != null) {
                currentPoint = blob;
                missCount    = 0;

                // Square-normalised: divide raw pixel coords by the shorter dimension
                float nx = blob[0] / shortSide;
                float ny = blob[1] / shortSide;

                // Jump filter
                boolean accept = true;
                if (!path.isEmpty()) {
                    float[] last = path.get(path.size() - 1);
                    float dx = nx - last[0], dy = ny - last[1];
                    if (dx*dx + dy*dy > MAX_JUMP_FRAC * MAX_JUMP_FRAC) {
                        accept = false; // big jump → tracking glitch, skip point
                    }
                }

                if (accept) {
                    path.add(new float[]{nx, ny});
                    if (path.size() > PATH_BUFFER_SIZE) path.remove(0);
                }

            } else {
                currentPoint = null;
                missCount++;
                if (missCount >= MAX_MISS_FRAMES) {
                    path.clear();
                    missCount = 0;
                }
            }

            pathSize = path.size();
            if (path.size() >= MIN_FIT_POINTS) {
                detected = fitAndCheck();
            }
        }

        prevLuma = luma;
        prevW    = W;
        prevH    = H;
        return detected;
    }

    public void reset() {
        path.clear();
        prevLuma        = null;
        missCount       = 0;
        lastArcCoverage = 0;
        lastResidual    = 0;
        lastRadius      = 0;
        currentPoint    = null;
        pathSize        = 0;
    }

    // ── Luma extraction ───────────────────────────────────────────────────────

    private byte[] extractLuma(Image img) {
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf    = plane.getBuffer();
            int W         = img.getWidth();
            int H         = img.getHeight();
            int rowStride = plane.getRowStride();
            int pixStride = plane.getPixelStride();
            int dW = W / DS, dH = H / DS;
            byte[] out = new byte[dW * dH];
            for (int y = 0; y < dH; y++) {
                for (int x = 0; x < dW; x++) {
                    int si = (y * DS) * rowStride + (x * DS) * pixStride;
                    out[y * dW + x] = (si < buf.limit()) ? buf.get(si) : 0;
                }
            }
            return out;
        } catch (Exception e) { return null; }
    }

    // ── Largest-blob centroid ─────────────────────────────────────────────────

    /**
     * Build binary motion mask, then iterative flood-fill to find all connected
     * blobs. Return centroid of the largest blob (= the primary moving object).
     * Ignores sensor noise and small background motions.
     */
    private float[] largestMotionBlobCentroid(byte[] curr, byte[] prev, int W, int H) {
        boolean[] mask    = new boolean[W * H];
        boolean[] visited = new boolean[W * H];

        for (int i = 0; i < W * H; i++) {
            mask[i] = Math.abs((curr[i] & 0xFF) - (prev[i] & 0xFF)) >= MOTION_THRESH;
        }

        int   bestSize = 0;
        float bestSumX = 0, bestSumY = 0;
        int[] stack = new int[W * H];

        for (int start = 0; start < W * H; start++) {
            if (!mask[start] || visited[start]) continue;

            int   top      = 0;
            int   blobSize = 0;
            float sumX     = 0, sumY = 0;
            stack[top++]   = start;
            visited[start] = true;

            while (top > 0) {
                int idx = stack[--top];
                int px  = idx % W;
                int py  = idx / W;
                blobSize++;
                sumX += px;
                sumY += py;

                // 4-connected neighbours
                if (px > 0)     top = push(idx-1, mask, visited, stack, top);
                if (px < W-1)   top = push(idx+1, mask, visited, stack, top);
                if (py > 0)     top = push(idx-W, mask, visited, stack, top);
                if (py < H-1)   top = push(idx+W, mask, visited, stack, top);
            }

            if (blobSize > bestSize) {
                bestSize = blobSize;
                bestSumX = sumX;
                bestSumY = sumY;
            }
        }

        if (bestSize < MIN_BLOB_PX) return null;
        return new float[]{ bestSumX / bestSize, bestSumY / bestSize };
    }

    private int push(int idx, boolean[] mask, boolean[] visited, int[] stack, int top) {
        if (!mask[idx] || visited[idx]) return top;
        visited[idx] = true;
        stack[top]   = idx;
        return top + 1;
    }

    // ── Circle fit + ordered arc check ───────────────────────────────────────

    private boolean fitAndCheck() {
        int n = path.size();

        // Kåsa algebraic fit: minimise Σ(x²+y²+Dx+Ey+F)²
        double sX=0, sY=0, sX2=0, sY2=0, sXY=0, sZ=0, sZX=0, sZY=0;
        for (float[] p : path) {
            double x = p[0], y = p[1], z = x*x + y*y;
            sX+=x; sY+=y; sX2+=x*x; sY2+=y*y;
            sXY+=x*y; sZ+=z; sZX+=z*x; sZY+=z*y;
        }
        double[][] A = { {sX2,sXY,sX}, {sXY,sY2,sY}, {sX,sY,n} };
        double[]   b = { sZX, sZY, sZ };
        double[] sol = solve3x3(A, b);
        if (sol == null) return false;

        double cx = -sol[0]/2.0, cy = -sol[1]/2.0;
        double r2 = cx*cx + cy*cy - sol[2];
        if (r2 <= 0) return false;
        double r = Math.sqrt(r2);

        // Radius check (in square-normalised space; 1.0 = shortSide)
        boolean radiusOk = r >= MIN_RADIUS_FRAC && r <= MAX_RADIUS_FRAC;

        // RMS residual
        double rms = 0;
        for (float[] p : path) {
            double dx = p[0]-cx, dy = p[1]-cy;
            double err = Math.sqrt(dx*dx+dy*dy) - r;
            rms += err*err;
        }
        rms = Math.sqrt(rms / n);
        float residualRatio = (float)(rms / Math.max(r, 1e-6));
        boolean fitOk = residualRatio <= MAX_RESIDUAL_RATIO;

        // Ordered arc: unwrap angular displacement along path
        float arcDeg = unwrappedSweepDegrees(cx, cy);
        boolean arcOk = arcDeg >= MIN_ARC_DEGREES;

        // Update public fields
        lastCx          = (float) cx;
        lastCy          = (float) cy;
        lastRadius      = (float) r;
        lastArcCoverage = arcDeg;
        lastResidual    = residualRatio;

        return radiusOk && fitOk && arcOk;
    }

    /**
     * Walk path in temporal order, compute unwrapped total angle swept around
     * (cx,cy).  Returns |total| in degrees, capped at 360.
     *
     * A genuine circle trace → ~360°.
     * Random back-and-forth scatter → much less (winds cancel).
     */
    private float unwrappedSweepDegrees(double cx, double cy) {
        if (path.size() < 2) return 0f;
        double total = 0;
        double prevAngle = Math.atan2(path.get(0)[1] - cy, path.get(0)[0] - cx);
        for (int i = 1; i < path.size(); i++) {
            float[] p = path.get(i);
            double angle = Math.atan2(p[1] - cy, p[0] - cx);
            double delta = angle - prevAngle;
            // wrap to (-π, π]
            while (delta >  Math.PI) delta -= 2 * Math.PI;
            while (delta < -Math.PI) delta += 2 * Math.PI;
            total += delta;
            prevAngle = angle;
        }
        return (float) Math.min(Math.toDegrees(Math.abs(total)), 360.0);
    }

    // ── 3×3 Gauss elimination ─────────────────────────────────────────────────

    private double[] solve3x3(double[][] A, double[] b) {
        int n = 3;
        double[][] aug = new double[n][n+1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, n);
            aug[i][n] = b[i];
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col+1; row < n; row++)
                if (Math.abs(aug[row][col]) > Math.abs(aug[pivot][col])) pivot = row;
            double[] tmp = aug[col]; aug[col] = aug[pivot]; aug[pivot] = tmp;
            if (Math.abs(aug[col][col]) < 1e-12) return null;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double f = aug[row][col] / aug[col][col];
                for (int j = col; j <= n; j++) aug[row][j] -= f * aug[col][j];
            }
        }
        double[] sol = new double[n];
        for (int i = 0; i < n; i++) sol[i] = aug[i][n] / aug[i][i];
        return sol;
    }
}
