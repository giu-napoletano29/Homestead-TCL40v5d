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

import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * A small process that presses the remote's keys for the phone remote, much faster than
 * `input keyevent`, whose every run starts a new Java process (about 0.7 s on a Stick).
 *
 * It is started once over the on-device ADB link, so it runs with the shell's privileges
 * (the same as `input`), as `app_process` with this APK on its class path. It listens on
 * 127.0.0.1 only, takes lines of "&lt;secret&gt; key &lt;code&gt;", "&lt;secret&gt; ping" and
 * "&lt;secret&gt; quit", presses only the codes in {@link #CODES}, and exits by itself after a
 * while without a line. The secret is made fresh each time it is started and is known only to
 * the app. It does not run in the app's own process and must not touch Android classes that
 * need a Looper or a Context.
 */
public final class KeyDaemon {

    /** The only keys it will press, and the only ones the phone can name (see RemoteKeys). */
    static final int[] CODES = {
            19, 20, 21, 22, 23, 4, 3, 82,
            85, 89, 90, 24, 25, 164 };

    private static final long IDLE_EXIT_MS = 75000L;

    private static volatile long lastLine = SystemClock.uptimeMillis();
    private static Object inputManager;
    private static Method inject;

    private KeyDaemon() { }

    public static void main(String[] args) {
        if (args.length < 2) return;
        final int port;
        try { port = Integer.parseInt(args[0]); } catch (NumberFormatException e) { return; }
        final String secret = args[1];
        try {
            init();
            ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(5000);
            while (SystemClock.uptimeMillis() - lastLine < IDLE_EXIT_MS) {
                try {
                    final Socket s = server.accept();
                    Thread t = new Thread(new Runnable() {
                        public void run() { serve(s, secret); }
                    });
                    t.setDaemon(true);
                    t.start();
                } catch (SocketTimeoutException idle) {
                    // Looking at the clock again.
                }
            }
            server.close();
        } catch (Throwable t) {
            // Nothing to report to: the app notices the socket never opened.
        }
        System.exit(0);
    }

    private static void init() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "input");
        Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
        inputManager = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent.class, int.class);
    }

    private static void serve(Socket s, String secret) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "US-ASCII"), 256);
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() > 40) break;
                String[] w = line.split(" ");
                if (w.length < 2 || !secret.equals(w[0])) break;
                lastLine = SystemClock.uptimeMillis();
                if ("quit".equals(w[1])) {
                    System.exit(0);
                } else if ("key".equals(w[1]) && w.length == 3) {
                    int code = Integer.parseInt(w[2]);
                    if (allowed(code)) press(code);
                }
            }
        } catch (Throwable t) {
            // A broken or malformed line ends this connection and nothing else.
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    static boolean allowed(int code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i] == code) return true;
        return false;
    }

    private static void press(int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        send(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private static void send(KeyEvent e) throws Exception {
        inject.invoke(inputManager, e, 0);   // 0: do not wait for the event to be handled
    }
}
