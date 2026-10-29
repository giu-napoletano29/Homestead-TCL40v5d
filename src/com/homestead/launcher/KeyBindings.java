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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;

/**
 * What the four coloured buttons of a TV's remote do. They reach the Fire TV through HDMI-CEC
 * (a Samsung sends them on, but keeps its Home and Guide buttons for itself), and almost no
 * Fire TV app uses them, so here each can open Home, a page of this launcher, or an app.
 *
 * A binding applies only to a key that came over HDMI, so the Fire TV's own remote and the
 * apps are untouched. The keys are read from the system log (HomeKeyWatcher), since Android
 * does not give HDMI-CEC keys to an accessibility service.
 */
final class KeyBindings {

    static final String OFF = "off";
    static final String HOME = "home";
    static final String CHANNELS = "channels";
    static final String SETTINGS = "settings";
    static final String APP = "app:";

    static final int[] KEYS = {
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN,
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE };
    private static final String[] NAMES = { "Red", "Green", "Yellow", "Blue" };

    private KeyBindings() { }

    /** The Android key of a CEC user-control code (0x71 blue, 0x72 red, 0x73 green, 0x74 yellow), or -1. */
    static int keyForCec(int cec) {
        switch (cec) {
            case 0x72: return KeyEvent.KEYCODE_PROG_RED;
            case 0x73: return KeyEvent.KEYCODE_PROG_GREEN;
            case 0x74: return KeyEvent.KEYCODE_PROG_YELLOW;
            case 0x71: return KeyEvent.KEYCODE_PROG_BLUE;
            default: return -1;
        }
    }

    static boolean isBindable(int keyCode) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode) return true;
        return false;
    }

    static String nameOf(int keyCode) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode) return NAMES[i];
        return "Key " + keyCode;
    }

    /** True when at least one coloured button has something to do. */
    static boolean anyOn(Context c) {
        for (int i = 0; i < KEYS.length; i++) {
            if (!OFF.equals(Prefs.tvKey(c, KEYS[i]))) return true;
        }
        return false;
    }

    static String label(Context c, String action) {
        if (action == null || OFF.equals(action)) return "Off";
        if (HOME.equals(action)) return "Home";
        if (CHANNELS.equals(action)) return "Canali TV";
        if (SETTINGS.equals(action)) return "Salotto settings";
        if (action.startsWith(APP)) {
            String pkg = action.substring(APP.length());
            try {
                PackageManager pm = c.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable t) {
                return pkg;
            }
        }
        return "Off";
    }

    /** Do what a button is bound to. {@code svc} is for the Home action; it may be null. */
    static void run(Context c, AccessibilityService svc, String action) {
        if (action == null || OFF.equals(action)) return;
        if (HOME.equals(action)) {
            if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        if (CHANNELS.equals(action) || SETTINGS.equals(action)) {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            i.putExtra(MainActivity.EXTRA_OPEN, action);
            try { c.startActivity(i); } catch (Throwable ignored) { }
            return;
        }
        if (action.startsWith(APP)) {
            try {
                Intent f = c.getPackageManager().getLaunchIntentForPackage(action.substring(APP.length()));
                if (f != null) {
                    f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(f);
                }
            } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- the settings pages

    static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "TV remote buttons", null, Panel.SHEET);
        p.addHeader("Coloured buttons");
        for (int i = 0; i < KEYS.length; i++) {
            final int key = KEYS[i];
            p.addRow(nameOf(key), label(c, Prefs.tvKey(c, key)), new Runnable() {
                public void run() { host.showPanel(buildChoose(c, host, key)); }
            }).opens().setHelp("What the " + nameOf(key).toLowerCase() + " button of the TV's "
                    + "remote does when it reaches the Fire TV over HDMI-CEC.");
        }
        p.addNote("The TV must pass the button on: some keep their own Home, Guide and Info "
                + "for themselves. A Fire TV remote's coloured buttons, and apps, are not "
                + "affected.");
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static View buildChoose(final Context c, final SettingsPanel.Host host, final int key) {
        final Panel p = new Panel(c, nameOf(key) + " button", null, Panel.SHEET);
        String cur = Prefs.tvKey(c, key);
        addChoice(c, host, p, key, cur, OFF, "Off");
        addChoice(c, host, p, key, cur, HOME, "Home");
        addChoice(c, host, p, key, cur, CHANNELS, "Canali TV");
        addChoice(c, host, p, key, cur, SETTINGS, "Salotto settings");
        p.addRow("An app...", cur.startsWith(APP) ? label(c, cur) : "", new Runnable() {
            public void run() { host.showPanel(buildApps(c, host, key)); }
        }).opens();
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void addChoice(final Context c, final SettingsPanel.Host host, Panel p,
            final int key, String cur, final String action, String title) {
        p.addRow(title, action.equals(cur) ? "in use" : "", new Runnable() {
            public void run() {
                set(c, key, action);
                host.closePanel();
                host.closePanel();
                host.showPanel(build(c, host));
            }
        });
    }

    private static View buildApps(final Context c, final SettingsPanel.Host host, final int key) {
        final Panel p = new Panel(c, nameOf(key) + ": an app", null, Panel.SHEET);
        List<AppModel.Item> all = AppModel.allItems();
        String cur = Prefs.tvKey(c, key);
        for (int i = 0; i < all.size(); i++) {
            final AppModel.Item it = all.get(i);
            if (it.type != AppModel.TYPE_APP || it.pkg == null) continue;
            final String action = APP + it.pkg;
            p.addRow(it.label, action.equals(cur) ? "in use" : "", new Runnable() {
                public void run() {
                    set(c, key, action);
                    host.closePanel();
                    host.closePanel();
                    host.closePanel();
                    host.showPanel(build(c, host));
                }
            });
        }
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void set(Context c, int key, String action) {
        Prefs.setTvKey(c, key, action);
    }
}
