package de.spiasm.bierpong;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Random;

/** Geräusche – werden beim Start im Programm erzeugt, keine Sounddateien nötig. */
public class Sfx {
    public static final int THROW = 0, TABLE = 1, RIM = 2, PLOP = 3, CHEER = 4, MISS = 5, WIN = 6, LOSE = 7;
    private static final int RATE = 22050;

    private volatile short[][] data;
    private final Handler handler = new Handler(Looper.getMainLooper());
    public boolean enabled = true;

    public Sfx() {
        new Thread(this::build).start();
    }

    public void play(int id) {
        play(id, 0);
    }

    public void play(final int id, long delayMs) {
        if (!enabled) return;
        if (delayMs > 0) {
            handler.postDelayed(() -> play(id, 0), delayMs);
            return;
        }
        short[][] all = data;
        if (all == null || all[id] == null) return;
        short[] d = all[id];
        try {
            final AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(d.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(d, 0, d.length);
            t.play();
            handler.postDelayed(() -> {
                try {
                    t.stop();
                } catch (Exception ignored) {
                }
                t.release();
            }, d.length * 1000L / RATE + 400);
        } catch (Exception ignored) {
        }
    }

    private void build() {
        Random rnd = new Random(3);
        short[][] d = new short[8][];
        d[THROW] = pcm(whoosh(rnd), 0.6f);
        d[TABLE] = pcm(knock(900, 0.12f, 60, rnd), 0.7f);
        d[RIM] = pcm(rim(rnd), 0.8f);
        d[PLOP] = pcm(plop(rnd), 0.9f);
        d[CHEER] = pcm(cheer(rnd), 0.8f);
        d[MISS] = pcm(melody(new float[]{311.13f, 293.66f}, new float[]{0.18f, 0.45f}), 0.5f);
        d[WIN] = pcm(melody(new float[]{523.25f, 659.25f, 783.99f, 1046.5f, 783.99f, 1046.5f},
                new float[]{0.13f, 0.13f, 0.13f, 0.3f, 0.12f, 0.5f}), 0.85f);
        d[LOSE] = pcm(melody(new float[]{392f, 369.99f, 349.23f, 293.66f},
                new float[]{0.3f, 0.3f, 0.3f, 0.8f}), 0.85f);
        data = d;
    }

    private static float[] whoosh(Random rnd) {
        int n = (int) (RATE * 0.35f);
        float[] f = new float[n];
        float y = 0;
        for (int i = 0; i < n; i++) {
            float p = i / (float) n;
            y += (0.02f + 0.25f * p) * ((rnd.nextFloat() * 2 - 1) - y);
            f[i] = y * (float) Math.pow(Math.sin(Math.PI * p), 2);
        }
        return f;
    }

    /** Kurzes "Tock" (Ball auf Tisch). */
    private static float[] knock(float freq, float sec, float decay, Random rnd) {
        int n = (int) (RATE * sec);
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            f[i] = (float) (Math.sin(2 * Math.PI * freq * t) * Math.exp(-t * decay)
                    + Math.sin(2 * Math.PI * freq * 2.3 * t) * Math.exp(-t * decay * 1.5) * 0.4
                    + (rnd.nextFloat() * 2 - 1) * Math.exp(-t * 400) * 0.6);
        }
        return f;
    }

    /** Plastikbecher-Rand "Klonk". */
    private static float[] rim(Random rnd) {
        int n = (int) (RATE * 0.25f);
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            f[i] = (float) (Math.sin(2 * Math.PI * 1650 * t) * Math.exp(-t * 28)
                    + Math.sin(2 * Math.PI * 2470 * t) * Math.exp(-t * 35) * 0.6
                    + Math.sin(2 * Math.PI * 610 * t) * Math.exp(-t * 20) * 0.5
                    + (rnd.nextFloat() * 2 - 1) * Math.exp(-t * 300) * 0.5);
        }
        return f;
    }

    /** "Plopp" in die Flüssigkeit mit Spritzer. */
    private static float[] plop(Random rnd) {
        int n = (int) (RATE * 0.5f);
        float[] f = new float[n];
        float y = 0;
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            double freq = 350 + 900 * Math.min(1, t / 0.06);
            ph += 2 * Math.PI * freq / RATE;
            float tone = (float) (Math.sin(ph) * Math.exp(-t * 18));
            y += 0.3f * ((rnd.nextFloat() * 2 - 1) - y);
            float splash = (float) (y * Math.exp(-t * 9) * (1 - Math.exp(-t * 200)));
            f[i] = tone + splash * 1.5f;
        }
        return f;
    }

    /** Jubel: Rauschen, das an- und abschwillt, plus kleine Fanfare. */
    private static float[] cheer(Random rnd) {
        int n = (int) (RATE * 1.4f);
        float[] f = new float[n];
        float y = 0, y2 = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float noise = rnd.nextFloat() * 2 - 1;
            y += 0.25f * (noise - y);
            y2 += 0.25f * (y - y2);
            float band = y - y2;
            float env = (float) ((1 - Math.exp(-t * 6)) * Math.exp(-t * 1.8));
            float wobble = (float) (0.7 + 0.3 * Math.sin(2 * Math.PI * 7 * t));
            f[i] = band * env * wobble * 3f;
        }
        float[] m = melody(new float[]{783.99f, 1046.5f}, new float[]{0.1f, 0.35f});
        for (int i = 0; i < m.length && i < n; i++) f[i] += m[i] * 0.35f;
        return f;
    }

    private static float[] melody(float[] freqs, float[] durs) {
        float total = 0;
        for (float d : durs) total += d;
        int n = (int) (RATE * (total + 0.3f));
        float[] f = new float[n];
        float start = 0;
        for (int k = 0; k < freqs.length; k++) {
            int s0 = (int) (start * RATE);
            int len = (int) ((durs[k] + 0.25f) * RATE);
            for (int i = 0; i < len && s0 + i < n; i++) {
                float t = i / (float) RATE;
                float env = (float) (Math.min(1, t * 200) * Math.exp(-t * 3.5));
                double w = Math.sin(2 * Math.PI * freqs[k] * t) + 0.3 * Math.sin(4 * Math.PI * freqs[k] * t)
                        + 0.12 * Math.sin(6 * Math.PI * freqs[k] * t);
                f[s0 + i] += (float) (w * env);
            }
            start += durs[k];
        }
        return f;
    }

    private static short[] pcm(float[] f, float level) {
        float max = 0;
        for (float v : f) max = Math.max(max, Math.abs(v));
        float g = max > 0 ? level / max : 0;
        short[] s = new short[f.length];
        for (int i = 0; i < f.length; i++) s[i] = (short) (f[i] * g * 32767);
        return s;
    }
}
