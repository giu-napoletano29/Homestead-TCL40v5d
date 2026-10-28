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

    static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, "Screen & sound", null, Panel.SHEET);

        p.addHeader("HDMI-CEC");
        addSwitch(c, p, "Turn the TV off too",
                new Setting(true, "hdmi_control_auto_device_off_enabled", false),
                "When this Stick goes to sleep, it also tells the TV to turn off.");
        addSwitch(c, p, "Sleep when the TV does",
                new Setting(false, "block_cec_standby", true),
                "When the TV goes to standby, this Stick sleeps with it. Off blocks the "
                        + "TV's standby signal and keeps the Stick awake.");

        p.addHeader("Screen");
        addSwitch(c, p, "Screen mirroring (Miracast)",
                new Setting(true, "wifi_display_on", false),
                "Lets a phone or tablet show its screen here. Just the switch Amazon's own "
                        + "page has behind it - no discovery screen of its own.");

        p.addHeader("Picture");
        addOverscan(c, p);
        addColorDepth(c, p);

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- a plain on/off value

    /** One Settings.Global or Settings.Secure boolean, "0"/"1", optionally read the other way round. */
    private static final class Setting {
        final boolean global;
        final String key;
        final boolean invert;
        Setting(boolean g, String k, boolean inv) { global = g; key = k; invert = inv; }

        boolean read(Context c) {
            String v;
            try {
                v = global ? Settings.Global.getString(c.getContentResolver(), key)
                        : Settings.Secure.getString(c.getContentResolver(), key);
            } catch (Throwable t) { v = null; }
            boolean on = "1".equals(v);
            return invert != on;
        }

        String command(boolean on) {
            String v = (invert != on) ? "1" : "0";
            return "settings put " + (global ? "global " : "secure ") + key + " " + v;
        }

        void writeDirect(Context c, boolean on) {
            String v = (invert != on) ? "1" : "0";
            try {
                if (global) Settings.Global.putString(c.getContentResolver(), key, v);
                else Settings.Secure.putString(c.getContentResolver(), key, v);
            } catch (Throwable t) { /* falls back to the ADB link */ }
        }
    }

    private static void addSwitch(final Context c, Panel p, String label, final Setting s,
            String help) {
        final Panel.Row row = p.addRow(label, onOff(s.read(c)), null).asSwitch();
        row.setHelp(help);
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final boolean n = !s.read(c);
                if (!s.global) {
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

    // ---------------------------------------------------------------- status-only rows

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
