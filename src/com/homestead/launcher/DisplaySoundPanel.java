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

import android.content.Context;
import android.provider.Settings;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A handful of screen and sound controls that Amazon's own settings keep behind
 * com.amazon.tv.permission.LAUNCHER_SETTINGS (a signature permission only Amazon's apps can
 * hold - see SystemSettingsPanel). Each one here is nothing more than a plain system value,
 * though, so Homestead reads and writes it directly: no root, no Amazon launcher, and it
 * keeps working after Amazon's launcher is switched off.
 *
 * Reading never needs a permission. Writing does: Settings.Secure takes the
 * WRITE_SECURE_SETTINGS this app already holds, but Settings.Global needs WRITE_SETTINGS,
 * which it does not - so a Global write goes out over the ADB link instead (the one Setup
 * uses), the same way HealthPanel applies its checks when it is not held directly.
 *
 * Colour depth cannot be offered as a control (a bad value can make the picture unstable), so
 * it is shown as status only, with a note. Overscan has a switch and a stepped amount: a wrong
 * amount only crops or shrinks the picture, and the switch undoes it.
 */
final class DisplaySoundPanel {

    private DisplaySoundPanel() { }

    /**
     * True on a TV that is itself the screen (the TCL), false on a stick that plugs into one.
     * HDMI-CEC with "the TV", overscan and the HDMI colour depth belong to the stick's HDMI
     * output; a TV has none of them (their settings are not even defined there), so they are
     * left out. Found from the HDMI device type the build declares (0 is a TV), or a tuner
     * in the hardware, which only a TV has.
     */
    static boolean builtInTv(Context c) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String type = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "ro.hdmi.device_type", "");
            if (type != null && type.trim().length() > 0) return type.trim().startsWith("0");
        } catch (Throwable ignored) { }
        try {
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager)
                    c.getSystemService(Context.TV_INPUT_SERVICE);
            List<android.media.tv.TvInputInfo> list = tim == null ? null : tim.getTvInputList();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (AppModel.isHardwareTuner(list.get(i))) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Screen & sound", null, Panel.SHEET);
        boolean tv = builtInTv(c);

        if (!tv) {
            p.addHeader("HDMI-CEC");
            addSwitch(c, p, "Turn the TV off too",
                    new Setting(true, "hdmi_control_auto_device_off_enabled", false),
                    "When this Stick goes to sleep, it also tells the TV to turn off.");
            addSwitch(c, p, "Sleep when the TV does",
                    new Setting(false, "block_cec_standby", true),
                    "When the TV goes to standby, this Stick sleeps with it. Off blocks the "
                            + "TV's standby signal and keeps the Stick awake.");
        }

        p.addHeader("Power");
        addAutoSleep(c, p);
        // Only a TV has a light to switch off; a Stick Lite has none.
        if (tv && get(c, Setting.SECURE, "power_led_enabled") != null) {
            addSwitch(c, p, "Power LED",
                    new Setting(Setting.SECURE, "power_led_enabled", false),
                    "The small light on the TV. Off keeps it dark, for a room where it would "
                            + "be seen.");
        }

        p.addHeader("Screen");
        addSwitch(c, p, "Screen mirroring (Miracast)",
                new Setting(true, "wifi_display_on", false),
                "Lets a phone or tablet show its screen here. Just the switch Amazon's own "
                        + "page has behind it - no discovery screen of its own.");
        addScreensaver(c, p);

        p.addHeader("Sound");
        addSwitch(c, p, "Interface sounds",
                new Setting(Setting.SYSTEM, "sound_effects_enabled", false),
                "The clicks when you move around the menus, here and in Amazon's own.");
        if (get(c, Setting.SECURE, LIVETV_AUDIO) != null) addLiveTvLanguage(c, p);

        if (!tv) {
            p.addHeader("Picture");
            addOverscan(c, p);
            addColorDepth(c, p);
        }

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- a plain on/off value

    /** One Settings.Global or Settings.Secure boolean, "0"/"1", optionally read the other way round. */
    private static final class Setting {
        static final int GLOBAL = 0, SECURE = 1, SYSTEM = 2;
        final int ns;
        final String key;
        final boolean invert;
        Setting(boolean global, String k, boolean inv) {
            this(global ? GLOBAL : SECURE, k, inv);
        }
        Setting(int ns, String k, boolean inv) { this.ns = ns; key = k; invert = inv; }

        /** Secure is written in-process (WRITE_SECURE_SETTINGS); the others go over ADB. */
        boolean direct() { return ns == SECURE; }

        boolean read(Context c) {
            return invert != "1".equals(get(c, ns, key));
        }

        String command(boolean on) {
            return put(ns, key, (invert != on) ? "1" : "0");
        }

        void writeDirect(Context c, boolean on) {
            putDirect(c, ns, key, (invert != on) ? "1" : "0");
        }
    }

    private static String nsName(int ns) {
        return ns == Setting.GLOBAL ? "global" : ns == Setting.SECURE ? "secure" : "system";
    }

    private static String get(Context c, int ns, String key) {
        try {
            android.content.ContentResolver r = c.getContentResolver();
            return ns == Setting.GLOBAL ? Settings.Global.getString(r, key)
                    : ns == Setting.SECURE ? Settings.Secure.getString(r, key)
                    : Settings.System.getString(r, key);
        } catch (Throwable t) { return null; }
    }

    private static String put(int ns, String key, String value) {
        return "settings put " + nsName(ns) + " " + key + " " + value;
    }

    private static void putDirect(Context c, int ns, String key, String value) {
        try {
            android.content.ContentResolver r = c.getContentResolver();
            if (ns == Setting.GLOBAL) Settings.Global.putString(r, key, value);
            else if (ns == Setting.SECURE) Settings.Secure.putString(r, key, value);
            else Settings.System.putString(r, key, value);
        } catch (Throwable t) { /* falls back to the ADB link */ }
    }

    /**
     * Writes values and calls back on the main thread with whether they all took: in-process
     * for Secure, over the ADB link otherwise (and for Secure if the direct write did not stick).
     */
    private static void write(final Context c, final int ns, final String[] keys,
                              final String[] values, final Runnable after) {
        if (ns == Setting.SECURE) {
            boolean stuck = true;
            for (int i = 0; i < keys.length; i++) {
                putDirect(c, ns, keys[i], values[i]);
                if (!values[i].equals(get(c, ns, keys[i]))) stuck = false;
            }
            if (stuck) { after.run(); return; }
        }
        if (!AdbBridge.canRun(c)) { after.run(); return; }
        String[] cmds = new String[keys.length];
        for (int i = 0; i < keys.length; i++) cmds[i] = put(ns, keys[i], values[i]);
        AdbBridge.run(cmds, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) { after.run(); }
        });
    }

    private static void addSwitch(final Context c, Panel p, String label, final Setting s,
            String help) {
        final Panel.Row row = p.addRow(label, onOff(s.read(c)), null).asSwitch();
        row.setHelp(help);
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final boolean n = !s.read(c);
                if (s.direct()) {
                    // WRITE_SECURE_SETTINGS is already held: applies at once, no ADB needed.
                    s.writeDirect(c, n);
                    row.setValue(onOff(n));
                    return;
                }
                if (!AdbBridge.canRun(c)) {
                    row.setValue("needs Setup");
                    return;
                }
                row.setValue("working...");
                AdbBridge.run(new String[]{ s.command(n) }, new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) { row.setValue(onOff(s.read(c))); }
                });
            }
        });
    }

    // ---------------------------------------------------------------- screensaver

    private static final String DREAM_COLORS = "com.android.dreams.basic/.Colors";

    /** What the TV shows when idle: nothing, Android's colours, or the Amazon one (with ads). */
    private static String screensaverState(Context c) {
        if ("0".equals(get(c, Setting.SECURE, "screensaver_enabled"))) return "Off";
        String comp = get(c, Setting.SECURE, "screensaver_components");
        if (comp == null || comp.length() == 0) return "TV default";
        if (comp.contains("com.amazon.")) return "Amazon's";
        if (comp.contains(".Colors")) return "Colours";
        return "Other";
    }

    private static void addScreensaver(final Context c, Panel p) {
        final Panel.Row row = p.addRow("Screensaver", screensaverState(c), null);
        row.setHelp("What the TV shows after a few idle minutes. Off: nothing, and the TV "
                + "sleeps on its own timer. Colours: Android's changing colours. Amazon's: "
                + "the TV's own one, which carries ads. Homestead's idle clock is separate and "
                + "only runs on its own home screen. Press OK for the next choice.");
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String now = screensaverState(c);
                String amazon = get(c, Setting.SECURE, "screensaver_default_component");
                String[] keys, vals;
                // Colours -> Off -> Amazon's -> Colours: the ad one is never one press from Off.
                if ("Colours".equals(now)) {
                    keys = new String[]{ "screensaver_enabled" };
                    vals = new String[]{ "0" };
                } else if ("Off".equals(now) && amazon != null && amazon.length() > 0) {
                    keys = new String[]{ "screensaver_enabled", "screensaver_components" };
                    vals = new String[]{ "1", amazon };
                } else {
                    keys = new String[]{ "screensaver_enabled", "screensaver_components" };
                    vals = new String[]{ "1", DREAM_COLORS };
                }
                row.setValue("working...");
                write(c, Setting.SECURE, keys, vals, new Runnable() {
                    public void run() { row.setValue(screensaverState(c)); }
                });
            }
        });
    }

    // ---------------------------------------------------------------- Live TV language

    private static final String LIVETV_AUDIO = "com.amazon.tv.livetv.primary_audio_language";
    /** Live TV's name for the language, and the code the system keeps beside it. */
    private static final String[][] LANGUAGES = {
            { "ITALIAN", "it" }, { "ENGLISH", "en" }, { "GERMAN", "de" },
            { "FRENCH", "fr" }, { "SPANISH", "es" },
    };

    private static String languageName(String v) {
        if (v == null || v.length() == 0) return "TV default";
        return v.charAt(0) + v.substring(1).toLowerCase(java.util.Locale.US);
    }

    private static void addLiveTvLanguage(final Context c, Panel p) {
        final Panel.Row row = p.addRow("Live TV audio language",
                languageName(get(c, Setting.SECURE, LIVETV_AUDIO)), null);
        row.setHelp("The language Live TV picks when a channel has several soundtracks, from "
                + "the next channel change on. Press OK for the next language.");
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String cur = get(c, Setting.SECURE, LIVETV_AUDIO);
                int next = 0;
                for (int i = 0; i < LANGUAGES.length; i++) {
                    if (LANGUAGES[i][0].equalsIgnoreCase(cur)) { next = (i + 1) % LANGUAGES.length; break; }
                }
                row.setValue("working...");
                write(c, Setting.SECURE, new String[]{ LIVETV_AUDIO, "primary_audio_language" },
                        new String[]{ LANGUAGES[next][0], LANGUAGES[next][1] }, new Runnable() {
                            public void run() {
                                row.setValue(languageName(get(c, Setting.SECURE, LIVETV_AUDIO)));
                            }
                        });
            }
        });
    }

    // ---------------------------------------------------------------- status-only rows

    // ---------------------------------------------------------------- power and checks

    private static final String SLEEP_ON = "900000";   // Amazon's 15 minutes
    private static final String SCREEN_OFF_NEVER = "2147483647";

    /** Milliseconds of idle before the Stick sleeps by itself; 0 or less is never. */
    private static long sleepTimeout(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), "sleep_timeout");
            return v == null ? 0 : Long.parseLong(v.trim());
        } catch (Throwable t) { return 0; }
    }

    /**
     * Things on this page worth a look, one line each: what the Stick does that the user may
     * not want. The System settings hub shows how many there are.
     */
    static java.util.List<String> issues(Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (builtInTv(c)) return out;   // these are all about a stick behind a TV
        long t = sleepTimeout(c);
        if (t > 0) out.add("it sleeps by itself after " + (t / 60000) + " minutes");
        if ("1".equals(getSecure(c, "block_cec_standby"))) {
            out.add("it ignores the TV's standby, so it stays awake when the TV turns off");
        }
        if (!"1".equals(getGlobal(c, "hdmi_control_auto_device_off_enabled"))) {
            out.add("it does not turn the TV off when it sleeps");
        }
        if ("1".equals(getGlobal(c, "overscan_enabled"))) {
            out.add("overscan is on: black borders appear if the TV shows the picture 1:1");
        }
        return out;
    }

    private static String getSecure(Context c, String key) {
        try { return Settings.Secure.getString(c.getContentResolver(), key); }
        catch (Throwable t) { return null; }
    }

    /** A switch for the Stick's own 15-minute sleep; both settings it uses go out over ADB. */
    private static void addAutoSleep(final Context c, Panel p) {
        final boolean tv = builtInTv(c);
        final Panel.Row row = p.addRow("Sleep after 15 minutes idle",
                onOff(sleepTimeout(c) > 0), null).asSwitch();
        row.setHelp(tv
                ? "The TV goes to sleep by itself after 15 minutes without a button press. Off, "
                        + "it sleeps only when you put it to sleep."
                : "The Stick goes to sleep by itself after 15 minutes without a button press. "
                        + "Off, it sleeps only when the TV does (the switches above) or when "
                        + "you put it to sleep.");
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) { row.setValue("needs Setup"); return; }
                final boolean turnOn = sleepTimeout(c) <= 0;
                row.setValue("working...");
                AdbBridge.run(new String[]{
                        "settings put secure sleep_timeout " + (turnOn ? SLEEP_ON : "0"),
                        "settings put system screen_off_timeout "
                                + (turnOn ? SLEEP_ON : SCREEN_OFF_NEVER)
                }, new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        row.setValue(onOff(sleepTimeout(c) > 0));
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- overscan

    /** How far the picture is taken in from every edge, as a share of the screen. */
    private static final float[] OVERSCAN_STEPS = { 0f, 0.01f, 0.02f, 0.03f, 0.04f, 0.05f };

    /** The first of the four per-side values, or 0 when unset. */
    private static float overscanNow(Context c) {
        String v = getGlobal(c, "overscan_values");
        if (v == null) return 0f;
        try { return Float.parseFloat(v.trim().split("\\s+")[0]); }
        catch (Throwable t) { return 0f; }
    }

    private static String percent(float f) {
        return String.format(java.util.Locale.US, "%.1f%%", f * 100f);
    }

    /**
     * Overscan: a switch for Amazon's correction (it shrinks the picture in from the edges, to
     * make up for a TV that crops them), and a row that steps the amount, the same on all four
     * sides. Both go out over the ADB link, since they are Settings.Global.
     */
    private static void addOverscan(final Context c, Panel p) {
        final Panel.Row sw = p.addRow("Overscan",
                onOff("1".equals(getGlobal(c, "overscan_enabled"))), null).asSwitch();
        sw.setHelp("Takes the picture in from the edges, for a TV that crops them. Leave it off "
                + "when the TV shows the picture 1:1 (for example in Film mode), or black "
                + "borders appear.");
        final Panel.Row amount = p.addRow("Overscan amount",
                percent(overscanNow(c)), null);
        amount.setHelp("How far in from each edge, the same on all four sides. Press OK for "
                + "the next step; it wraps round to 0. Only has an effect while Overscan is on.");

        sw.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) { sw.setValue("needs Setup"); return; }
                final boolean n = !"1".equals(getGlobal(c, "overscan_enabled"));
                sw.setValue("working...");
                AdbBridge.run(new String[]{ "settings put global overscan_enabled " + (n ? 1 : 0) },
                        new AdbBridge.Callback() {
                            public void onDone(boolean ok, String out) {
                                sw.setValue(onOff("1".equals(getGlobal(c, "overscan_enabled"))));
                            }
                        });
            }
        });
        amount.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) { amount.setValue("needs Setup"); return; }
                float cur = overscanNow(c), next = OVERSCAN_STEPS[0];
                for (int i = 0; i < OVERSCAN_STEPS.length; i++) {
                    if (OVERSCAN_STEPS[i] > cur + 0.0005f) { next = OVERSCAN_STEPS[i]; break; }
                }
                String one = String.format(java.util.Locale.US, "%.6f", next);
                amount.setValue("working...");
                AdbBridge.run(new String[]{ "settings put global overscan_values \""
                        + one + " " + one + " " + one + " " + one + "\"" },
                        new AdbBridge.Callback() {
                            public void onDone(boolean ok, String out) {
                                amount.setValue(percent(overscanNow(c)));
                            }
                        });
            }
        });
    }

    private static void addColorDepth(final Context c, Panel p) {
        String cur = getGlobal(c, "hdr_bit_depth");
        final Panel.Row row = p.addRow("Colour depth", (cur == null ? "8" : cur) + " bit", null);
        row.setHelp("A value the TV does not support can make the picture unstable until it "
                + "is put back. Shown for reference; changed from Amazon's own page.");
    }

    private static String getGlobal(Context c, String key) {
        try { return Settings.Global.getString(c.getContentResolver(), key); }
        catch (Throwable t) { return null; }
    }

    private static String onOff(boolean on) { return on ? "on" : "off"; }
}
