/*
 * Homestead - a replacement home screen for Fire TV.
 * Copyright (C) 2026 Homestead contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. It is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.homestead.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The remote's buttons, for the phone remote. Only the keys in this table can be pressed: the
 * phone names a key and never sends anything else to the TV, so a signed-in phone cannot run
 * commands, only press what a remote has.
 *
 * A key reaches the TV in one of two ways, both through the on-device ADB link, which is how a
 * real remote's press reaches whichever app is in front, Salotto included:
 * <ul>
 * <li>through {@link KeyDaemon}, a small helper process the link starts once and the app then
 *     talks to over a loopback socket: a press takes a few milliseconds;</li>
 * <li>while the helper is not up (or could not be started), as `input keyevent` over the link,
 *     which costs about 0.7 s a run because each run starts a new process.</li>
 * </ul>
 * Home and Back also work without the link, through the accessibility service. Main thread
 * only, except where a field says otherwise.
 */
final class RemoteKeys {

    private static final String[] NAMES = {
            "up", "down", "left", "right", "ok", "back", "home", "menu",
            "playpause", "rewind", "ffwd", "volup", "voldown", "mute" };
    private static final int[] CODES = KeyDaemon.CODES;

    private static final String TAG = "Homestead";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Presses wait here while the link is busy; the oldest go if the phone outruns the TV. */
    private static final ArrayDeque<Integer> QUEUE = new ArrayDeque<Integer>();
    private static final int QUEUE_MAX = 6;
    /** A press the link never answered must not block the next ones for good. */
    private static final long STUCK_MS = 8000L;
    private static boolean busy;
    private static long busySince;

    // ---- the helper process
    private static final SecureRandom RNG = new SecureRandom();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "remote-keys");
            t.setDaemon(true);
            return t;
        }
    });
    /** After a failed start, the earliest time to try again. */
    private static final long RETRY_MS = 120000L;
    private static volatile boolean helperUp;
    private static Context ctx;
    private static volatile String helperSecret = "";
    private static boolean starting;
    private static long noHelperUntil;
    private static Socket sock;                 // IO thread only
    private static OutputStream sockOut;        // IO thread only

    static final String OK = "ok", UNKNOWN = "unknown", NEEDS_LINK = "needs_link";

    private RemoteKeys() { }

    private static int code(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return CODES[i];
        return -1;
    }

    /** Presses a key: {@link #OK}, {@link #UNKNOWN} for a name not in the table, or {@link #NEEDS_LINK}. */
    static String press(Context c, String name) {
        int code = code(name);
        if (code < 0) return UNKNOWN;
        ctx = c.getApplicationContext();
        if (helperUp) {
            toHelper("key " + code, code);
            return OK;
        }
        if (!AdbBridge.canRun(c)) {
            // Without the link only the two system actions are possible.
            if (code == 3) return HomeRedirectService.globalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME) ? OK : NEEDS_LINK;
            if (code == 4) return HomeRedirectService.globalAction(
                    AccessibilityService.GLOBAL_ACTION_BACK) ? OK : NEEDS_LINK;
            return NEEDS_LINK;
        }
        QUEUE.addLast(code);
        while (QUEUE.size() > QUEUE_MAX) QUEUE.removeFirst();
        pump(c);
        return OK;
    }

    /**
     * Brings the link and the helper up ahead of the first press, and keeps them up while the
     * phone's remote page is open (it asks every 20 s; the helper exits after 75 s without a
     * line and the link after 25 s without a command).
     */
    static void warm(Context c) {
        ctx = c.getApplicationContext();
        if (helperUp) {
            toHelper("ping", -1);
            return;
        }
        if (!AdbBridge.canRun(c) || busy || starting) return;
        if (SystemClock.elapsedRealtime() >= noHelperUntil) {
            startHelper(c);
        } else if (AdbBridge.isConnected()) {
            AdbBridge.keepAlive();
        } else if (!AdbBridge.isBusy()) {
            AdbBridge.init(c);
            AdbBridge.connect();
        }
    }

    /** The phone remote was switched off: nothing of this should stay running. */
    static void stop() {
        MAIN.post(new Runnable() {
            public void run() { QUEUE.clear(); }
        });
        if (helperUp) toHelper("quit", -1);
    }

    private static void pump(final Context c) {
        if (busy && SystemClock.elapsedRealtime() - busySince > STUCK_MS) busy = false;
        if (busy || QUEUE.isEmpty()) return;
        // Everything that is waiting goes in one `input` run, in order: starting it costs about
        // 0.7 s on a Stick, and it takes any number of keycodes (four in 0.76 s, against 2.9 s
        // as four separate runs).
        StringBuilder cmd = new StringBuilder("input keyevent");
        while (!QUEUE.isEmpty()) cmd.append(' ').append(QUEUE.removeFirst());
        busy = true;
        busySince = SystemClock.elapsedRealtime();
        AdbBridge.run(cmd.toString(), new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                MAIN.post(new Runnable() {
                    public void run() {
                        busy = false;
                        if (!QUEUE.isEmpty()) pump(c);
                        else startHelper(c);
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- the helper process

    /**
     * Starts {@link KeyDaemon} over the link, then connects to it. It runs from this APK, with a
     * port and a secret made fresh now, and nothing else can press keys through it: it listens
     * on loopback only and drops any line that does not begin with the secret.
     */
    private static void startHelper(final Context c) {
        if (starting || helperUp || busy || !AdbBridge.canRun(c)) return;
        if (SystemClock.elapsedRealtime() < noHelperUntil) return;
        String apk = c.getPackageCodePath();
        if (apk == null || !apk.matches("[A-Za-z0-9/._=+-]+")) {
            noHelperUntil = SystemClock.elapsedRealtime() + RETRY_MS;
            return;
        }
        starting = true;
        Log.i(TAG, "remote keys: starting the helper");
        final int port = 20000 + RNG.nextInt(40000);
        byte[] raw = new byte[8];
        RNG.nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < raw.length; i++) hex.append(String.format("%02x", raw[i] & 0xff));
        final String secret = hex.toString();
        // All three descriptors are redirected, or the link would wait for the helper to end.
        // The short sleep matters: the shell service is a pty, and once its shell has exited the
        // pty hangs up, which kills a background job that has not yet left the session with
        // setsid (checked on a Stick: without the sleep the helper never starts).
        String cmd = "CLASSPATH=" + apk + " setsid app_process /system/bin "
                + "com.homestead.launcher.KeyDaemon " + port + " " + secret
                + " </dev/null >/dev/null 2>&1 & sleep 0.5";
        busy = true;
        busySince = SystemClock.elapsedRealtime();
        AdbBridge.run(cmd, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                busy = false;
                Log.i(TAG, "remote keys: helper command ok=" + ok + " out=" + out.trim());
                IO.execute(new Runnable() {
                    public void run() { connect(port, secret); }
                });
            }
        });
    }

    /** IO thread. The helper takes a moment to start, so this tries for a few seconds. */
    private static void connect(int port, String secret) {
        Socket s = null;
        for (int i = 0; i < 16 && s == null; i++) {
            Socket t = new Socket();
            try {
                t.connect(new InetSocketAddress("127.0.0.1", port), 500);
                t.setTcpNoDelay(true);
                s = t;
            } catch (IOException e) {
                try { t.close(); } catch (IOException ignored) { }
                SystemClock.sleep(250);
            }
        }
        boolean up = false;
        Log.i(TAG, "remote keys: helper socket " + (s != null ? "connected" : "not reachable"));
        if (s != null) {
            try {
                closeSocket();
                helperSecret = secret;
                sock = s;
                sockOut = s.getOutputStream();
                sockOut.write((secret + " ping\n").getBytes("US-ASCII"));
                sockOut.flush();
                watch(s);
                helperUp = true;
                up = true;
            } catch (IOException e) {
                Log.w(TAG, "remote keys: helper hello failed: " + e);
                closeSocket();
            }
        }
        final boolean ok = up;
        MAIN.post(new Runnable() {
            public void run() {
                starting = false;
                if (!ok) noHelperUntil = SystemClock.elapsedRealtime() + RETRY_MS;
                // Presses that arrived while it was starting go the fast way if it is up.
                if (helperUp) {
                    while (!QUEUE.isEmpty()) {
                        int k = QUEUE.removeFirst();
                        toHelper("key " + k, k);
                    }
                } else if (!QUEUE.isEmpty() && ctx != null) {
                    pump(ctx);
                }
            }
        });
    }

    /** The helper never writes; the end of its stream means it has exited. */
    private static void watch(final Socket s) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = s.getInputStream();
                    while (in.read() >= 0) { }
                } catch (IOException ignored) { }
                IO.execute(new Runnable() {
                    public void run() { if (sock == s) closeSocket(); }
                });
            }
        }, "remote-keys-watch");
        t.setDaemon(true);
        t.start();
    }

    /** IO thread. */
    private static void closeSocket() {
        helperUp = false;
        Socket s = sock;
        sock = null;
        sockOut = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Sends one line to the helper. If that fails the press is not lost: it goes back to the
     * queue and out over the link instead. {@code code} is the key, or -1 for a line that
     * carries none.
     */
    private static void toHelper(final String line, final int code) {
        IO.execute(new Runnable() {
            public void run() {
                try {
                    OutputStream o = sockOut;
                    if (o == null) throw new IOException("closed");
                    o.write((helperSecret + " " + line + "\n").getBytes("US-ASCII"));
                    o.flush();
                } catch (IOException e) {
                    Log.w(TAG, "remote keys: helper write failed: " + e);
                    closeSocket();
                    if (code < 0) return;
                    MAIN.post(new Runnable() {
                        public void run() {
                            if (ctx == null) return;
                            QUEUE.addLast(code);
                            pump(ctx);
                        }
                    });
                }
            }
        });
    }
}
