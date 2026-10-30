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
import android.view.View;
import android.widget.Toast;

/**
 * Settings > Behaviour > Phone remote: the switch for RemoteServer, the way to pair a phone,
 * how long a phone stays signed in, and signing phones out. Off until the user turns it on.
 */
final class RemotePanel {

    private RemotePanel() { }

    static View build(final Context c, final SettingsPanel.Host host, final Runnable changed) {
        final Panel p = new Panel(c, c.getString(R.string.remote_title), null, Panel.SHEET);

        final Panel.Row on = p.addRow(c.getString(R.string.remote_title), label(c, Prefs.remoteOn(c)), null)
                .asSwitch();
        on.setHelp(c.getString(R.string.rp_help_switch));
        final Panel.Row address = p.addRow(c.getString(R.string.remote_address), "", null);
        address.setHelp(c.getString(R.string.rp_help_address));
        final Panel.Row pair = p.addRow(c.getString(R.string.remote_pair), "", null).opens();
        pair.setHelp(c.getString(R.string.rp_help_pair));
        final Panel.Row session = p.addRow(c.getString(R.string.remote_session), "", null);
        session.setHelp(c.getString(R.string.rp_help_session));
        final Panel.Row phones = p.addRow(c.getString(R.string.remote_phones), "", null);
        phones.setHelp(c.getString(R.string.rp_help_phones));
        final Panel.Row out = p.addRow(c.getString(R.string.remote_sign_out), "", null);
        out.setHelp(c.getString(R.string.rp_help_signout));

        final Runnable refresh = new Runnable() {
            public void run() {
                boolean enabled = Prefs.remoteOn(c);
                // Enabled first: setValue repaints the row, alpha included.
                address.cell.setEnabled(enabled);
                pair.cell.setEnabled(enabled);
                phones.cell.setEnabled(enabled);
                out.cell.setEnabled(enabled);
                on.setValue(label(c, enabled));
                String a = enabled ? RemoteServer.address() : null;
                address.setValue(!enabled ? "" : a != null ? a : c.getString(R.string.remote_no_network));
                pair.setValue("");
                session.setValue(sessionLabel(c, Prefs.remoteSessionDays(c)));
                phones.setValue(enabled ? String.valueOf(Prefs.remoteSessions(c)) : "");
                out.setValue("");
            }
        };
        on.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setRemoteOn(c, !Prefs.remoteOn(c));
                RemoteServer.ensure(c);
                refresh.run();
                if (changed != null) changed.run();
            }
        });
        pair.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Prefs.remoteOn(c)) host.showPanel(PairPanel.build(c, host));
            }
        });
        // Back from the pair screen: a phone may have paired meanwhile.
        pair.cell.setOnFocusChangeListener(chain(pair.cell.getOnFocusChangeListener(), refresh));
        session.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = Prefs.remoteSessionDays(c);
                int[] all = Prefs.REMOTE_SESSION_CHOICES;
                int next = all[0];
                for (int i = 0; i < all.length; i++) {
                    if (all[i] == cur) { next = all[(i + 1) % all.length]; break; }
                }
                Prefs.setRemoteSessionDays(c, next);
                session.setValue(sessionLabel(c, next));
            }
        });
        out.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Prefs.remoteOn(c)) return;
                Prefs.clearRemoteTokens(c);
                phones.setValue("0");
                Toast.makeText(c, R.string.remote_signed_out, Toast.LENGTH_SHORT).show();
            }
        });

        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        refresh.run();
        return p.root;
    }

    private static String label(Context c, boolean on) { return SettingsPanel.onOff(c, on); }

    static String sessionLabel(Context c, int days) {
        return days <= 0 ? c.getString(R.string.remote_session_never)
                : c.getResources().getQuantityString(R.plurals.remote_days, days, days);
    }

    /** Runs extra work after a row's own focus handling, when it gains focus. */
    private static View.OnFocusChangeListener chain(final View.OnFocusChangeListener first,
            final Runnable onGain) {
        return new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (first != null) first.onFocusChange(v, has);
                if (has) onGain.run();
            }
        };
    }
}
