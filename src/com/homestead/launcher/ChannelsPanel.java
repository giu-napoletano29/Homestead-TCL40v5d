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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * "Canali TV": what an antenna's channel list would be on a TV with no tuner. A page of the
 * live channels offered by the streaming apps that are installed, each opening its app, on
 * the channel itself where the app takes a link for it. Only apps that are on the device
 * are listed, so the page is never a list of things that do nothing.
 */
final class ChannelsPanel {

    /** One live channel, or an app whose live channels are chosen inside it. */
    private static final class Entry {
        final String group, name, pkg, uri;
        Entry(String group, String name, String pkg, String uri) {
            this.group = group; this.name = name; this.pkg = pkg; this.uri = uri;
        }
    }

    private static final String RAIPLAY = "it.rainet.androidtv";
    private static final String INFINITY = "it.mediaset.infinitytv1";

    private static final String G_RAI = "Rai";
    private static final String G_MEDIASET = "Mediaset";
    private static final String G_OTHER = "Altre app con dirette";

    /**
     * In the order the page lists them. RaiPlay takes https://www.raiplay.it/dirette/<channel>
     * and starts that channel's live stream (tried with Rai 1 and Rai Movie on a Fire TV
     * Stick). Mediaset Infinity publishes no link per channel, so it opens on its own home,
     * which has an "on air now" row.
     */
    private static final Entry[] CATALOGUE = {
            rai("Rai 1", "rai1"), rai("Rai 2", "rai2"), rai("Rai 3", "rai3"),
            rai("Rai 4", "rai4"), rai("Rai 5", "rai5"), rai("Rai Movie", "raimovie"),
            rai("Rai Premium", "raipremium"), rai("Rai Gulp", "raigulp"),
            rai("Rai YoYo", "raiyoyo"), rai("Rai Storia", "raistoria"),
            rai("Rai Scuola", "raiscuola"),
            new Entry(G_RAI, "RaiPlay", RAIPLAY, null),
            new Entry(G_MEDIASET, "Mediaset Infinity", INFINITY, null),
            new Entry(G_OTHER, "Plex (canali gratuiti)", "com.plexapp.android", null),
            new Entry(G_OTHER, "Pluto TV", "tv.pluto.android", null),
            new Entry(G_OTHER, "discovery+", "com.discovery.discoplus", null),
            new Entry(G_OTHER, "NOW", "com.nowtv.it", null),
            new Entry(G_OTHER, "DAZN", "com.dazn", null),
    };

    private static Entry rai(String name, String slug) {
        return new Entry(G_RAI, name, RAIPLAY, "https://www.raiplay.it/dirette/" + slug);
    }

    private ChannelsPanel() { }

    /** The catalogue entries whose app is installed and switched on. */
    private static List<Entry> available(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (installed(pm, CATALOGUE[i].pkg)) out.add(CATALOGUE[i]);
        }
        return out;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether there is anything to list; the tile is on the home screen only if so. */
    static boolean any(Context c) {
        return !available(c).isEmpty();
    }

    /** Names of what is listed, for the model's change detection. */
    static String signature(Context c) {
        StringBuilder sb = new StringBuilder();
        List<Entry> list = available(c);
        for (int i = 0; i < list.size(); i++) sb.append(list.get(i).name).append(',');
        return sb.toString();
    }

    static View build(final Context c, final SettingsPanel.Host host) {
        Panel p = new Panel(c, "Canali TV",
                "Le dirette delle app installate; ogni canale apre la sua app", Panel.SHEET);
        List<Entry> list = available(c);
        String group = null;
        for (int i = 0; i < list.size(); i++) {
            final Entry e = list.get(i);
            if (!e.group.equals(group)) {
                group = e.group;
                p.addHeader(group);
            }
            p.addRow(e.name, e.uri != null ? "diretta" : "apre l'app", new Runnable() {
                public void run() { open(c, e); }
            }).opens();
        }
        p.addNote("La prima apertura di un'app puo' richiedere qualche secondo. Mediaset Infinity "
                + "si apre sulla sua home, dove c'e' la riga \"Ora in onda\".");
        p.addRow("Indietro", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** Start the channel on its app, or just the app if it does not take the link. */
    private static void open(Context c, Entry e) {
        // The overlay (if this page is in it) must not stay over the app.
        HomeRedirectService.hideOverlay();
        if (e.uri != null) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(e.uri));
                i.setPackage(e.pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return;
            } catch (Throwable ignored) { }
        }
        try {
            Intent f = c.getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (f != null) {
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(f);
                return;
            }
        } catch (Throwable ignored) { }
        Toast.makeText(c, e.name + " non si apre", Toast.LENGTH_SHORT).show();
    }
}
