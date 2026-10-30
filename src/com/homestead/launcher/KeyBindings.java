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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the buttons of a TV's remote do. They reach the Fire TV through HDMI-CEC (a Samsung
 * sends many on, but keeps its Home and Guide buttons for itself), and almost no Fire TV app
 * uses the coloured ones or Exit, so here each can open Home, a page of this launcher, or an
 * app.
 *
 * Which buttons a TV passes on, and under which CEC code, differs from one TV and remote to
 * the next, so any button can be added: the page waits for the next one pressed and learns its
 * code. The four coloured buttons and Exit are always listed.
 *
 * A binding applies only to a key that came over HDMI, so the Fire TV's own remote and the
 * apps are untouched. The keys are read from the system log (HomeKeyWatcher), since Android
 * does not give HDMI-CEC keys to an accessibility service. The log only tells us a button was
 * pressed: it is not taken away from the system, so whatever the button already did still
 * happens as well.
 */
final class KeyBindings {

    static final String OFF = "off";
    static final String HOME = "home";
    static final String CHANNELS = "channels";
    static final String SETTINGS = "settings";
    static final String APP = "app:";
    /** "channel:<id>": one of the channels of the Canali TV page (ChannelsPanel). */
    static final String CHANNEL = "channel:";

    /**
     * Always listed: the four coloured buttons (CEC F2 red, F3 green, F4 yellow, F1 blue), then
     * Exit, which a Samsung sends as the CEC code "Clear" (0x2C), five presses out of five
     * on a Stick with a Samsung TV.
     */
    static final int[] DEFAULT_CODES = { 0x72, 0x73, 0x74, 0x71, 0x2C };
    /** How many of DEFAULT_CODES are coloured buttons; the rest are listed under "Other buttons". */
    private static final int COLOURED = 4;

    private KeyBindings() { }

    /** A button that has to keep doing what it does: Select, the arrows and Exit/Back (0x0D). */
    static boolean reserved(int cec) {
        return cec <= 0x08 || cec == 0x0D;
    }

    static boolean isDefault(int cec) {
        for (int i = 0; i < DEFAULT_CODES.length; i++) if (DEFAULT_CODES[i] == cec) return true;
        return false;
    }

    /** Where a coloured button's (or Exit's) choice was kept before buttons were stored by CEC code, or -1. */
    static int legacyKey(int cec) {
        switch (cec) {
            case 0x72: return KeyEvent.KEYCODE_PROG_RED;
            case 0x73: return KeyEvent.KEYCODE_PROG_GREEN;
            case 0x74: return KeyEvent.KEYCODE_PROG_YELLOW;
            case 0x71: return KeyEvent.KEYCODE_PROG_BLUE;
            case 0x2C: return KeyEvent.KEYCODE_CLEAR;
            default: return -1;
        }
    }

    /** The CEC "user control" names, for the buttons a remote is likely to have. */
    static String nameOf(Context c, int cec) {
        if (cec >= 0x20 && cec <= 0x29) return c.getString(R.string.keys_number, cec - 0x20);
        switch (cec) {
            case 0x72: return c.getString(R.string.keys_red);
            case 0x73: return c.getString(R.string.keys_green);
            case 0x74: return c.getString(R.string.keys_yellow);
            case 0x71: return c.getString(R.string.keys_blue);
            case 0x75: return c.getString(R.string.kn_f5);
            case 0x76: return c.getString(R.string.kn_data);
            case 0x2C: return c.getString(R.string.kn_exit);
            case 0x09: return c.getString(R.string.kn_menu);
            case 0x0A: return c.getString(R.string.kn_setup);
            case 0x0B: return c.getString(R.string.kn_contents);
            case 0x0C: return c.getString(R.string.kn_favourites);
            case 0x10: return c.getString(R.string.kn_media_top);
            case 0x11: return c.getString(R.string.kn_media_menu);
            case 0x2A: return c.getString(R.string.kn_dot);
            case 0x2B: return c.getString(R.string.kn_enter);
            case 0x2F: return c.getString(R.string.kn_next_fav);
            case 0x30: return c.getString(R.string.kn_ch_up);
            case 0x31: return c.getString(R.string.kn_ch_down);
            case 0x32: return c.getString(R.string.kn_prev_ch);
            case 0x33: return c.getString(R.string.kn_sound);
            case 0x34: return c.getString(R.string.kn_source);
            case 0x35: return c.getString(R.string.kn_info);
            case 0x36: return c.getString(R.string.kn_help);
            case 0x37: return c.getString(R.string.kn_page_up);
            case 0x38: return c.getString(R.string.kn_page_down);
            case 0x40: return c.getString(R.string.kn_power);
            case 0x41: return c.getString(R.string.kn_vol_up);
            case 0x42: return c.getString(R.string.kn_vol_down);
            case 0x43: return c.getString(R.string.kn_mute);
            case 0x44: return c.getString(R.string.kn_play);
            case 0x45: return c.getString(R.string.kn_stop);
            case 0x46: return c.getString(R.string.kn_pause);
            case 0x47: return c.getString(R.string.kn_record);
            case 0x48: return c.getString(R.string.kn_rewind);
            case 0x49: return c.getString(R.string.kn_ffwd);
            case 0x4A: return c.getString(R.string.kn_eject);
            case 0x4B: return c.getString(R.string.kn_next);
            case 0x4C: return c.getString(R.string.kn_previous);
            case 0x50: return c.getString(R.string.kn_angle);
            case 0x51: return c.getString(R.string.kn_subtitles);
            case 0x52: return c.getString(R.string.kn_vod);
            case 0x53: return c.getString(R.string.kn_guide);
            case 0x54: return c.getString(R.string.kn_timer);
            case 0x55: return c.getString(R.string.kn_init);
            case 0x56: return c.getString(R.string.kn_bcast);
            case 0x57: return c.getString(R.string.kn_sound_pres);
            default: return c.getString(R.string.keys_button_hex,
                    Integer.toHexString(cec).toUpperCase(java.util.Locale.US));
        }
    }

    /** What a button is bound to; Red is Home until changed. */
    static String actionFor(Context c, int cec) {
        return Prefs.tvCec(c, cec);
    }

    static String label(Context c, String action) {
        if (action == null || OFF.equals(action)) return c.getString(R.string.keys_off);
        if (HOME.equals(action)) return "Home";
        if (CHANNELS.equals(action)) return "Canali TV";
        if (SETTINGS.equals(action)) return c.getString(R.string.keys_settings);
        if (action.startsWith(CHANNEL)) {
            String id = action.substring(CHANNEL.length());
            List<String[]> all = ChannelsPanel.searchable(c);
            for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(id)) return all.get(i)[1];
            // Its app is gone or switched off: nothing will open until it is back.
            return c.getString(R.string.keys_channel_gone);
        }
        if (action.startsWith(APP)) {
            String pkg = action.substring(APP.length());
            try {
                PackageManager pm = c.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Throwable t) {
                return pkg;
            }
        }
        return c.getString(R.string.keys_off);
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
        if (action.startsWith(CHANNEL)) {
            // Only a channel whose app is installed and on is found, so nothing else is tried.
            ChannelsPanel.openById(c, action.substring(CHANNEL.length()));
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

    // ---------------------------------------------------------------- learning a button

    /** Wants the next button pressed on the TV's remote instead of the binding doing its job. */
    interface Learner {
        void onKey(int cec);
    }

    private static Learner learner;     // main thread

    static void setLearner(Learner l) { learner = l; }

    static Learner learner() { return learner; }

    // ---------------------------------------------------------------- the settings pages

    static View build(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.sys_tv_remote), null, Panel.SHEET);
        p.addHeader(c.getString(R.string.keys_header_coloured));
        for (int i = 0; i < DEFAULT_CODES.length; i++) {
            if (i == COLOURED) p.addHeader(c.getString(R.string.keys_header_other));
            addButton(c, host, p, DEFAULT_CODES[i]);
        }
        List<Integer> learned = new ArrayList<Integer>(Prefs.tvCecList(c));
        Collections.sort(learned);
        for (int i = 0; i < learned.size(); i++) addButton(c, host, p, learned.get(i));
        p.addRow(c.getString(R.string.keys_add), "", new Runnable() {
            public void run() { host.showPanel(buildLearn(c, host)); }
        }).opens().setHelp(c.getString(R.string.keys_add_help));
        p.addNote(c.getString(R.string.keys_note));
        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void addButton(final Context c, final SettingsPanel.Host host, Panel p,
            final int cec) {
        p.addRow(nameOf(c, cec), label(c, actionFor(c, cec)), new Runnable() {
            public void run() { host.showPanel(buildChoose(c, host, cec)); }
        }).opens().setHelp(c.getString(R.string.keys_row_help));
    }

    /**
     * Waits for a button on the TV's remote, then offers what it should do. What it is waiting
     * for, and what went wrong, is written in the page below the title, not in a row.
     */
    private static View buildLearn(final Context c, final SettingsPanel.Host host) {
        final Panel p = new Panel(c, c.getString(R.string.keys_learn_title), null, Panel.SHEET);
        p.addText(c.getString(R.string.keys_learn_press));
        final TextView status = p.addText(c.getString(R.string.keys_learn_waiting));
        p.addText(c.getString(R.string.keys_learn_note));
        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        final KeyBindings.Learner learn = new KeyBindings.Learner() {
            public void onKey(int cec) {
                if (reserved(cec)) {
                    status.setText(c.getString(R.string.keys_learn_reserved, nameOf(c, cec)));
                    return;
                }
                if (!isDefault(cec)) Prefs.addTvCec(c, cec);
                // The list is built again so that it shows the new button, with its choices on top.
                host.closePanel();
                host.closePanel();
                host.showPanel(build(c, host));
                host.showPanel(buildChoose(c, host, cec));
            }
        };
        p.root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) { setLearner(learn); }
            public void onViewDetachedFromWindow(View v) {
                if (learner == learn) setLearner(null);
            }
        });
        return p.root;
    }

    private static View buildChoose(final Context c, final SettingsPanel.Host host, final int cec) {
        final Panel p = new Panel(c, c.getString(R.string.keys_button_title, nameOf(c, cec)), null, Panel.SHEET);
        String cur = actionFor(c, cec);
        addChoice(c, host, p, cec, cur, OFF, c.getString(R.string.keys_off));
        addChoice(c, host, p, cec, cur, HOME, "Home");
        addChoice(c, host, p, cec, cur, CHANNELS, "Canali TV");
        addChoice(c, host, p, cec, cur, SETTINGS, c.getString(R.string.keys_settings));
        p.addRow(c.getString(R.string.keys_channel), cur.startsWith(CHANNEL) ? label(c, cur) : "",
                new Runnable() {
            public void run() { host.showPanel(buildChannels(c, host, cec)); }
        }).opens();
        p.addRow(c.getString(R.string.keys_app), cur.startsWith(APP) ? label(c, cur) : "",
                new Runnable() {
            public void run() { host.showPanel(buildApps(c, host, cec)); }
        }).opens();
        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static void addChoice(final Context c, final SettingsPanel.Host host, Panel p,
            final int cec, String cur, final String action, String title) {
        p.addRow(title, action.equals(cur) ? c.getString(R.string.keys_in_use) : "", new Runnable() {
            public void run() {
                set(c, cec, action);
                host.closePanel();
                host.closePanel();
                host.showPanel(build(c, host));
            }
        });
    }

    private static View buildApps(final Context c, final SettingsPanel.Host host, final int cec) {
        final Panel p = new Panel(c, c.getString(R.string.keys_app_title, nameOf(c, cec)), null, Panel.SHEET);
        List<AppModel.Item> all = AppModel.allItems();
        String cur = actionFor(c, cec);
        for (int i = 0; i < all.size(); i++) {
            final AppModel.Item it = all.get(i);
            if (it.type != AppModel.TYPE_APP || it.pkg == null) continue;
            final String action = APP + it.pkg;
            p.addRow(it.label, action.equals(cur) ? c.getString(R.string.keys_in_use) : "", new Runnable() {
                public void run() {
                    set(c, cec, action);
                    host.closePanel();
                    host.closePanel();
                    host.closePanel();
                    host.showPanel(build(c, host));
                }
            });
        }
        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /**
     * The channels of the Canali TV page, under the name of their tab. Only those whose app is
     * installed and on are listed (ChannelsPanel.searchable), so a button is never bound to a
     * channel that cannot open.
     */
    private static View buildChannels(final Context c, final SettingsPanel.Host host, final int cec) {
        final Panel p = new Panel(c, c.getString(R.string.keys_channel_title, nameOf(c, cec)),
                null, Panel.SHEET);
        List<String[]> all = ChannelsPanel.searchable(c);
        String cur = actionFor(c, cec);
        String group = null;
        for (int i = 0; i < all.size(); i++) {
            String[] ch = all.get(i);
            if (!ch[2].equals(group)) {
                group = ch[2];
                p.addHeader(group);
            }
            final String action = CHANNEL + ch[0];
            p.addRow(ch[1], action.equals(cur) ? c.getString(R.string.keys_in_use) : "", new Runnable() {
                public void run() {
                    set(c, cec, action);
                    host.closePanel();
                    host.closePanel();
                    host.closePanel();
                    host.showPanel(build(c, host));
                }
            });
        }
        p.addRow(c.getString(R.string.home_back), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** Off on a button that was added takes it off the list again; the always-listed stay. */
    private static void set(Context c, int cec, String action) {
        Prefs.setTvCec(c, cec, action);
        if (OFF.equals(action) && !isDefault(cec)) Prefs.removeTvCec(c, cec);
    }
}
