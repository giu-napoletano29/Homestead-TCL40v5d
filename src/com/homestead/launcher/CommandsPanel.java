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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Fallback page: the adb commands to run from a computer for whatever is still missing on
 * this TV. Needed because Fire OS 7 refuses adb connections that originate on the device
 * itself (see AdbConnection.FAIL_SELF_REFUSED).
 */
public final class CommandsPanel {

    private static final int OK = 0xFFA7D28F;
    private static final int WARN = 0xFFE8C468;

    private CommandsPanel() { }

    public static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.cmd_title),
                c.getString(R.string.cmd_sub));

        String pkg = c.getPackageName();
        String ip = localAddress();

        p.addHeader(c.getString(R.string.cmd_connecting));
        p.addRow(c.getString(R.string.cmd_this_tv), ip == null ? c.getString(R.string.remote_no_network) : ip, null);
        p.addNote(c.getString(R.string.cmd_connect_note,
                ip == null ? c.getString(R.string.cmd_this_tv_arg) : ip));

        // ------------------------------------------------------------------ what is missing
        boolean svc = SetupPanel.serviceEnabled(c);
        boolean wss = HealthPanel.canWriteSettings(c);
        boolean logs = HomeKeyWatcher.permitted(c);

        p.addHeader(c.getString(R.string.cmd_missing));

        Panel.Row a = p.addRow(c.getString(R.string.cmd_home_redirect), svc ? c.getString(R.string.st_done) : c.getString(R.string.cmd_needed), null);
        a.setValueColor(svc ? OK : WARN);
        if (!svc) {
            p.addNote(c.getString(R.string.cmd_home_note, pkg));
        }

        Panel.Row w = p.addRow(c.getString(R.string.cmd_tuning), wss ? c.getString(R.string.st_done) : c.getString(R.string.cmd_needed), null);
        w.setValueColor(wss ? OK : WARN);
        if (!wss) {
            p.addNote(c.getString(R.string.cmd_tuning_note, pkg));
        }

        Panel.Row h = p.addRow(c.getString(R.string.cmd_catch_home), logs ? c.getString(R.string.st_done) : c.getString(R.string.cmd_needed), null);
        h.setValueColor(logs ? OK : WARN);
        if (!logs) {
            p.addNote(c.getString(R.string.cmd_logs_note, pkg));
        }

        // ------------------------------------------------------------------ packages
        StringBuilder off = new StringBuilder();
        int count = 0;
        for (int i = 0; i < Debloat.CATALOGUE.length; i++) {
            Debloat.Entry e = Debloat.CATALOGUE[i];
            if (e.caution) continue;                       // opt-in, not a blanket suggestion
            if (Debloat.isProtected(c, e.pkg)) continue;
            if (Debloat.stateOf(c, e.pkg) != Debloat.ENABLED) continue;
            off.append("adb shell pm disable-user --user 0 ").append(e.pkg).append('\n');
            count++;
        }

        p.addHeader(c.getString(R.string.cmd_ads));
        Panel.Row pkgs = p.addRow(c.getString(R.string.cmd_running), count == 0 ? c.getString(R.string.cmd_none) : String.valueOf(count), null);
        pkgs.setValueColor(count == 0 ? OK : WARN);
        if (count > 0) {
            p.addText(off.toString()
                    + c.getString(R.string.cmd_undo_note));
        } else {
            p.addNote(c.getString(R.string.cmd_none_note));
        }

        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** The address a computer on the same network would use to reach this TV. */
    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null || ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress addr = as.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
