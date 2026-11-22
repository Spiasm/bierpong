package de.spiasm.bierpong;

/**
 * Ball-Physik. Rechnet mit double und StrictMath, damit beide Handys exakt dasselbe Ergebnis bekommen.
 * Koordinaten in Metern: x = seitlich, y = hoch, z = nach vorne (vom Werfer weg).
 */
public final class Physics {
    public static final double G = 9.81;
    public static final double BALL_R = 0.02;
    public static final double RIM_R = 0.0475;
    public static final double BOTTOM_R = 0.031;
    public static final double CUP_H = 0.12;
    public static final double TABLE_L = 2.44;
    public static final double TABLE_W = 0.61;
    public static final double START_Y = 0.28;
    public static final double ELEVATION = StrictMath.toRadians(55);
    public static final double DT = 1.0 / 240.0;

    public static final int CUPS = 10;
    /** Becherpositionen {x, z}: Dreieck 4-3-2-1, Spitze zum Werfer */
    public static final double[][] CUP_POS = new double[CUPS][2];

    static {
        double dx = 0.097, dz = 0.084, back = TABLE_L - 0.07;
        int[] rows = {4, 3, 2, 1};
        int k = 0;
        for (int i = 0; i < rows.length; i++) {
            int n = rows[i];
            for (int j = 0; j < n; j++) {
                CUP_POS[k][0] = (j - (n - 1) / 2.0) * dx;
                CUP_POS[k][1] = back - i * dz;
                k++;
            }
        }
    }

    public static final int RUN = 0, IN = 1, MISS = 2;
    public static final int EV_NONE = 0, EV_TABLE = 1, EV_RIM = 2, EV_BAR = 3;

    // Spielmodi
    public static final int MODE_EASY = 0, MODE_MEDIUM = 1, MODE_HARD = 2, MODE_REAL = 3;
    public static final String[] MODE_NAMES = {"Einfach", "Mittel", "Schwer", "Realistisch"};

    // Wandernde Balken (Pfosten) auf dem Tisch
    public static final double BAR_H = 1.0;   // Höhe
    public static final double BAR_W = 0.05;  // halbe Breite
    public static final double BAR_D = 0.03;  // halbe Tiefe
    public static final double BAR_A = 0.24;  // wie weit sie zur Seite wandern
    public static final double[] BAR_Z = {1.30, 0.85};
    public static final double[] BAR_SPEED = {1.7, 2.4};
    public static final double[] BAR_OFF = {0.0, 2.0};

    public static int barCount(int mode) {
        return mode == MODE_MEDIUM ? 1 : mode == MODE_HARD ? 2 : 0;
    }

    /** Seitliche Position von Balken i zur Zeit T (Sekunden). */
    public static double barX(int i, double T) {
        return BAR_A * StrictMath.sin(BAR_SPEED[i] * T + BAR_OFF[i]);
    }

    /** Ein Wurf in der Simulation. */
    public static final class Sim {
        public double x, y, z, vx, vy, vz, t;
        public int state = RUN;
        public int cup = -1;
        /** Ereignis im letzten Schritt (für Geräusche) */
        public int event = EV_NONE;
        private final boolean[] alive;
        public final int bars;
        public final double phase;

        public Sim(double speed, double theta, double x0, boolean[] alive) {
            this(speed, theta, x0, alive, 0, 0);
        }

        public Sim(double speed, double theta, double x0, boolean[] alive, int bars, double phase) {
            this.alive = alive.clone();
            this.bars = bars;
            this.phase = phase;
            x = x0;
            y = START_Y;
            z = 0;
            double vh = speed * StrictMath.cos(ELEVATION);
            vx = vh * StrictMath.sin(theta);
            vz = vh * StrictMath.cos(theta);
            vy = speed * StrictMath.sin(ELEVATION);
        }

        public void step() {
            if (state != RUN) return;
            event = EV_NONE;
            t += DT;
            double py = y;
            vy -= G * DT;
            x += vx * DT;
            y += vy * DT;
            z += vz * DT;
            boolean onTable = StrictMath.abs(x) <= TABLE_W / 2 && z >= 0 && z <= TABLE_L;

            // Balken
            for (int b = 0; b < bars; b++) {
                double bz = BAR_Z[b], bx = barX(b, phase + t);
                double ax = StrictMath.abs(x - bx), az = StrictMath.abs(z - bz);
                if (y < BAR_H + BALL_R && ax < BAR_W + BALL_R && az < BAR_D + BALL_R) {
                    double px = BAR_W + BALL_R - ax, pz = BAR_D + BALL_R - az;
                    if (pz <= px) {
                        double sgn = z < bz ? -1 : 1;
                        z = bz + sgn * (BAR_D + BALL_R);
                        if (vz * sgn < 0) vz = -vz * 0.45;
                    } else {
                        double sgn = x < bx ? -1 : 1;
                        x = bx + sgn * (BAR_W + BALL_R);
                        if (vx * sgn < 0) vx = -vx * 0.45;
                    }
                    event = EV_BAR;
                }
            }

            for (int i = 0; i < CUPS; i++) {
                if (!alive[i]) continue;
                double cx = CUP_POS[i][0], cz = CUP_POS[i][1];
                double dx = x - cx, dz = z - cz;
                double d = StrictMath.sqrt(dx * dx + dz * dz);
                if (d > RIM_R + BALL_R + 0.01) continue;
                // Rein in den Becher
                if (py >= CUP_H && y < CUP_H && d < RIM_R - BALL_R * 0.4) {
                    state = IN;
                    cup = i;
                    return;
                }
                // Am Rand oder an der Becherwand abprallen
                if (y > 0 && y < CUP_H + BALL_R) {
                    double f = StrictMath.min(1, StrictMath.max(0, y / CUP_H));
                    double rr = BOTTOM_R + (RIM_R - BOTTOM_R) * f;
                    if (d < rr + BALL_R && d > rr - BALL_R * 0.6) {
                        double nx = 0, nz = 1;
                        if (d > 1e-6) {
                            nx = dx / d;
                            nz = dz / d;
                        }
                        double vn = vx * nx + vz * nz;
                        if (vn < 0) {
                            vx -= 1.6 * vn * nx;
                            vz -= 1.6 * vn * nz;
                            vx *= 0.8;
                            vz *= 0.8;
                            if (y > CUP_H - 0.01) vy *= 0.7;
                            event = EV_RIM;
                        }
                        x = cx + nx * (rr + BALL_R);
                        z = cz + nz * (rr + BALL_R);
                    }
                }
            }

            if (onTable && y < BALL_R && vy < 0) {
                y = BALL_R;
                if (vy < -0.3) event = EV_TABLE;
                vy = -vy * 0.5;
                vx *= 0.85;
                vz *= 0.85;
                if (StrictMath.abs(vy) < 0.15) vy = 0;
            }
            if (y < -0.4) state = MISS;
            if (onTable && y <= BALL_R + 1e-6 && vy == 0 && StrictMath.sqrt(vx * vx + vz * vz) < 0.05) state = MISS;
            if (t > 6) state = MISS;
        }
    }

    /** Rechnet den Wurf komplett durch. Ergebnis: getroffener Becher oder -1. */
    public static int outcome(double speed, double theta, double x0, boolean[] alive) {
        return outcome(speed, theta, x0, alive, 0, 0);
    }

    public static int outcome(double speed, double theta, double x0, boolean[] alive, int bars, double phase) {
        Sim s = new Sim(speed, theta, x0, alive, bars, phase);
        while (s.state == RUN) s.step();
        return s.state == IN ? s.cup : -1;
    }

    /** Für den Computer: Wurf berechnen, der Becher i trifft. {speed, theta} oder null. */
    public static double[] aim(int cup, boolean[] alive, double x0) {
        double cx = CUP_POS[cup][0], cz = CUP_POS[cup][1];
        double theta = StrictMath.atan2(cx - x0, cz);
        double lo = -1, hi = -1;
        for (double v = 3.0; v <= 6.5; v += 0.01) {
            if (outcome(v, theta, x0, alive) == cup) {
                if (lo < 0) lo = v;
                hi = v;
            } else if (lo > 0) {
                break;
            }
        }
        if (lo < 0) return null;
        return new double[]{(lo + hi) / 2, theta};
    }

    public static int count(boolean[] alive) {
        int n = 0;
        for (boolean b : alive) if (b) n++;
        return n;
    }
}
