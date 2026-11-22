package de.spiasm.bierpong;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements BtLink.Listener, PongView.ThrowListener {
    private static final int REQ_PERM = 1;
    private static final int REQ_ENABLE = 2;
    private static final String PERM_CONNECT = "android.permission.BLUETOOTH_CONNECT";

    private static final int BG = 0xFF1A1030;
    private static final int BTN = 0xFF3A2A50;
    private static final int ACCENT = 0xFFD32F2F;
    private static final int GREEN = 0xFF2E7D32;
    private static final int BLUE = 0xFF1E88E5;

    private static final int S_MENU = 0, S_WAIT = 1, S_PICK = 2, S_GAME = 3, S_END = 4, S_OTHER = 5;

    private BluetoothAdapter adapter;
    private BtLink link;
    private Sfx sfx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private String peerName = "Gegner";
    private int state = S_MENU;
    private Runnable pending;

    // Computer-Gegner
    private boolean vsBot;
    private boolean botSmart;
    private int mode = Physics.MODE_EASY;

    // Spielstand
    private boolean isHost;
    private boolean[] myCups = new boolean[Physics.CUPS];
    private boolean[] oppCups = new boolean[Physics.CUPS];
    private boolean myTurn;
    private int throwsLeft;
    private int hits;
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    private LinearLayout root;
    private PongView view;

    // ------------------------------------------------------------------ Start

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        sfx = new Sfx();
        sfx.enabled = getPreferences(MODE_PRIVATE).getBoolean("sound", true);
        showMenu(null);
    }

    @Override
    protected void onDestroy() {
        closeLink();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (state == S_MENU) {
            super.onBackPressed();
        } else if (state == S_OTHER || state == S_PICK || state == S_END) {
            closeLink();
            showMenu(null);
        } else {
            new AlertDialog.Builder(this)
                    .setMessage(vsBot ? "Spiel verlassen?" : "Spiel verlassen? Die Verbindung wird getrennt.")
                    .setPositiveButton("Verlassen", (d, w) -> {
                        closeLink();
                        showMenu(null);
                    })
                    .setNegativeButton("Weiterspielen", null)
                    .show();
        }
    }

    // ------------------------------------------------------------- Bluetooth

    private void withBluetooth(Runnable r) {
        if (adapter == null) {
            toast("Dieses Gerät hat kein Bluetooth.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(PERM_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pending = r;
            requestPermissions(new String[]{PERM_CONNECT}, REQ_PERM);
            return;
        }
        if (!adapter.isEnabled()) {
            pending = r;
            try {
                startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            } catch (Exception e) {
                toast("Bitte Bluetooth einschalten.");
            }
            return;
        }
        r.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERM) return;
        Runnable p = pending;
        pending = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && p != null) {
            withBluetooth(p);
        } else {
            toast("Ohne die Erlaubnis „Geräte in der Nähe“ geht Bluetooth nicht.");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code != REQ_ENABLE) return;
        Runnable p = pending;
        pending = null;
        if (adapter != null && adapter.isEnabled() && p != null) p.run();
        else toast("Bluetooth ist aus.");
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
        }
        vsBot = false;
        handler.removeCallbacksAndMessages(null);
        queue.clear();
    }

    private void send(String msg) {
        if (link != null) link.send(msg);
    }

    @Override
    public void onConnected(String name) {
        peerName = name;
        toast("Verbunden mit " + name);
        if (isHost) send("MODE " + mode);
        startGame();
    }

    @Override
    public void onClosed(String reason) {
        link = null;
        showMenu(reason);
    }

    @Override
    public void onMessage(String line) {
        String[] p = line.split(" ");
        if (p.length == 0) return;
        if ("AGAIN".equals(p[0])) {
            if (state == S_END) {
                toast(peerName + " will nochmal spielen!");
                startGame();
            }
            return;
        }
        if ("MODE".equals(p[0]) && p.length > 1) {
            try {
                mode = Math.max(0, Math.min(3, Integer.parseInt(p[1])));
            } catch (NumberFormatException ignored) {
            }
            updateTurn();
            return;
        }
        if ("THROW".equals(p[0]) && state == S_GAME) {
            queue.add(line);
            processQueue();
        }
    }

    private void processQueue() {
        if (state != S_GAME || view == null || view.busy() || myTurn || queue.isEmpty()) return;
        String[] p = queue.poll().split(" ");
        try {
            double speed = Double.parseDouble(p[1]);
            double theta = Double.parseDouble(p[2]);
            double x0 = Double.parseDouble(p[3]);
            final int cup = Integer.parseInt(p[4]);
            double phase = p.length > 5 ? Double.parseDouble(p[5]) : 0;
            view.cups = myCups;
            view.inputEnabled = false;
            view.animateThrow(speed, theta, x0, phase, (c, ex, ez) -> afterThrow(false, cup, ex, ez));
        } catch (RuntimeException e) {
            processQueue();
        }
    }

    // ----------------------------------------------------------- Spielablauf

    private void startGame() {
        for (int i = 0; i < Physics.CUPS; i++) {
            myCups[i] = true;
            oppCups[i] = true;
        }
        myTurn = isHost;
        throwsLeft = 2;
        hits = 0;
        queue.clear();
        showGame();
        updateTurn();
    }

    private void startBot(boolean smart) {
        closeLink();
        vsBot = true;
        botSmart = smart;
        isHost = true;
        peerName = "Computer";
        startGame();
    }

    private void updateTurn() {
        if (view == null) return;
        view.bars = Physics.barCount(mode);
        view.drunk = mode == Physics.MODE_REAL ? Physics.CUPS - Physics.count(myCups) : 0;
        String score = Physics.MODE_NAMES[mode] + "  •  Du: " + Physics.count(myCups) + "   "
                + peerName + ": " + Physics.count(oppCups) + " Becher";
        view.sub = score;
        if (myTurn) {
            view.cups = oppCups;
            view.inputEnabled = true;
            view.title = "🍺 Dein Wurf (" + (3 - throwsLeft) + "/2)";
        } else {
            view.cups = myCups;
            view.inputEnabled = false;
            view.title = (vsBot ? "🤖 " : "") + peerName + " wirft (" + (3 - throwsLeft) + "/2)";
            if (vsBot) handler.postDelayed(this::botThrow, 1500);
        }
        view.invalidate();
        processQueue();
    }

    @Override
    public void onThrow(double speed, double theta, double x0) {
        if (state != S_GAME || !myTurn || view.busy()) return;
        double phase = view.clock();
        int cup = Physics.outcome(speed, theta, x0, oppCups, Physics.barCount(mode), phase);
        send("THROW " + speed + " " + theta + " " + x0 + " " + cup + " " + phase);
        view.inputEnabled = false;
        view.cups = oppCups;
        view.animateThrow(speed, theta, x0, phase, (c, ex, ez) -> afterThrow(true, cup, ex, ez));
    }

    @Override
    public void onWeakThrow() {
        view.flash("Schneller wischen!");
    }

    private void botThrow() {
        if (state != S_GAME || myTurn || !vsBot || view.busy()) return;
        List<Integer> alive = new ArrayList<>();
        for (int i = 0; i < Physics.CUPS; i++) if (myCups[i]) alive.add(i);
        if (alive.isEmpty()) return;
        int target = alive.get(random.nextInt(alive.size()));
        double[] aim = Physics.aim(target, myCups, 0);
        double speed, theta;
        if (aim == null) {
            speed = 4.3;
            theta = 0;
        } else {
            speed = aim[0];
            theta = aim[1];
        }
        double sv = botSmart ? 0.02 : 0.04;
        double st = botSmart ? 0.015 : 0.03;
        speed *= 1 + random.nextGaussian() * sv;
        theta += random.nextGaussian() * st;
        speed = Math.round(speed * 1000) / 1000.0;
        theta = Math.round(theta * 10000) / 10000.0;
        double phase = view.clock();
        final int cup = Physics.outcome(speed, theta, 0, myCups, Physics.barCount(mode), phase);
        view.cups = myCups;
        view.animateThrow(speed, theta, 0, phase, (c, ex, ez) -> afterThrow(false, cup, ex, ez));
    }

    private void afterThrow(boolean mine, int cup, double endX, double endZ) {
        if (state != S_GAME) return;
        boolean[] target = mine ? oppCups : myCups;
        if (cup >= 0 && target[cup]) {
            target[cup] = false;
            hits++;
            if (mine) {
                view.flash("Treffer! 🍺");
                sfx.play(Sfx.CHEER, 150);
            } else {
                view.flash(mode == Physics.MODE_REAL ? "Trink aus! 🍺 Hicks…" : "Getroffen – trink aus! 🍺");
            }
        } else {
            if (mine) {
                double front = Physics.CUP_POS[Physics.CUPS - 1][1] - Physics.RIM_R;
                if (view.lastBlocked) view.flash("Geblockt! 🚧");
                else if (endZ < front - 0.05) view.flash("Zu kurz!");
                else if (endZ > Physics.TABLE_L) view.flash("Zu weit!");
                else view.flash("Daneben!");
                sfx.play(Sfx.MISS);
            } else {
                view.flash(view.lastBlocked ? "Geblockt – Glück gehabt!" : "Daneben – Glück gehabt!");
            }
        }
        throwsLeft--;

        if (Physics.count(target) == 0) {
            handler.postDelayed(() -> showEnd(mine), 1200);
            return;
        }
        if (throwsLeft <= 0) {
            if (hits >= 2) {
                handler.postDelayed(() -> {
                    if (view != null) view.flash("2 Treffer – Bälle zurück!");
                }, 900);
            } else {
                myTurn = !myTurn;
            }
            throwsLeft = 2;
            hits = 0;
        }
        updateTurn();
    }

    // ------------------------------------------------------------- Oberfläche

    private void showMenu(String message) {
        state = S_MENU;
        view = null;
        newScreen();
        add(text("🍺 Bierpong", 34, Color.WHITE, true));
        add(text("Wirf den Ball in die Becher –\nzu zweit per Bluetooth oder gegen den Computer", 16, 0xFFC9B8E0, false));
        if (message != null) add(text(message, 15, 0xFFFFB74D, false));
        add(button("🛰  Spiel erstellen", BLUE, v -> withBluetooth(() -> showModePick(this::hostGame))));
        add(button("🔗  Spiel beitreten", BLUE, v -> withBluetooth(this::showPick)));
        add(button("🤖  Gegen den Computer", GREEN, v -> showModePick(this::showBotPick)));
        add(row(button("📖  Regeln", BTN, v -> showRules()), soundButton()));
        add(text("Zu zweit: Handys einmal in den Bluetooth-Einstellungen koppeln. Einer tippt „Spiel erstellen“, "
                + "der andere „Spiel beitreten“.", 14, 0xFFC9B8E0, false));
    }

    private void showRules() {
        state = S_OTHER;
        newScreen();
        add(text("📖 So geht's", 26, Color.WHITE, true));
        add(text("Werfen: Den Ball unten antippen und schwungvoll nach oben wischen.\n"
                + "• Schneller wischen = weiter werfen\n"
                + "• Schräg wischen = schräg werfen\n\n"
                + "Jeder hat 10 Becher. Pro Runde hast du 2 Würfe.\n"
                + "Triffst du mit beiden Würfen, bekommst du die Bälle zurück.\n\n"
                + "Der Ball kann am Becherrand abprallen und auf dem Tisch aufspringen – auch dann zählt er, "
                + "wenn er noch reinfällt.\n\n"
                + "Wer zuerst alle Becher des anderen trifft, gewinnt!\n\n"
                + "Schwierigkeitsgrade:\n"
                + "🙂 Einfach – ganz normal\n"
                + "🚧 Mittel – ein wandernder Balken blockt den Ball\n"
                + "🚧🚧 Schwer – zwei Balken\n"
                + "🥴 Realistisch – je mehr du trinken musstest, desto mehr schwankt alles (und dein Wurf)\n\n"
                + "Tipp: Wenn's danebengeht, steht in der Mitte „Zu kurz“ oder „Zu weit“.",
                16, 0xFFE8DDF5, false));
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    private void showModePick(final Runnable next) {
        state = S_OTHER;
        newScreen();
        add(text("🎯 Schwierigkeitsgrad", 26, Color.WHITE, true));
        String[] desc = {
                "Ganz normales Bierpong",
                "Ein rot-weißer Balken wandert über den Tisch und kann den Ball blocken",
                "Zwei Balken wandern über den Tisch",
                "Mit jedem Becher, den du trinken musst, schwankt dein Bild mehr – wie betrunken"};
        int[] cols = {GREEN, BLUE, ACCENT, 0xFF8E24AA};
        String[] icons = {"🙂", "🚧", "🚧🚧", "🥴"};
        for (int i = 0; i < 4; i++) {
            final int m = i;
            Button b = button(icons[i] + "  " + Physics.MODE_NAMES[i] + "\n" + desc[i], cols[i], v -> {
                mode = m;
                next.run();
            });
            b.setTextSize(15);
            add(b);
        }
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    private void showBotPick() {
        state = S_OTHER;
        newScreen();
        add(text("🤖 Gegen den Computer", 26, Color.WHITE, true));
        add(text("Wie gut soll der Computer werfen?", 16, 0xFFC9B8E0, false));
        add(button("😊  Leicht – trifft ab und zu", GREEN, v -> startBot(false)));
        add(button("😈  Normal – trifft ziemlich oft", ACCENT, v -> startBot(true)));
        add(button("Zurück", BTN, v -> showModePick(this::showBotPick)));
    }

    private void hostGame() {
        closeLink();
        isHost = true;
        link = new BtLink(this);
        link.host(adapter);
        state = S_WAIT;
        newScreen();
        String myName;
        try {
            myName = adapter.getName();
        } catch (SecurityException e) {
            myName = null;
        }
        add(text("⏳ Warte auf deinen Kumpel…", 24, Color.WHITE, true));
        add(text("Schwierigkeitsgrad: " + Physics.MODE_NAMES[mode], 16, 0xFFFFD54F, false));
        add(text("Er soll jetzt „Spiel beitreten“ tippen und dieses Handy auswählen"
                + (myName != null ? ":\n\n📱 " + myName : "."), 16, 0xFFE8DDF5, false));
        add(button("Abbrechen", ACCENT, v -> {
            closeLink();
            showMenu(null);
        }));
    }

    private void showPick() {
        state = S_PICK;
        newScreen();
        add(text("🔗 Mit wem spielen?", 24, Color.WHITE, true));
        Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException e) {
            devices = null;
        }
        if (devices == null || devices.isEmpty()) {
            add(text("Keine gekoppelten Geräte gefunden. Koppelt eure Handys zuerst in den Bluetooth-Einstellungen.",
                    16, 0xFFFFB74D, false));
        } else {
            add(text("Tippe auf das Handy deines Kumpels. Er muss vorher „Spiel erstellen“ getippt haben.",
                    15, 0xFFC9B8E0, false));
            for (final BluetoothDevice d : devices) {
                String name;
                try {
                    name = d.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                final String label = name == null ? d.getAddress() : name;
                add(button("📱  " + label, BLUE, v -> {
                    closeLink();
                    isHost = false;
                    link = new BtLink(this);
                    link.join(d, adapter);
                    state = S_WAIT;
                    newScreen();
                    add(text("⏳ Verbinde mit " + label + "…", 22, Color.WHITE, true));
                    add(button("Abbrechen", ACCENT, w -> {
                        closeLink();
                        showMenu(null);
                    }));
                }));
            }
        }
        add(button("⚙  Bluetooth-Einstellungen öffnen", BTN, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            } catch (Exception ignored) {
            }
        }));
        add(button("Zurück", BTN, v -> showMenu(null)));
    }

    private void showGame() {
        state = S_GAME;
        FrameLayout frame = new FrameLayout(this);
        view = new PongView(this);
        view.sfx = sfx;
        view.throwListener = this;
        frame.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        Button close = smallButton("✕");
        close.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams lp1 = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.START);
        lp1.setMargins(dp(10), dp(30), 0, 0);
        frame.addView(close, lp1);

        final Button snd = smallButton(sfx.enabled ? "🔊" : "🔇");
        snd.setOnClickListener(v -> {
            toggleSound();
            snd.setText(sfx.enabled ? "🔊" : "🔇");
        });
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        lp2.setMargins(0, dp(30), dp(10), 0);
        frame.addView(snd, lp2);

        setContentView(frame);
    }

    private void showEnd(boolean won) {
        state = S_END;
        view = null;
        handler.removeCallbacksAndMessages(null);
        sfx.play(won ? Sfx.WIN : Sfx.LOSE);
        newScreen();
        add(text(won ? "🏆 Gewonnen!" : "🍺 Verloren!", 38, won ? 0xFF81C784 : 0xFFE57373, true));
        add(text(won ? "Du hast alle Becher von " + peerName + " getroffen."
                : peerName + " hat alle deine Becher getroffen – Prost!", 17, Color.WHITE, false));
        add(button("🔁  Nochmal spielen", GREEN, v -> {
            if (vsBot) {
                startGame();
            } else {
                send("AGAIN");
                startGame();
            }
        }));
        add(button("Zum Menü", BTN, v -> {
            closeLink();
            showMenu(null);
        }));
    }

    // ---------------------------------------------------------------- Helfer

    private void toggleSound() {
        sfx.enabled = !sfx.enabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("sound", sfx.enabled).apply();
        if (sfx.enabled) sfx.play(Sfx.RIM);
    }

    private Button soundButton() {
        final Button b = button("", BTN, null);
        b.setText(sfx.enabled ? "🔊 Ton an" : "🔇 Ton aus");
        b.setOnClickListener(v -> {
            toggleSound();
            b.setText(sfx.enabled ? "🔊 Ton an" : "🔇 Ton aus");
        });
        return b;
    }

    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66000000);
        bg.setCornerRadius(dp(22));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private void newScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(36), dp(18), dp(28));
        sv.addView(root);
        setContentView(sv);
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(v, lp);
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(views[i], lp);
        }
        return r;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(17);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(14), dp(6), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
