package de.spiasm.bierpong;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Bierpong-Tisch in der Ich-Perspektive: Zeichnen, Wurf-Animation und Wisch-Steuerung. */
public class PongView extends View {
    public interface ThrowListener {
        void onThrow(double speed, double theta, double x0);

        void onWeakThrow();
    }

    public interface DoneListener {
        /** cup = getroffener Becher oder -1; endX/endZ = wo der Ball zuletzt war */
        void onDone(int cup, double endX, double endZ);
    }

    // Kamera
    private static final double CAM_Y = 0.75, CAM_Z = -0.35;
    private static final double PITCH = Math.toRadians(16);
    private static final double SIN_P = Math.sin(PITCH), COS_P = Math.cos(PITCH);

    public boolean[] cups = new boolean[Physics.CUPS];
    public boolean inputEnabled;
    public String title = "", sub = "";
    public ThrowListener throwListener;
    public Sfx sfx;
    /** Anzahl wandernder Balken (0-2) */
    public int bars;
    /** Wie viele Becher man schon trinken musste (Modus Realistisch), sonst 0 */
    public int drunk;
    /** Wurde der letzte Ball von einem Balken geblockt? */
    public boolean lastBlocked;
    private final long clockStart = SystemClock.uptimeMillis();

    /** Spieluhr in Sekunden (gerundet, damit beide Handys dieselbe Zahl haben). */
    public double clock() {
        double t = ((SystemClock.uptimeMillis() - clockStart) / 1000.0) % 10000.0;
        return Math.round(t * 10000) / 10000.0;
    }

    private double barTime() {
        if (sim != null) return sim.phase + sim.t;
        return (SystemClock.uptimeMillis() - clockStart) / 1000.0 % 10000.0;
    }

    private float F, horizon;
    private final float[] p = new float[3];
    private final float[] q = new float[3];

    private Physics.Sim sim;
    private DoneListener done;
    private long lastFrame;
    private double acc;
    private long finishAt;
    private int hitCup = -1;
    private long hitTime;
    private long lastRimSound;

    private static class Drop {
        double x, y, z, vx, vy, vz;
    }

    private final List<Drop> drops = new ArrayList<>();
    private final Random rnd = new Random();

    // Finger
    private boolean holding;
    private float fx, fy, downX;
    private final float[] hx = new float[32], hy = new float[32];
    private final long[] ht = new long[32];
    private int hn;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private LinearGradient wallGrad, tableGrad;
    private int gradH;

    public PongView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
        text.setFakeBoldText(true);
        text.setShadowLayer(6, 0, 2, 0xCC000000);
        for (int i = 0; i < cups.length; i++) cups[i] = true;
    }

    private String flashText;
    private long flashUntil;

    public boolean busy() {
        return sim != null;
    }

    /** Große Meldung kurz in der Mitte einblenden. */
    public void flash(String s) {
        flashText = s;
        flashUntil = SystemClock.uptimeMillis() + 1600;
        invalidate();
    }

    private void drawFlash(Canvas c, float w, float h, long now) {
        if (flashText == null || now > flashUntil) return;
        float left = (flashUntil - now) / 1600f;
        float a = Math.min(1f, left * 3f);
        text.setTextSize(w * 0.1f);
        text.setColor(0xFFFFD54F);
        text.setAlpha(Math.round(255 * a));
        c.drawText(flashText, w / 2, h * 0.62f, text);
        text.setColor(0xFFFFFFFF);
        postInvalidateOnAnimation();
    }

    /** Wurf abspielen. Die Becher am anderen Ende sind {@link #cups}. */
    public void animateThrow(double speed, double theta, double x0, double phase, DoneListener listener) {
        sim = new Physics.Sim(speed, theta, x0, cups, bars, phase);
        lastBlocked = false;
        done = listener;
        acc = 0;
        lastFrame = SystemClock.uptimeMillis();
        finishAt = 0;
        hitCup = -1;
        holding = false;
        if (sfx != null) sfx.play(Sfx.THROW);
        invalidate();
    }

    // ------------------------------------------------------------ Projektion

    /** Weltpunkt -> Bildschirm. out = {x, y, Tiefe}. */
    private void proj(double x, double y, double z, float[] out) {
        double dy = y - CAM_Y, dz = z - CAM_Z;
        double zc = -dy * SIN_P + dz * COS_P;
        double yc = dy * COS_P + dz * SIN_P;
        if (zc < 0.05) zc = 0.05;
        out[0] = (float) (getWidth() / 2.0 + F * x / zc);
        out[1] = (float) (horizon - F * yc / zc);
        out[2] = (float) zc;
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        F = w * 2.3f;
        horizon = h * 0.47f;
        long now = SystemClock.uptimeMillis();

        advance(now);

        // Betrunken: Bild schwankt
        float sway = 0;
        c.save();
        if (drunk > 0) {
            double t = (now - clockStart) / 1000.0;
            sway = (float) Math.sin(t * 0.9);
            float rot = drunk * 0.9f * sway;
            float dx = (float) (drunk * w * 0.006 * Math.sin(t * 0.7 + 1));
            float dy = (float) (drunk * w * 0.004 * Math.sin(t * 1.3));
            float sc = 1f + drunk * 0.012f;
            c.translate(dx, dy);
            c.rotate(rot, w / 2, h / 2);
            c.scale(sc, sc, w / 2, h / 2);
        }

        drawRoom(c, w, h);
        drawTable(c);

        // Becher, Balken und Ball nach Tiefe sortiert (hinten zuerst)
        double barT = barTime();
        int nCups = Physics.CUPS;
        int nItems = nCups + bars + 1;
        double[] depth = new double[nItems];
        Integer[] order = new Integer[nItems];
        for (int i = 0; i < nItems; i++) {
            order[i] = i;
            if (i < nCups) depth[i] = Physics.CUP_POS[i][1];
            else if (i < nCups + bars) depth[i] = Physics.BAR_Z[i - nCups] + Physics.BAR_D;
            else depth[i] = sim != null ? sim.z : -9;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(depth[b], depth[a]));
        for (int i : order) {
            if (i < nCups) {
                float alpha = 1f;
                if (i == hitCup) alpha = Math.max(0f, 1f - (now - hitTime) / 700f);
                if (cups[i] && alpha > 0) drawCup(c, Physics.CUP_POS[i][0], Physics.CUP_POS[i][1], alpha, i == hitCup);
            } else if (i < nCups + bars) {
                int b = i - nCups;
                drawBar(c, Physics.barX(b, barT), Physics.BAR_Z[b]);
            } else if (sim != null && sim.state != Physics.IN) {
                drawBall(c, sim.x, sim.y, sim.z);
            }
        }

        drawDrops(c, now);
        c.restore();
        if (drunk > 0) drawVignette(c, w, h);
        drawHand(c, w, h);
        drawHud(c, w, h);
        drawFlash(c, w, h, now);

        if (sim != null || !drops.isEmpty() || holding || bars > 0 || drunk > 0) postInvalidateOnAnimation();
    }

    private void advance(long now) {
        if (sim == null) return;
        double dt = Math.min(0.05, (now - lastFrame) / 1000.0);
        lastFrame = now;
        if (sim.state == Physics.RUN) {
            acc += dt;
            while (acc >= Physics.DT && sim.state == Physics.RUN) {
                sim.step();
                acc -= Physics.DT;
                if (sim.event == Physics.EV_TABLE && sfx != null) sfx.play(Sfx.TABLE);
                if (sim.event == Physics.EV_BAR) lastBlocked = true;
                if (sim.event == Physics.EV_BAR && sfx != null && now - lastRimSound > 90) {
                    lastRimSound = now;
                    sfx.play(Sfx.BAR);
                }
                if (sim.event == Physics.EV_RIM && sfx != null && now - lastRimSound > 90) {
                    lastRimSound = now;
                    sfx.play(Sfx.RIM);
                }
            }
            if (sim.state == Physics.IN) {
                hitCup = sim.cup;
                hitTime = now;
                spawnSplash(Physics.CUP_POS[sim.cup][0], Physics.CUP_POS[sim.cup][1]);
                if (sfx != null) sfx.play(Sfx.PLOP);
                finishAt = now + 900;
            } else if (sim.state == Physics.MISS) {
                finishAt = now + 350;
            }
        }
        if (sim.state != Physics.RUN && now >= finishAt) {
            Physics.Sim s = sim;
            DoneListener l = done;
            sim = null;
            done = null;
            int cup = s.state == Physics.IN ? s.cup : -1;
            hitCup = -1;
            if (l != null) l.onDone(cup, s.x, s.z);
        }
    }

    private void drawRoom(Canvas c, float w, float h) {
        proj(0, -0.76, 3.3, p);
        float floorY = p[1];
        if (wallGrad == null || gradH != (int) h) {
            gradH = (int) h;
            wallGrad = new LinearGradient(0, 0, 0, floorY, 0xFF1A1030, 0xFF3A1F3D, Shader.TileMode.CLAMP);
            tableGrad = new LinearGradient(0, floorY * 0.9f, 0, h, 0xFF4A2C1A, 0xFF8A5A34, Shader.TileMode.CLAMP);
        }
        fill.setShader(wallGrad);
        c.drawRect(0, 0, w, floorY, fill);
        fill.setShader(null);
        fill.setColor(0xFF1C1414);
        c.drawRect(0, floorY, w, h, fill);

        // Lichterkette
        stroke.setColor(0xFF222222);
        stroke.setStrokeWidth(w * 0.004f);
        path.reset();
        path.moveTo(-w * 0.05f, h * 0.06f);
        path.quadTo(w * 0.5f, h * 0.2f, w * 1.05f, h * 0.06f);
        c.drawPath(path, stroke);
        int[] cols = {0xFFFFD54F, 0xFFFF5252, 0xFF69F0AE, 0xFF40C4FF, 0xFFE040FB};
        for (int i = 0; i <= 12; i++) {
            float t = i / 12f;
            float x = -w * 0.05f + t * w * 1.1f;
            float y = (1 - t) * (1 - t) * h * 0.06f + 2 * (1 - t) * t * h * 0.2f + t * t * h * 0.06f;
            int col = cols[i % cols.length];
            fill.setColor((col & 0x00FFFFFF) | 0x33000000);
            c.drawCircle(x, y + w * 0.02f, w * 0.035f, fill);
            fill.setColor(col);
            c.drawCircle(x, y + w * 0.02f, w * 0.012f, fill);
        }

        // Leuchtschild
        text.setTextSize(w * 0.09f);
        text.setColor(0x55FF4081);
        text.setShadowLayer(w * 0.04f, 0, 0, 0xFFFF4081);
        c.drawText("BIERPONG", w / 2, h * 0.29f, text);
        text.setColor(0xFFFF80AB);
        text.setShadowLayer(w * 0.015f, 0, 0, 0xFFFF4081);
        c.drawText("BIERPONG", w / 2, h * 0.29f, text);
        text.setShadowLayer(6, 0, 2, 0xCC000000);
        text.setColor(0xFFFFFFFF);
    }

    private void drawTable(Canvas c) {
        double hw = Physics.TABLE_W / 2, L = Physics.TABLE_L;
        // Tischbeine-Andeutung / Kante hinten
        proj(-hw, 0, L, p);
        proj(hw, 0, L, q);
        float bx0 = p[0], by0 = p[1], bx1 = q[0];
        proj(-hw, -0.06, L, q);
        float thick = q[1] - by0;
        fill.setColor(0xFF2E1B10);
        c.drawRect(bx0, by0, bx1, by0 + thick, fill);

        path.reset();
        proj(-hw, 0, L, p);
        path.moveTo(p[0], p[1]);
        proj(hw, 0, L, p);
        path.lineTo(p[0], p[1]);
        proj(hw, 0, 0, p);
        path.lineTo(p[0], p[1]);
        proj(-hw, 0, 0, p);
        path.lineTo(p[0], p[1]);
        path.close();
        fill.setShader(tableGrad);
        c.drawPath(path, fill);
        fill.setShader(null);

        // Holzmaserung
        stroke.setColor(0x22000000);
        for (int i = 1; i < 7; i++) {
            double x = -hw + i * Physics.TABLE_W / 7;
            proj(x, 0, L, p);
            proj(x, 0, 0, q);
            stroke.setStrokeWidth(getWidth() * 0.003f);
            c.drawLine(p[0], p[1], q[0], q[1], stroke);
        }
        // weiße Randlinien und Mittellinie
        stroke.setColor(0xDDFFFFFF);
        double e = hw - 0.02;
        lineW(c, -e, 0.02, -e, L - 0.02);
        lineW(c, e, 0.02, e, L - 0.02);
        lineW(c, -e, L - 0.02, e, L - 0.02);
        lineW(c, -e, L / 2, e, L / 2);
        lineW(c, 0, 0.02, 0, L / 2);
    }

    private void lineW(Canvas c, double x0, double z0, double x1, double z1) {
        proj(x0, 0, z0, p);
        proj(x1, 0, z1, q);
        float depth = Math.min(p[2], q[2]);
        stroke.setStrokeWidth(Math.max(1.5f, (float) (0.012 * F / Math.max(depth, 0.6))));
        // Linie in Stücken zeichnen, damit sie zur Perspektive passt
        int n = 12;
        for (int i = 0; i < n; i++) {
            double a = i / (double) n, b = (i + 1) / (double) n;
            proj(x0 + (x1 - x0) * a, 0, z0 + (z1 - z0) * a, p);
            proj(x0 + (x1 - x0) * b, 0, z0 + (z1 - z0) * b, q);
            stroke.setStrokeWidth(Math.max(1.5f, (float) (0.012 * F / p[2])));
            c.drawLine(p[0], p[1], q[0], q[1], stroke);
        }
    }

    private void drawCup(Canvas c, double cx, double cz, float alpha, boolean hit) {
        double H = Physics.CUP_H;
        proj(cx, H, cz, p);
        float tx = p[0], ty = p[1], tz = p[2];
        float rx = (float) (Physics.RIM_R * F / tz);
        proj(cx, H, cz - Physics.RIM_R, q);
        float yNear = q[1];
        proj(cx, H, cz + Physics.RIM_R, q);
        float ry = Math.abs(yNear - q[1]) / 2;
        proj(cx, 0, cz, q);
        float bx = q[0], by = q[1];
        float brx = (float) (Physics.BOTTOM_R * F / q[2]);
        float bry = ry * brx / rx;
        int a = Math.round(255 * alpha);

        // Becherkörper
        path.reset();
        path.moveTo(tx - rx, ty);
        path.lineTo(bx - brx, by);
        r.set(bx - brx, by - bry, bx + brx, by + bry);
        path.arcTo(r, 180, -180);
        path.lineTo(tx + rx, ty);
        path.close();
        fill.setShader(new LinearGradient(tx - rx, 0, tx + rx, 0,
                new int[]{0xFF7A0E0E, 0xFFD32F2F, 0xFFFF6659, 0xFFC62828, 0xFF6D0B0B},
                new float[]{0f, 0.35f, 0.5f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        fill.setAlpha(a);
        c.drawPath(path, fill);
        fill.setShader(null);
        // Rillen
        stroke.setColor(0x55FFFFFF);
        stroke.setAlpha(Math.round(85 * alpha));
        stroke.setStrokeWidth(Math.max(1f, rx * 0.04f));
        for (float f : new float[]{0.18f, 0.32f}) {
            float yy = ty + (by - ty) * f;
            float xx = rx + (brx - rx) * f;
            r.set(tx - xx, yy - ry * 0.8f, tx + xx, yy + ry * 0.8f);
            c.drawArc(r, 10, 160, false, stroke);
        }
        // Öffnung: innen dunkel, Bier
        r.set(tx - rx, ty - ry, tx + rx, ty + ry);
        fill.setColor(0xFF5A0A0A);
        fill.setAlpha(a);
        c.drawOval(r, fill);
        c.save();
        path.reset();
        path.addOval(r, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(hit ? 0xFFFFE082 : 0xFFE0A526);
        fill.setAlpha(a);
        r.set(tx - rx * 0.92f, ty - ry * 0.55f, tx + rx * 0.92f, ty + ry * 1.25f);
        c.drawOval(r, fill);
        fill.setColor(0xFFFFF3C4);
        fill.setAlpha(Math.round(160 * alpha));
        r.set(tx - rx * 0.5f, ty - ry * 0.2f, tx + rx * 0.1f, ty + ry * 0.35f);
        c.drawOval(r, fill);
        c.restore();
        // weißer Rand
        stroke.setColor(0xFFFFFFFF);
        stroke.setAlpha(a);
        stroke.setStrokeWidth(Math.max(1.5f, rx * 0.12f));
        r.set(tx - rx, ty - ry, tx + rx, ty + ry);
        c.drawOval(r, stroke);
        stroke.setAlpha(255);
        fill.setAlpha(255);
    }

    /** Rot-weißer Pfosten auf dem Tisch. */
    private void drawBar(Canvas c, double bx, double bz) {
        double x0 = bx - Physics.BAR_W, x1 = bx + Physics.BAR_W;
        double zf = bz - Physics.BAR_D, zb = bz + Physics.BAR_D, H = Physics.BAR_H;
        // Schatten
        proj(x0 + 0.02, 0, zf, p);
        proj(x1 + 0.05, 0, zb + 0.04, q);
        fill.setColor(0x55000000);
        c.drawRect(Math.min(p[0], q[0]), q[1], Math.max(p[0], q[0]), p[1], fill);
        // Seitenfläche (die zur Mitte zeigende ist sichtbar)
        double xs = bx > 0 ? x0 : x1;
        path.reset();
        proj(xs, 0, zf, p);
        path.moveTo(p[0], p[1]);
        proj(xs, H, zf, p);
        path.lineTo(p[0], p[1]);
        proj(xs, H, zb, p);
        path.lineTo(p[0], p[1]);
        proj(xs, 0, zb, p);
        path.lineTo(p[0], p[1]);
        path.close();
        fill.setColor(0xFF9E1B1B);
        c.drawPath(path, fill);
        // Vorderseite mit Streifen
        int bands = 8;
        for (int k = 0; k < bands; k++) {
            double ya = H * k / bands, yb = H * (k + 1) / bands;
            path.reset();
            proj(x0, ya, zf, p);
            path.moveTo(p[0], p[1]);
            proj(x1, ya, zf, p);
            path.lineTo(p[0], p[1]);
            proj(x1, yb, zf, p);
            path.lineTo(p[0], p[1]);
            proj(x0, yb, zf, p);
            path.lineTo(p[0], p[1]);
            path.close();
            fill.setColor(k % 2 == 0 ? 0xFFE53935 : 0xFFF5F5F5);
            c.drawPath(path, fill);
        }
        // Oberseite
        path.reset();
        proj(x0, H, zf, p);
        path.moveTo(p[0], p[1]);
        proj(x1, H, zf, p);
        path.lineTo(p[0], p[1]);
        proj(x1, H, zb, p);
        path.lineTo(p[0], p[1]);
        proj(x0, H, zb, p);
        path.lineTo(p[0], p[1]);
        path.close();
        fill.setColor(0xFFFFCDD2);
        c.drawPath(path, fill);
        // Umriss
        stroke.setColor(0x88000000);
        proj(x0, 0, zf, p);
        proj(x1, H, zf, q);
        stroke.setStrokeWidth(Math.max(1.5f, getWidth() * 0.003f));
        c.drawRect(Math.min(p[0], q[0]), q[1], Math.max(p[0], q[0]), p[1], stroke);
    }

    private RadialGradient vignette;
    private int vignetteLevel = -1;

    private void drawVignette(Canvas c, float w, float h) {
        if (vignette == null || vignetteLevel != drunk) {
            vignetteLevel = drunk;
            int a = Math.min(200, 40 + drunk * 18);
            vignette = new RadialGradient(w / 2, h / 2, (float) Math.hypot(w, h) * 0.55f,
                    new int[]{0x00000000, 0x00000000, (a << 24) | 0x1A0010},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        fill.setShader(vignette);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    private void drawBall(Canvas c, double x, double y, double z) {
        if (y < -0.005) return; // unter Tischhöhe = hinter/neben dem Tisch verschwunden
        // Schatten auf dem Tisch
        if (Math.abs(x) < Physics.TABLE_W / 2 && z > 0 && z < Physics.TABLE_L && y > -0.01) {
            proj(x, 0, z, p);
            float rr = (float) (Physics.BALL_R * F / p[2]);
            float fade = (float) Math.max(0.15, 1 - y * 1.5);
            fill.setColor(0x000000);
            fill.setAlpha(Math.round(110 * fade));
            r.set(p[0] - rr, p[1] - rr * 0.35f, p[0] + rr, p[1] + rr * 0.35f);
            c.drawOval(r, fill);
            fill.setAlpha(255);
        }
        proj(x, y, z, p);
        float rr = (float) (Physics.BALL_R * F / p[2]);
        fill.setShader(new RadialGradient(p[0] - rr * 0.35f, p[1] - rr * 0.35f, rr * 1.3f,
                0xFFFFFFFF, 0xFFD9D9D9, Shader.TileMode.CLAMP));
        c.drawCircle(p[0], p[1], rr, fill);
        fill.setShader(null);
    }

    private void spawnSplash(double cx, double cz) {
        drops.clear();
        for (int i = 0; i < 16; i++) {
            Drop d = new Drop();
            double a = rnd.nextDouble() * Math.PI * 2;
            double s = 0.15 + rnd.nextDouble() * 0.35;
            d.x = cx;
            d.y = Physics.CUP_H;
            d.z = cz;
            d.vx = Math.cos(a) * s;
            d.vz = Math.sin(a) * s;
            d.vy = 0.8 + rnd.nextDouble() * 1.0;
            drops.add(d);
        }
    }

    private long lastDrop;

    private void drawDrops(Canvas c, long now) {
        if (drops.isEmpty()) {
            lastDrop = now;
            return;
        }
        double dt = Math.min(0.05, (now - lastDrop) / 1000.0);
        lastDrop = now;
        for (int i = drops.size() - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            d.vy -= 9.81 * dt;
            d.x += d.vx * dt;
            d.y += d.vy * dt;
            d.z += d.vz * dt;
            if (d.y < 0) {
                drops.remove(i);
                continue;
            }
            proj(d.x, d.y, d.z, p);
            float rr = (float) (0.006 * F / p[2]);
            fill.setColor(0xDDFFC107);
            c.drawCircle(p[0], p[1], rr, fill);
        }
    }

    private void drawHand(Canvas c, float w, float h) {
        if (!inputEnabled || sim != null) return;
        float bx = holding ? fx : w / 2, by = holding ? fy : h * 0.86f;
        float rr = w * 0.075f;
        fill.setColor(0x44000000);
        c.drawCircle(bx + rr * 0.15f, by + rr * 0.2f, rr, fill);
        fill.setShader(new RadialGradient(bx - rr * 0.35f, by - rr * 0.35f, rr * 1.3f,
                0xFFFFFFFF, 0xFFD0D0D0, Shader.TileMode.CLAMP));
        c.drawCircle(bx, by, rr, fill);
        fill.setShader(null);
        if (!holding) {
            text.setTextSize(w * 0.045f);
            c.drawText("Ball antippen & nach oben wischen", w / 2, h * 0.96f, text);
        }
    }

    private void drawHud(Canvas c, float w, float h) {
        text.setTextSize(w * 0.065f);
        c.drawText(title, w / 2, h * 0.075f + w * 0.06f, text);
        text.setTextSize(w * 0.042f);
        c.drawText(sub, w / 2, h * 0.075f + w * 0.13f, text);
    }

    // --------------------------------------------------------------- Steuerung

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inputEnabled || sim != null || e.getY() < getHeight() * 0.5f) return true;
                holding = true;
                downX = fx = e.getX();
                fy = e.getY();
                hn = 0;
                push(fx, fy, now);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!holding) return true;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    push(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i));
                }
                fx = e.getX();
                fy = e.getY();
                push(fx, fy, now);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                if (!holding) return true;
                holding = false;
                push(e.getX(), e.getY(), now);
                // Geschwindigkeit der letzten ~90 ms
                int last = hn - 1;
                int first = last;
                while (first > 0 && ht[(first - 1) % hx.length] >= ht[last % hx.length] - 90 && last - first < hx.length - 2) {
                    first--;
                }
                float dtS = Math.max(0.016f, (ht[last % hx.length] - ht[first % hx.length]) / 1000f);
                float vx = (hx[last % hx.length] - hx[first % hx.length]) / dtS;
                float vy = (hy[last % hx.length] - hy[first % hx.length]) / dtS;
                float hgt = getHeight();
                if (vy > -hgt * 0.8f) {
                    if (throwListener != null) throwListener.onWeakThrow();
                    invalidate();
                    return true;
                }
                double vn = -vy / hgt;
                double speed = Math.max(2.0, Math.min(7.0, 2.2 + vn * 0.55));
                double theta = Math.max(-0.4, Math.min(0.4, Math.atan2(vx, -vy) * 0.4));
                double x0 = Math.max(-0.15, Math.min(0.15, (downX - getWidth() / 2f) / getWidth() * 0.25));
                if (drunk > 0) {
                    double t = (now - clockStart) / 1000.0;
                    theta += Math.sin(t * 0.9) * drunk * 0.004 + Math.sin(t * 2.3) * drunk * 0.002;
                    speed *= 1 + Math.sin(t * 1.7) * drunk * 0.004;
                }
                // Werte runden, damit beide Handys exakt dieselben Zahlen haben
                speed = Math.round(speed * 1000) / 1000.0;
                theta = Math.round(theta * 10000) / 10000.0;
                x0 = Math.round(x0 * 10000) / 10000.0;
                if (throwListener != null) throwListener.onThrow(speed, theta, x0);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                holding = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void push(float x, float y, long t) {
        int i = hn % hx.length;
        hx[i] = x;
        hy[i] = y;
        ht[i] = t;
        hn++;
    }
}
