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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reports Home presses from the system log. The HOME intent is logged as the system acts
 * on the press, a few hundred ms before Amazon's home draws, and it is the only trace of a
 * press made while Amazon's home is already in front (that changes no window).
 *
 * Needs READ_LOGS, a development permission granted over ADB. The log group it maps to is
 * applied only when a process starts, so this runs in its own process: binding after the
 * grant starts a fresh one that can read the log straight away. Only ActivityManager and
 * ActivityTaskManager lines from the system buffer are read, and nothing is kept.
 *
 * It also reports the buttons of a TV's remote that come over HDMI-CEC. Android does not hand
 * those to an accessibility key filter, but Fire OS logs each one (OZ-DCSCEC:HdmiCecAgent,
 * "Opcode: 0x44; Params: 0x72"), so the log is where they are seen.
 */
public class HomeKeyWatcher extends Service {

    static final int MSG_REGISTER = 1;
    /** arg1 is the uid that started the HOME intent. */
    static final int MSG_HOME = 2;
    /** arg1 is the CEC user-control code of a button pressed on the TV's remote. */
    static final int MSG_TVKEY = 3;

    private static final String TAG = "Homestead";
    private static final String PERM = "android.permission.READ_LOGS";

    private volatile Messenger client;
    private volatile boolean running;
    private volatile java.lang.Process proc;
    private Thread reader;

    private final Messenger me = new Messenger(new Handler(Looper.getMainLooper()) {
        public void handleMessage(Message m) {
            if (m.what == MSG_REGISTER) client = m.replyTo;
        }
    });

    /** True once READ_LOGS has been granted, whether or not this process has it yet. */
    public static boolean permitted(Context c) {
        return c.checkPermission(PERM, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (!permitted(this)) return;
        running = true;
        reader = new Thread(new Runnable() {
            public void run() { readLoop(); }
        }, "homestead-homekey");
        reader.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return me.getBinder();
    }

    @Override
    public void onDestroy() {
        running = false;
        java.lang.Process p = proc;
        if (p != null) p.destroy();
        if (reader != null) reader.interrupt();
        super.onDestroy();
    }

    private void readLoop() {
        long backoff = 2000L;
        while (running) {
            long started = System.currentTimeMillis();
            try {
                // -T 1 follows from the newest line, so there is no backlog to skip.
                // Android 9 (Fire OS 7) logs activity starts as ActivityManager; Android 10
                // and later (Fire OS 8) as ActivityTaskManager.
                // A filter on the text, not on tags: Amazon's CEC tag has a colon in it, which
                // logcat does not accept in a tag filter. Only these two kinds of line pass.
                proc = new ProcessBuilder("logcat", "-b", "system", "-b", "main", "-v", "epoch",
                        "-T", "1", "-e", "START u0 .|Opcode: 0x44; Params")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()));
                Log.i(TAG, "watching for Home presses");
                String line;
                while (running && (line = r.readLine()) != null) {
                    int uid = homeStartUid(line, started);
                    if (uid >= 0) { send(MSG_HOME, uid); continue; }
                    int cec = cecKey(line, started);
                    if (cec >= 0) send(MSG_TVKEY, cec);
                }
            } catch (Throwable t) {
                if (running) Log.w(TAG, "Home watch failed: " + t);
            } finally {
                java.lang.Process p = proc;
                if (p != null) p.destroy();
                proc = null;
            }
            if (!running) return;
            // logcat exited; a run that lasted a while starts the backoff over.
            if (System.currentTimeMillis() - started > 60000L) backoff = 2000L;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(backoff * 2, 60000L);
        }
    }

    /**
     * The uid that started a HOME intent, or -1 if the line is not one. Which uids count as
     * a Home press is up to HomeRedirectService. Our "Amazon Home" tile has no HOME
     * category, so it never matches.
     */
    static int homeStartUid(String line, long notBefore) {
        if (line.indexOf("START u0 {") < 0) return -1;
        if (line.indexOf("cat=[android.intent.category.HOME]") < 0) return -1;
        int u = line.indexOf(" from uid ");
        if (u < 0) return -1;
        int s = u + 10, e = s;
        while (e < line.length() && Character.isDigit(line.charAt(e))) e++;
        if (e == s) return -1;
        // -T 1 replays the newest line, which may be an old press.
        try {
            String t = line.trim();
            int sp = t.indexOf(' ');
            long at = (long) (Double.parseDouble(t.substring(0, sp)) * 1000.0);
            if (at < notBefore) return -1;
        } catch (Throwable ignored) { }
        try { return Integer.parseInt(line.substring(s, e)); } catch (Throwable t) { return -1; }
    }

    /**
     * The code of a button pressed on a TV's remote, from the CEC agent's log line "Received
     * message: Source: 0x00; Destination: 0x04; Opcode: 0x44; Params: 0x72", or -1. Only the
     * press (0x44), not the release (0x45).
     */
    static int cecKey(String line, long notBefore) {
        if (line.indexOf("HdmiCecAgent") < 0 || line.indexOf("Opcode: 0x44") < 0) return -1;
        int p = line.indexOf("Params: 0x");
        if (p < 0) return -1;
        int s = p + 10, e = s;
        while (e < line.length() && Character.digit(line.charAt(e), 16) >= 0) e++;
        if (e == s) return -1;
        try {
            String t = line.trim();
            long at = (long) (Double.parseDouble(t.substring(0, t.indexOf(' '))) * 1000.0);
            if (at < notBefore) return -1;
        } catch (Throwable ignored) { }
        try { return Integer.parseInt(line.substring(s, e), 16); } catch (Throwable t) { return -1; }
    }

    private void send(int what, int arg) {
        Messenger c = client;
        if (c == null) return;
        try {
            c.send(Message.obtain(null, what, arg, 0));
        } catch (RemoteException e) {
            client = null;
        }
    }
}
