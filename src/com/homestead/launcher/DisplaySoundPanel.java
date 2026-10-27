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
 * Two values this page cannot offer a real control for are shown as status only, with a
 * note: overscan (the safe values depend on the TV) and colour depth (a bad value can make
 * the picture unstable). Both still show what the TV is set to.
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
        addStatus(c, p, "Overscan", "overscan_enabled", "overscan_values",
                "Amazon's own page lets you nudge the picture in from the edges. The safe "
                        + "amount depends on the TV, so Homestead only shows whether it is on "
                        + "and can reset it - not adjust it.", host);
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

    private static void addStatus(final Context c, Panel p, String label, final String enabledKey,
            final String valuesKey, String help, final SettingsPanel.Host host) {
        boolean on = "1".equals(getGlobal(c, enabledKey));
        final Panel.Row row = p.addRow(label, on ? "on" : "off", null);
        row.setHelp(help);
        if (!on) return;
        row.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!AdbBridge.canRun(c)) { row.setValue("needs Setup"); return; }
                row.setValue("resetting...");
                AdbBridge.run(new String[]{
                        "settings put global " + enabledKey + " 0",
                        "settings put global " + valuesKey + " \"0.0 0.0 0.0 0.0\""
                }, new AdbBridge.Callback() {
                    public void onDone(boolean ok, String out) {
                        row.setValue("1".equals(getGlobal(c, enabledKey)) ? "on" : "off");
                        row.cell.setOnClickListener(null);
                    }
                });
            }
        });
        row.setBadge("tap to reset");
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
