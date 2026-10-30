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
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What the search can find: the apps on the home screen (not the ones the user hid) and the
 * live channels of Canali TV. Everything is already in memory, so a search is a filter over a
 * few dozen names; nothing runs in the background and nothing leaves the TV.
 *
 * Built and used on the main thread only (it reads AppModel). The same index serves the
 * search page on the TV and the phone remote (RemoteServer).
 */
final class SearchIndex {

    /** A match. {@code id} is "app:&lt;package&gt;" or "ch:&lt;channel&gt;", the form open() takes. */
    static final class Hit {
        final String id, title, hint;
        final String norm;
        Hit(String id, String title, String hint) {
            this.id = id; this.title = title; this.hint = hint;
            this.norm = fold(title);
        }
    }

    private final List<Hit> all = new ArrayList<Hit>();

    SearchIndex(Context c) {
        AppModel.ensureLoaded(c);
        List<AppModel.Item> items = AppModel.visibleItems(c);
        for (int i = 0; i < items.size(); i++) {
            AppModel.Item it = items.get(i);
            if (it.type != AppModel.TYPE_APP || it.pkg == null || it.label == null) continue;
            if (special(it.pkg)) continue;
            all.add(new Hit("app:" + it.pkg, it.label, null));
        }
        List<String[]> channels = ChannelsPanel.searchable(c);
        for (int i = 0; i < channels.size(); i++) {
            String[] ch = channels.get(i);
            // An app that lists its channels inside itself is on the page as an entry named
            // like the app: the app is already a match, so it is not offered twice.
            boolean twin = false;
            for (int k = 0; k < all.size() && !twin; k++) {
                twin = all.get(k).id.startsWith("app:") && all.get(k).norm.equals(fold(ch[1]));
            }
            if (!twin) all.add(new Hit("ch:" + ch[0], ch[1], ch[2]));
        }
    }

    /**
     * Apps Fire OS lets only its own Settings open: a direct launch from here would do
     * nothing, so they are left out rather than found and then dead.
     */
    private static boolean special(String pkg) {
        return AppModel.AIRPLAY_PKG.equals(pkg) || AppModel.PROFILE_PICKER_PKG.equals(pkg)
                || AppModel.AMAZON_KIDS_PKG.equals(pkg) || AppModel.WSIW_PKG.equals(pkg);
    }

    /**
     * Matches for what was typed, best first: names that start with it, then names with a
     * word that starts with it, then names that contain it. Empty text returns nothing.
     */
    List<Hit> find(String query, int max) {
        final String q = fold(query).trim();
        List<Hit> out = new ArrayList<Hit>();
        if (q.length() == 0) return out;
        final java.util.Map<Hit, Integer> rank = new java.util.HashMap<Hit, Integer>();
        for (int i = 0; i < all.size(); i++) {
            Hit h = all.get(i);
            int r;
            if (h.norm.startsWith(q)) r = 0;
            else if (h.norm.contains(" " + q)) r = 1;
            // One letter only matches where a name or a word starts: "n" inside "Boing" is noise.
            else if (q.length() > 1 && h.norm.contains(q)) r = 2;
            else continue;
            rank.put(h, r);
            out.add(h);
        }
        Collections.sort(out, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) {
                int d = rank.get(a) - rank.get(b);
                return d != 0 ? d : a.norm.compareTo(b.norm);
            }
        });
        return out.size() > max ? new ArrayList<Hit>(out.subList(0, max)) : out;
    }

    /** The apps used most recently, for the page before anything is typed. */
    static List<Hit> recent(Context c, int max) {
        AppModel.ensureLoaded(c);
        List<AppModel.Item> items = new ArrayList<AppModel.Item>();
        List<AppModel.Item> vis = AppModel.visibleItems(c);
        for (int i = 0; i < vis.size(); i++) {
            AppModel.Item it = vis.get(i);
            if (it.type == AppModel.TYPE_APP && it.pkg != null && it.label != null
                    && it.lastUsed > 0 && !special(it.pkg)) {
                items.add(it);
            }
        }
        Collections.sort(items, new Comparator<AppModel.Item>() {
            public int compare(AppModel.Item a, AppModel.Item b) {
                return a.lastUsed == b.lastUsed ? 0 : (a.lastUsed < b.lastUsed ? 1 : -1);
            }
        });
        List<Hit> out = new ArrayList<Hit>();
        for (int i = 0; i < items.size() && out.size() < max; i++) {
            out.add(new Hit("app:" + items.get(i).pkg, items.get(i).label, null));
        }
        return out;
    }

    /** Lower case and without accents, so "citta" finds "Città". */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.getDefault()), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }

    /** Opens a match: the app, or the channel in its streaming app. Main thread. */
    static boolean open(Context c, String id) {
        if (id == null) return false;
        if (id.startsWith("ch:")) {
            return ChannelsPanel.openById(c, id.substring(3));
        }
        if (!id.startsWith("app:")) return false;
        String pkg = id.substring(4);
        AppModel.Item found = null;
        List<AppModel.Item> items = AppModel.allItems();
        for (int i = 0; i < items.size(); i++) {
            AppModel.Item it = items.get(i);
            if (it.type == AppModel.TYPE_APP && pkg.equals(it.pkg)) { found = it; break; }
        }
        if (found == null || special(pkg)) return false;
        // The overlay (if the home screen is in it) must not stay over the app.
        HomeRedirectService.hideOverlay();
        AppModel.noteLaunched(c, found);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(found.pkg, found.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(i);
            return true;
        } catch (Throwable t) { }
        try {
            Intent f = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (f != null) {
                f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(f);
                return true;
            }
        } catch (Throwable t) { }
        Toast.makeText(c, found.label, Toast.LENGTH_SHORT).show();
        return false;
    }
}
