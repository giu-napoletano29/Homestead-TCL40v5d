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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The Settings pages. Everything here is the launcher's own behaviour - nothing on these
 * pages touches the system, so none of it needs a privileged permission.
 */
public final class SettingsPanel {

    /** Implemented by HomeGrid so a panel can push another panel or apply changes. */
    public interface Host {
        void rebuildGrid();
        void showPanel(View v);
        void closePanel();
    }

    private SettingsPanel() { }

    /**
     * The hub: a sheet over the home screen with the three pages of home-screen options, then
     * the system pages. Changes show on the grid behind it straight away.
     */
    public static View build(final Context c, final Host host) {
        final Panel p = new Panel(c, c.getString(R.string.settings_title),
                "Salotto " + UpdateChecker.installedVersionName(c) + "  ·  a Homestead fork",
                Panel.SHEET);

        final Panel.Row look = p.addRow(c.getString(R.string.settings_appearance),
                appearanceSummary(c), null).opens();
        look.setValueColor(0xFF8FA3B8);
        look.setHelp("Background, grid columns, dock size, tile style, the clock, date and greeting.");
        final Panel.Row content = p.addRow(c.getString(R.string.settings_content),
                contentSummary(c), null).opens();
        content.setValueColor(0xFF8FA3B8);
        content.setHelp("Sort order, hidden tiles, which sections show, Live TV first.");
        final Panel.Row behaviour = p.addRow(c.getString(R.string.settings_behaviour),
                behaviourSummary(c), null).opens();
        behaviour.setValueColor(0xFF8FA3B8);
        behaviour.setHelp("What the home screen does by itself, and the phone remote.");
        // Summaries catch up when a sub-page changes something.
        final Runnable summaries = new Runnable() {
            public void run() {
                look.setValue(appearanceSummary(c));
                content.setValue(contentSummary(c));
                behaviour.setValue(behaviourSummary(c));
            }
        };
        look.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildAppearance(c, host, summaries)); }
        });
        content.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildContent(c, host, summaries)); }
        });
        behaviour.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildBehaviour(c, host, summaries)); }
        });

        p.addHeader(c.getString(R.string.settings_header_system));

        boolean ready = SetupPanel.serviceEnabled(c);
        final Panel.Row setup = p.addRow(c.getString(R.string.settings_setup), ready ? "done"
                : Prefs.homeOffByUser(c) ? "Home button off" : "not finished", null).opens();
        setup.setValueColor(ready ? 0xFFA7D28F : 0xFFE8C468);
        setup.setHelp("What this launcher needs, and how to switch it on.");
        setup.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(SetupPanel.build(c, host)); }
        });

        if (AppModel.amazonLauncherOn(c)) {
            // With Amazon's launcher off there is nothing for this to hand Home back to.
            SetupPanel.addTurnOffRow(c, p, new Runnable() {
                public void run() {
                    setup.setValue("Home button off");
                    setup.setValueColor(0xFFE8C468);
                }
            });
            p.addNote(SetupPanel.TURN_OFF_HELP);
        }

        p.addHeader(c.getString(R.string.settings_header_device));

        p.addRow(c.getString(R.string.sys_title), "", new Runnable() {
            public void run() { host.showPanel(SystemSettingsPanel.build(c, host)); }
        }).opens().setHelp("Amazon's own settings screens (network, Bluetooth, applications, "
                + "My Fire TV), each opened directly; Salotto's own pages for what Amazon "
                + "locks behind a permission; the TV remote's buttons; and the quick settings "
                + "in the top bar.");

        p.addRow(c.getString(R.string.home_tv_health), "check", new Runnable() {
            public void run() { host.showPanel(HealthPanel.build(c, host)); }
        }).opens().setHelp("This TV's speed and privacy settings and Amazon's advertising "
                + "packages, with a fix for each one it flags - Debloat is reached from here.");

        final Panel.Row watch = p.addRow(c.getString(R.string.settings_warn_debloat),
                onOff(Prefs.restoreWatch(c)), null).asSwitch();
        watch.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.restoreWatch(c);
                Prefs.setRestoreWatch(c, n);
                watch.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        watch.setHelp("If an update switches back on something Debloat turned off, the home "
                + "screen shows it for 10 seconds, and OK switches it off again.");

        final Panel.Row updates = p.addRow(c.getString(R.string.settings_check_updates),
                "v" + UpdateChecker.installedVersionName(c), null);
        updates.setValueColor(0xFF8FA3B8);
        updates.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { UpdatePanel.checkNow(c, host, updates); }
        });

        p.addRow(c.getString(R.string.settings_close), "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- home screen
    // Three pages under the hub: how it looks, what it shows and in which order, and what
    // it does by itself.

    /** Layout, clock and greeting, and the background. */
    private static View buildAppearance(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, c.getString(R.string.settings_appearance), null, Panel.SHEET);

        final Panel.Row look = p.addRow(c.getString(R.string.settings_background),
                Backgrounds.label(c), null).opens();
        look.setValueColor(0xFF8FA3B8);
        look.setHelp("The colour or photo behind the home screen.");
        look.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildLook(c, host, changed)); }
        });
        // The choice is made on the page this row opens; repaint it on the way back.
        look.cell.setOnFocusChangeListener(chain(look.cell.getOnFocusChangeListener(),
                new Runnable() {
                    public void run() { look.setValue(Backgrounds.label(c)); }
                }));

        p.addHeader("Tiles");
        final Panel.Row cols = p.addRow("Grid columns", String.valueOf(Prefs.columns(c)), null);
        cols.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.columns(c) + 1;
                if (n > 8) n = 4;
                Prefs.setColumns(c, n);
                cols.setValue(String.valueOf(n));
                AppModel.invalidateMetrics();
                host.rebuildGrid();
                changed.run();
            }
        });

        final Panel.Row dock = p.addRow("Dock size",
                Prefs.DOCK_SIZE_NAMES[Prefs.dockSize(c)], null);
        dock.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = (Prefs.dockSize(c) + 1) % Prefs.DOCK_SIZE_NAMES.length;
                Prefs.setDockSize(c, n);
                dock.setValue(Prefs.DOCK_SIZE_NAMES[n]);
                host.rebuildGrid();
            }
        });
        dock.setHelp("How big the app tiles in the dock along the bottom are. Larger sizes "
                + "scroll sideways when they don't all fit.");

        final Panel.Row wide = p.addRow("Non-TV apps as wide tiles", onOff(Prefs.nonTvWide(c)), null).asSwitch();
        wide.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.nonTvWide(c);
                Prefs.setNonTvWide(c, n);
                wide.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        wide.setHelp("Like Fire TV's own home: each app's icon on a banner-sized tile, in a grid "
                + "like the TV apps.");

        final Panel.Row fire = p.addRow("Fire TV system icons", onOff(Prefs.fireOsIcons(c)), null).asSwitch();
        fire.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.fireOsIcons(c);
                Prefs.setFireOsIcons(c, n);
                fire.setValue(onOff(n));
                AppModel.reload(c);
                host.rebuildGrid();
            }
        });
        fire.setHelp("Amazon Home, Settings and TV Health use the icons of Fire OS's own home and "
                + "Settings, read from this TV. Where one is missing, the usual icon stays.");


        p.addHeader("Clock and greeting");
        final Panel.Row clock = p.addRow("Clock", clockLabel(c), null);
        clock.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.clockMode(c) + 1;
                if (n > Prefs.CLOCK_OFF) n = Prefs.CLOCK_24H;
                Prefs.setClockMode(c, n);
                clock.setValue(clockLabel(c));
                host.rebuildGrid();
            }
        });

        final Panel.Row greet = p.addRow("Greeting", onOff(Prefs.greeting(c)), null).asSwitch();
        greet.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.greeting(c);
                Prefs.setGreeting(c, n);
                greet.setValue(onOff(n));
                host.rebuildGrid();
            }
        });
        greet.setHelp("\"Good morning\", \"Good afternoon\" or \"Good evening\" at the top left.");

        final Panel.Row date = p.addRow("Date", dateLabel(c), null);
        date.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = Prefs.dateFormat(c) + 1;
                if (n > Prefs.DATE_SHORT) n = Prefs.DATE_OFF;
                Prefs.setDateFormat(c, n);
                date.setValue(dateLabel(c));
                host.rebuildGrid();
            }
        });
        date.setHelp("Under the greeting. Both formats follow the TV's language.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** What is shown, in which order, and what is hidden. */
    private static View buildContent(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, c.getString(R.string.settings_content), null, Panel.SHEET);

        final Panel.Row sort = p.addRow("Sort apps by", sortLabel(c), null);
        sort.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setSort(c, Prefs.sort(c) == Prefs.SORT_LAST_USED
                        ? Prefs.SORT_ALPHA : Prefs.SORT_LAST_USED);
                sort.setValue(sortLabel(c));
                AppModel.resort(c);
                host.rebuildGrid();
            }
        });

        p.addHeader("Hidden");

        final Panel.Row hidden = p.addRow("Hidden tiles",
                String.valueOf(Prefs.hiddenCount(c)), null).opens();
        hidden.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.showPanel(buildHiddenList(c, host)); }
        });
        // The count changes on the page this row opens; repaint it on the way back.
        hidden.cell.setOnFocusChangeListener(chain(hidden.cell.getOnFocusChangeListener(),
                new Runnable() {
                    public void run() {
                        hidden.setValue(String.valueOf(Prefs.hiddenCount(c)));
                        changed.run();
                    }
                }));

        p.addNote("Press the MENU button on any tile to hide it (long-press works too). "
                + "Hiding only changes what this launcher draws - nothing is "
                + "disabled or uninstalled, and the app still works everywhere else.");

        p.addHeader("Sections");

        // Only offered where there is a Live TV or Canali TV tile.
        if (AppModel.hasLiveTv()) {
            final Panel.Row tvFirst = p.addRow("Live TV first", onOff(Prefs.liveTvFirst(c)), null).asSwitch();
            tvFirst.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean n = !Prefs.liveTvFirst(c);
                    Prefs.setLiveTvFirst(c, n);
                    tvFirst.setValue(onOff(n));
                    host.rebuildGrid();
                }
            });
            tvFirst.setHelp("Live TV (or Canali TV) as a wider first tile in the dock; Home always "
                    + "lands on Live TV.");
        }

        final Panel.Row secIn = p.addRow("Show \"Inputs & System\"", onOff(Prefs.showInputs(c)), null).asSwitch();
        secIn.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showInputs(c);
                Prefs.setShowInputs(c, n);
                secIn.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        final Panel.Row secNon = p.addRow("Show \"Non-TV Applications\"", onOff(Prefs.showNonTv(c)), null).asSwitch();
        secNon.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean n = !Prefs.showNonTv(c);
                Prefs.setShowNonTv(c, n);
                secNon.setValue(onOff(n));
                host.rebuildGrid();
            }
        });

        p.addSectionHelp("Settings and TV Health always stay reachable, even with "
                + "\"Inputs & System\" switched off.");

        p.addHeader("Apps");
        p.addRow("Reload apps now", "", new Runnable() {
            public void run() {
                AppModel.reload(c);
                host.rebuildGrid();
            }
        }).setHelp("Reads the installed apps and the TV's inputs again.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    /** What the home screen does by itself. */
    private static View buildBehaviour(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, c.getString(R.string.settings_behaviour), null, Panel.SHEET);

        final Panel.Row idle = p.addRow("Idle clock", idleLabel(Prefs.idleMinutes(c)), null);
        idle.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int cur = Prefs.idleMinutes(c);
                int next = Prefs.IDLE_CHOICES[0];
                for (int i = 0; i < Prefs.IDLE_CHOICES.length; i++) {
                    if (Prefs.IDLE_CHOICES[i] == cur) {
                        next = Prefs.IDLE_CHOICES[(i + 1) % Prefs.IDLE_CHOICES.length];
                        break;
                    }
                }
                Prefs.setIdleMinutes(c, next);
                idle.setValue(idleLabel(next));
                changed.run();
            }
        });
        idle.setHelp("After this long with no button pressed, the home screen is covered by a "
                + "large clock that drifts slowly across the screen, so nothing stays lit in one "
                + "place. Any button brings the home screen back; that press does nothing else.");

        final Panel.Row remote = p.addRow(c.getString(R.string.remote_title),
                onOff(Prefs.remoteOn(c)), null).opens();
        remote.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                host.showPanel(RemotePanel.build(c, host, new Runnable() {
                    public void run() {
                        remote.setValue(onOff(Prefs.remoteOn(c)));
                        changed.run();
                    }
                }));
            }
        });
        remote.setHelp("Search the apps and channels from a phone on the same Wi-Fi. Off until "
                + "you turn it on.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    private static String appearanceSummary(Context c) {
        return Prefs.columns(c) + " columns, " + Backgrounds.label(c);
    }

    private static String contentSummary(Context c) {
        int n = Prefs.hiddenCount(c);
        return n == 0 ? "nothing hidden" : n + " hidden";
    }

    private static String behaviourSummary(Context c) {
        return "idle clock " + idleLabel(Prefs.idleMinutes(c))
                + (Prefs.remoteOn(c) ? ", phone remote" : "");
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

    // ---------------------------------------------------------------- background

    private static View buildLook(final Context c, final Host host, final Runnable changed) {
        final Panel p = new Panel(c, "Background", null, Panel.SHEET);

        // One row per look, the one in use marked. Selecting one applies it at once, and the
        // home screen beside the page shows it.
        p.addSectionHelp("Select one to use it. The home screen beside this page changes "
                + "straight away.");
        final List<Panel.Row> rows = new ArrayList<Panel.Row>();
        final List<String> ids = new ArrayList<String>();
        final Runnable mark = new Runnable() {
            public void run() {
                String now = Backgrounds.currentId(c);
                boolean noFile = Backgrounds.photoFile(c) == null;
                for (int i = 0; i < rows.size(); i++) {
                    boolean on = ids.get(i).equals(now);
                    String v = on ? "in use" : "";
                    if (noFile && ids.get(i).equals(Backgrounds.PHOTO_ID)) {
                        v = on ? "in use, no file yet" : "no file yet";
                    }
                    rows.get(i).setValue(v);
                    rows.get(i).setValueColor(on ? IN_USE : 0xFF8FA3B8);
                }
            }
        };
        for (int i = 0; i <= Backgrounds.PRESETS.length; i++) {
            boolean photo = i == Backgrounds.PRESETS.length;
            final Backgrounds.Preset look = photo ? Backgrounds.PHOTO_LOOK : Backgrounds.PRESETS[i];
            final Panel.Row r = p.addRow(look.name, "", null);
            android.graphics.Bitmap shot = photo ? Backgrounds.lastPhoto() : null;
            r.setSwatch(shot != null
                    ? new android.graphics.drawable.BitmapDrawable(c.getResources(), shot)
                    : swatch(c, look));
            r.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Backgrounds.choose(c, look.id);
                    mark.run();
                    host.rebuildGrid();
                    changed.run();
                }
            });
            rows.add(r);
            ids.add(look.id);
        }
        rows.get(ids.indexOf("black")).setHelp("Black is for OLED screens and night viewing.");
        rows.get(rows.size() - 1).setHelp("Choose a picture saved on the TV below (needs the "
                + "on-device tunnel), or send a JPEG or PNG from a computer:\n\n"
                + "adb push background.jpg /sdcard/Download/\n"
                + "adb shell \"content write --uri content://" + BackgroundProvider.AUTHORITY
                + "/photo < /sdcard/Download/background.jpg\"");

        // Public-domain paintings that come with the app, shown the way a photo is.
        p.addHeader("Paintings");
        for (int i = 0; i < Backgrounds.ARTS.length; i++) {
            final Backgrounds.Art art = Backgrounds.ARTS[i];
            final Panel.Row r = p.addRow(art.name, "", null);
            android.graphics.Bitmap thumb = Backgrounds.artThumb(c, art);
            if (thumb != null) {
                r.setSwatch(new android.graphics.drawable.BitmapDrawable(c.getResources(), thumb));
            }
            r.cell.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Backgrounds.choose(c, art.id);
                    mark.run();
                    host.rebuildGrid();
                    changed.run();
                }
            });
            rows.add(r);
            ids.add(art.id);
        }
        mark.run();

        p.addHeader("Photo");
        final Panel.Row dim = p.addRow("Photo dimming", Backgrounds.dimLabel(c), null);
        dim.cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setPhotoDim(c, (Prefs.photoDim(c) + 1) % Backgrounds.DIM_NAMES.length);
                dim.setValue(Backgrounds.dimLabel(c));
                host.rebuildGrid();
            }
        });
        dim.setHelp("Dimming darkens the photo so the labels stay readable.");

        p.addRow("Choose a photo on this TV", "", new Runnable() {
            public void run() { host.showPanel(PhotoPanel.build(c, host)); }
        }).opens().setHelp("Lists the pictures in the TV's Download folder. Needs the "
                + "on-device tunnel.");

        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });

        // Start on the look in use. showPanel focuses the first row once the page is up,
        // so this goes one step after that.
        final View start = rows.get(Math.max(0, ids.indexOf(Backgrounds.currentId(c)))).cell;
        p.root.post(new Runnable() {
            public void run() {
                p.root.post(new Runnable() { public void run() { start.requestFocus(); } });
            }
        });
        return p.root;
    }

    private static final int IN_USE = 0xFFA7D28F;

    /** A preset's colours as a small rounded sample, edged so dark ones show on the page. */
    private static android.graphics.drawable.Drawable swatch(Context c, Backgrounds.Preset look) {
        android.graphics.drawable.GradientDrawable d = Backgrounds.drawable(look);
        d.setCornerRadius(AppModel.dp(c, 5));
        d.setStroke(AppModel.dp(c, 1), 0x59FFFFFF);
        return d;
    }

    // ---------------------------------------------------------------- hidden list

    private static View buildHiddenList(final Context c, final Host host) {
        final Panel p = new Panel(c, "Hidden tiles",
                "Select one to show it on the home screen again", Panel.SHEET);

        Set<String> keys = Prefs.hidden(c);
        if (keys.isEmpty()) {
            p.addNote("Nothing is hidden. Press MENU on a tile (or long-press it) to hide it.");
        } else {
            // Resolve keys to human labels through the full model, not the visible list.
            List<AppModel.Item> all = AppModel.allItems();
            List<String> rows = new ArrayList<String>();
            final List<String> rowKeys = new ArrayList<String>();
            for (String k : keys) {
                String label = k;
                for (int i = 0; i < all.size(); i++) {
                    if (k.equals(all.get(i).key())) { label = all.get(i).label; break; }
                }
                // Live TV leaves the model when its app is switched off; still name it.
                if (label.equals(AppModel.LIVETV_KEY)) label = c.getString(R.string.home_livetv);
                if (label.equals(AppModel.CHANNELS_KEY)) label = c.getString(R.string.home_channels);
                rows.add(label);
                rowKeys.add(k);
            }
            // Stable, readable order.
            for (int i = 0; i < rows.size(); i++) {
                for (int j = i + 1; j < rows.size(); j++) {
                    if (rows.get(j).compareToIgnoreCase(rows.get(i)) < 0) {
                        Collections.swap(rows, i, j);
                        Collections.swap(rowKeys, i, j);
                    }
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                final String key = rowKeys.get(i);
                final Panel.Row r = p.addRow(rows.get(i), "hidden", null);
                r.cell.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Prefs.setHidden(c, key, false);
                        r.setValue("shown");
                        r.cell.setEnabled(false);
                        host.rebuildGrid();
                    }
                });
            }
            p.addRow("Show all hidden tiles", "", new Runnable() {
                public void run() {
                    Prefs.clearHidden(c);
                    host.rebuildGrid();
                    host.closePanel();
                }
            });
        }
        p.addRow("Back", "", new Runnable() {
            public void run() { host.closePanel(); }
        });
        return p.root;
    }

    // ---------------------------------------------------------------- labels

    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    static String sortLabel(Context c) {
        return Prefs.sort(c) == Prefs.SORT_ALPHA ? "A – Z" : "Last used";
    }

    static String clockLabel(Context c) {
        switch (Prefs.clockMode(c)) {
            case Prefs.CLOCK_12H: return "12-hour";
            case Prefs.CLOCK_OFF: return "Off";
            default: return "24-hour";
        }
    }

    static String idleLabel(int minutes) {
        return minutes <= 0 ? "Off" : minutes + " min";
    }

    static String dateLabel(Context c) {
        switch (Prefs.dateFormat(c)) {
            case Prefs.DATE_LONG: return "Weekday and month";
            case Prefs.DATE_SHORT: return "Numbers";
            default: return "Off";
        }
    }
}
