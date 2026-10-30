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

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The catalogue of packages that are safe to switch off, and what must never be touched.
 *
 * Nothing is ever uninstalled: every change is `pm disable-user --user 0`, which keeps the
 * APK and data and is undone by `pm enable`. Disabling frees RAM and CPU, not storage.
 *
 * isProtected() asks the system what currently serves as input method, TV input, device
 * admin, home screen or accessibility service, on top of a hard-coded list, rather than
 * guessing by package-name prefix.
 */
final class Debloat {

    static final int ABSENT = -1;
    static final int DISABLED = 0;
    static final int ENABLED = 1;

    static final int GROUP_ADS = 0;
    static final int GROUP_TELEMETRY = 1;
    static final int GROUP_VOICE = 2;
    static final int GROUP_EXTRAS = 3;
    static final int GROUP_HOME = 4;

    /** Marked safe, but the one-press cleanups leave it on: family photos are a common use of the TV. */
    static final String AMAZON_PHOTOS = "com.amazon.bueller.photos";

    /** Works, but has a consequence worth stating before the user presses the button. */
    static final boolean CAUTION = true;
    static final boolean SAFE = false;

    static final class Entry {
        final String pkg;
        final String title;
        final String note;
        final int group;
        final boolean caution;
        /** Never part of a bulk switch-off, never offered by the one-press buttons. */
        final boolean risky;

        Entry(int group, String pkg, String title, String note, boolean caution) {
            this(group, pkg, title, note, caution, false);
        }

        Entry(int group, String pkg, String title, String note, boolean caution, boolean risky) {
            this.group = group;
            this.pkg = pkg;
            this.title = title;
            this.note = note;
            this.caution = caution;
            this.risky = risky;
        }

        /**
         * The English text here is the default; the TV's language comes from the resources
         * "dbt_<package>" and "dbn_<package>" (dots as underscores), where there are any.
         */
        String title(Context c) { return localized(c, "dbt_", title); }

        String note(Context c) { return localized(c, "dbn_", note); }

        private String localized(Context c, String prefix, String fallback) {
            int id = c.getResources().getIdentifier(prefix + pkg.replace('.', '_'), "string",
                    c.getPackageName());
            return id != 0 ? c.getString(id) : fallback;
        }
    }

    static String groupName(Context c, int g) {
        switch (g) {
            case GROUP_ADS: return c.getString(R.string.db_group_ads);
            case GROUP_TELEMETRY: return c.getString(R.string.db_group_telemetry);
            case GROUP_VOICE: return c.getString(R.string.db_group_alexa);
            case GROUP_HOME: return c.getString(R.string.db_group_home);
            default: return c.getString(R.string.db_group_extras);
        }
    }

    /**
     * Ordered by group. Packages Fire OS refuses to disable are still listed; the attempt
     * fails cleanly and the row says so.
     */
    static final Entry[] CATALOGUE = {
            // ---------------------------------------------------------- advertising
            new Entry(GROUP_ADS, "com.amazon.hybridadidservice",
                    "Advertising ID service",
                    "Generates the identifier used to target ads at this TV.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.acr",
                    "Automatic Content Recognition",
                    "Samples what is on screen, including from HDMI inputs, for ad targeting.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.media.recommendations",
                    "\"Recommended for you\" engine",
                    "Builds the recommendation rows on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.sneakpeek",
                    "Home-screen trailer previews",
                    "The auto-playing trailer banner at the top of Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.recess",
                    "Sponsored content service",
                    "Serves the sponsored placements on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.client",
                    "Amazon shopping row",
                    "The shopping row on Amazon's home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.shoptv.firetv.client",
                    "Amazon shopping row (Fire TV)",
                    "Fire TV variant of the same shopping row.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.minitv.android.app",
                    "Amazon MiniTV",
                    "Ad-supported free channel that appears as a row and an app.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftv.screensaver",
                    "Amazon ad screensaver",
                    "Replaces the idle screensaver with sponsored imagery.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.ftvads.deeplinking",
                    "Advertising deep-link handler",
                    "Handles taps on Fire TV ads. Fire OS usually refuses to disable this "
                            + "one; if the row stays enabled, that is why.", SAFE),

            new Entry(GROUP_ADS, "com.amazon.kso.blackbird",
                    "Sponsored-content ad sync",
                    "Fetches and bids on the sponsored banner and rows. The banner itself "
                            + "still loads, but its ad targeting stops.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.uxnotification",
                    "Promotional pop-ups",
                    "On-screen notification pop-ups, mostly Amazon promotions.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.systemnotices",
                    "System notices",
                    "Amazon's system notice pop-ups.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.releasenotes",
                    "\"What's new\" pop-ups",
                    "Shows Amazon's release-note screens after updates.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.easyupgrade",
                    "\"Upgrade your Fire TV\" promos",
                    "Nags to buy a newer Fire TV. This is not the software updater.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.gamehub",
                    "Games hub",
                    "Promotes games and Luna cloud gaming on the home screen.", SAFE),
            new Entry(GROUP_ADS, "com.amazon.tv.developer.dataservice",
                    "App personalization data",
                    "Shares content and personalization data with third-party apps.", SAFE),
            // ---------------------------------------------------------- telemetry
            new Entry(GROUP_TELEMETRY, "com.amazon.tv.fw.metrics",
                    "Framework metrics collector",
                    "Collects framework-level usage metrics.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.dp.logger",
                    "Amazon logging framework",
                    "Shared logging plumbing used by Amazon services.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.wifilocker",
                    "Wi-Fi password upload",
                    "Uploads saved Wi-Fi credentials to your Amazon account so other "
                            + "devices can join automatically. Disabling it does not "
                            + "affect this TV's own Wi-Fi.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.client.metrics",
                    "Usage-metrics upload service",
                    "Runs even when the TV is idle. Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.metrics",
                    "Device metrics collector",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.minerva.client.api",
                    "Minerva analytics client",
                    "Amazon's telemetry pipeline. Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.diode",
                    "Diagnostics uploader",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.crashmanager",
                    "Crash-report uploader",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.device.logmanager",
                    "Log upload service",
                    "Usually protected by Fire OS.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.wirelessmetrics.service",
                    "Wireless metrics",
                    "Reports Wi-Fi and network metrics to Amazon.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.perfc",
                    "Performance collector",
                    "Collects device performance data.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.perfcollection",
                    "Performance collector (2)",
                    "A second performance-data collector.", SAFE),
            new Entry(GROUP_TELEMETRY, "com.amazon.client.metrics.api",
                    "Metrics API service",
                    "The metrics-reporting API used by Amazon apps.", SAFE),
            // ---------------------------------------------------------- alexa
            new Entry(GROUP_VOICE, "com.amazon.vizzini",
                    "Alexa voice engine",
                    "The main Alexa process. It switches itself back on after a restart; "
                            + "keep \"Re-apply after reboot\" on in Setup to keep it off.",
                    CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.aca",
                    "Alexa companion agent",
                    "Reports account and media status to Alexa.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexamediaplayer.runtime.ftv",
                    "Alexa media playback runtime",
                    "Lets Alexa control playback.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.datastore.app",
                    "Alexa datastore", "Local storage for Alexa state.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexadirectivebrokerservice",
                    "Alexa directive broker", "Routes recognised voice commands.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.alexa.externalmediaplayer.fireos",
                    "Alexa external media bridge", "Bridges Alexa to third-party players.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexaalerts",
                    "Alexa timers and alarms", "Voice timers and alarms stop working.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.tv.alexanotifications",
                    "Alexa notifications", "Alexa notification delivery.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.vizzini.ftvcds",
                    "Alexa content discovery", "Voice search across apps stops working.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.ale",
                    "Alexa Local Engine", "On-device speech handling.", CAUTION),

            new Entry(GROUP_VOICE, "com.amazon.audiohome",
                    "Alexa Home Theater",
                    "Pairs the TV with Echo speakers as a home-theatre group.", CAUTION),
            new Entry(GROUP_VOICE, "com.amazon.aiondec",
                    "Alexa skill connections",
                    "Connects Alexa skills to the TV.", CAUTION),
            // ---------------------------------------------------------- extras
            new Entry(GROUP_EXTRAS, "com.apple.airplay.amazon.airplay",
                    "AirPlay receiver",
                    "Screen mirroring from Apple devices.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.firebat",
                    "Prime Video",
                    "Amazon's Prime Video app. It starts in the background after boot, so "
                            + "switching it off frees memory, but Prime Video will not open "
                            + "until you switch it back on.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whad",
                    "Wireless Home Audio daemon",
                    "Pairing with Echo speakers for TV audio.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whasettings",
                    "Wireless Home Audio settings",
                    "The settings screen for the above.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.ffsprovisioneeclient",
                    "Frustration-Free Setup client",
                    "Automatic setup of new Amazon devices from this one.", SAFE),
            new Entry(GROUP_EXTRAS, AMAZON_PHOTOS,
                    "Amazon Photos",
                    "Fed the ad screensaver. Only useful if you browse photos on the TV.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.democontent.provider",
                    "Retail demo content",
                    "In-store demo loop. Nothing uses it in a home.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tmm.tutorial",
                    "First-run tutorial", "The introductory tour. Already seen.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.storm.lightning.tutorial",
                    "Remote-app tutorial", "Tutorial for the phone remote app.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.tigris",
                    "Appstore onboarding", "Appstore first-run experience.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.naatyam",
                    "Setup redirect handler", "Onboarding launch redirection.", SAFE),
            new Entry(GROUP_EXTRAS, "com.amazon.hedwig",
                    "Fire TV Channels",
                    "The free ad-supported channels row and the Live input.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.livetv",
                    "Live tab and guide",
                    "The Live tab, the channel guide and the Live TV player. Switching it off "
                            + "removes all three, and Salotto's Live TV tile with them.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tv.mattercohost",
                    "Matter smart-home hub",
                    "Lets the TV act as a Matter smart-home controller.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.whisperjoin.middleware.np",
                    "Frustration-Free Setup provisioning",
                    "Sets up other Amazon devices from this TV over Wi-Fi.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.tahoe",
                    "Amazon Kids",
                    "Amazon Kids (FreeTime) profiles and parental controls.", CAUTION),
            new Entry(GROUP_EXTRAS, "com.amazon.ods.kindleconnect",
                    "\"Show on Fire TV\" casting",
                    "Casts content from an Echo or Fire tablet to this TV.", CAUTION),
            new Entry(GROUP_HOME, AppModel.AMAZON_LAUNCHER,
                    "Amazon's launcher",
                    "Home opens Salotto directly, with no overlay and no flash of Amazon's "
                            + "screen. Anything that lived only on Amazon's home screen, such as "
                            + "its Inputs screen, stops working until you switch this back on. "
                            + "A firmware update can switch it back on by itself.",
                    CAUTION, true),
    };

    /**
     * Packages this app never touches. com.amazon.ceviche must stay listed: disabling it
     * puts com.amazon.tv.launcher into a crash loop.
     */
    private static final String[] NEVER = {
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.downloads",
            "com.android.packageinstaller",
            "com.android.keychain",
            "com.android.certinstaller",
            "com.android.location.fused",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.shell",
            "com.android.server.telecom",
            "com.android.webview",
            "com.google.android.webview",
            "com.android.captiveportallogin",
            "com.amazon.tv.launcher",
            "com.amazon.firehomestarter",
            "com.amazon.tv.settings.v2",
            "com.amazon.device.software.ota",
            "com.amazon.device.software.ota.override",
            "com.amazon.tv.parentalcontrols",
            "com.amazon.ceviche",
            "com.amazon.tv.devicecontrolsettings",
            "com.amazon.imp",
            "com.amazon.dcp",
    };

    /** Substrings that identify subsystems the TV cannot lose. Deliberately narrow. */
    private static final String[] NEVER_CONTAINING = {
            "inputmethod",
            "tvinput",
            "com.mediatek.",
            "com.mstar.",
            "wifi",
            "bluetooth",
    };

    private Debloat() { }

    // ---------------------------------------------------------------- state

    /** ENABLED, DISABLED or ABSENT. Needs no permission. */
    static int stateOf(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager()
                    .getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return ai.enabled ? ENABLED : DISABLED;
        } catch (Throwable t) {
            return ABSENT;
        }
    }

    static String stateLabel(int state) {
        if (state == ABSENT) return "not present";
        return state == ENABLED ? "on" : "off";
    }

    // ---------------------------------------------------------------- protection

    /** Null if the package may be toggled, otherwise the reason it may not be. */
    static String protectionReason(Context c, String pkg) {
        if (pkg == null || pkg.length() == 0) return c.getString(R.string.db_reason_nopkg);
        if (pkg.equals(c.getPackageName())) return c.getString(R.string.db_reason_self);

        for (int i = 0; i < NEVER.length; i++) {
            if (NEVER[i].equals(pkg)) {
                if (isRisky(pkg)) break;     // its own row, with a warning, is the way in
                return "com.amazon.ceviche".equals(pkg)
                        ? c.getString(R.string.db_reason_crash)
                        : c.getString(R.string.db_reason_core);
            }
        }
        // The substring rules guard "Browse every system app". Catalogue entries were
        // checked by hand, so they are exempt (com.amazon.wifilocker contains "wifi").
        if (!inCatalogue(pkg)) {
            String lower = pkg.toLowerCase(java.util.Locale.US);
            for (int i = 0; i < NEVER_CONTAINING.length; i++) {
                if (lower.contains(NEVER_CONTAINING[i])) return c.getString(R.string.db_reason_core);
            }
        }
        // The launcher is the home screen right now by definition; Homestead also declares
        // Home, so the risky entry is allowed past this check and carries its own warning.
        if (!isRisky(pkg) && liveCritical(c).contains(pkg)) return c.getString(R.string.db_reason_live);
        // Only ever blocks switching it off: once it is off, its row must stay the way back.
        if (AppModel.AMAZON_LAUNCHER.equals(pkg) && stateOf(c, pkg) == ENABLED) {
            return homeWithoutAmazonLauncher(c);
        }
        return null;
    }

    /**
     * Null when, with Amazon's launcher off, Home would come straight to Homestead; otherwise
     * who would get it. Android gives Home to the home screen with the highest priority, and
     * an app's priority is 0. On Fire OS 7 Amazon's Home Starter declares 1 and would win; it
     * only switches Amazon's launcher back on and closes, so Home would open nothing. A tie
     * with another home app would bring up a chooser instead.
     */
    static String homeWithoutAmazonLauncher(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        } catch (Throwable t) {
            return c.getString(R.string.db_reason_check);
        }
        ResolveInfo ours = null, rival = null;
        for (int i = 0; ris != null && i < ris.size(); i++) {
            ResolveInfo ri = ris.get(i);
            if (ri.activityInfo == null) continue;
            String p = ri.activityInfo.packageName;
            if (AppModel.AMAZON_LAUNCHER.equals(p)) continue;
            if (p.equals(c.getPackageName())) {
                if (ours == null || ri.priority > ours.priority) ours = ri;
            } else if (rival == null || ri.priority > rival.priority) {
                rival = ri;
            }
        }
        if (ours == null) return c.getString(R.string.db_reason_check);
        if (rival == null || rival.priority < ours.priority) return null;
        if (rival.priority > ours.priority) return c.getString(R.string.db_reason_fireos);
        // Short enough for the row; a package without a label falls back to its name.
        CharSequence label = null;
        try { label = rival.loadLabel(pm); } catch (Throwable ignored) { }
        String name = label == null ? "" : label.toString();
        return name.length() == 0 || name.equals(rival.activityInfo.packageName)
                ? c.getString(R.string.db_reason_other_home) : c.getString(R.string.db_reason_shared, name);
    }

    /** The catalogue's plain-English name for a package, or the package name itself. */
    static String titleOf(Context c, String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].pkg.equals(pkg)) return CATALOGUE[i].title(c);
        }
        return pkg;
    }

    /** A catalogue entry that is switched off only from its own row, never in bulk. */
    static boolean isRisky(String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].risky && CATALOGUE[i].pkg.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean inCatalogue(String pkg) {
        for (int i = 0; i < CATALOGUE.length; i++) {
            if (CATALOGUE[i].pkg.equals(pkg)) return true;
        }
        return false;
    }

    /** Fire OS's message when disable-user hits a package it protects. */
    static boolean refusedIn(String output, String pkg) {
        return output != null && output.contains("protected package: " + pkg);
    }

    static boolean isProtected(Context c, String pkg) {
        return protectionReason(c, pkg) != null;
    }

    /**
     * Packages the system currently relies on: input methods, TV inputs (HDMI), home
     * screens, active device admins and enabled accessibility services. Not cached, since
     * the answer changes when the user switches keyboard or plugs something in.
     */
    private static Set<String> liveCritical(Context c) {
        Set<String> out = new HashSet<String>();
        PackageManager pm = c.getPackageManager();

        try {
            InputMethodManager imm =
                    (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> imes = (imm == null) ? null : imm.getInputMethodList();
            if (imes != null) {
                for (int i = 0; i < imes.size(); i++) out.add(imes.get(i).getPackageName());
            }
        } catch (Throwable ignored) { }

        try {
            TvInputManager tim =
                    (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = (tim == null) ? null : tim.getTvInputList();
            if (inputs != null) {
                for (int i = 0; i < inputs.size(); i++) {
                    android.content.pm.ServiceInfo si = inputs.get(i).getServiceInfo();
                    if (si != null) out.add(si.packageName);
                }
            }
        } catch (Throwable ignored) { }

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> ris = pm.queryIntentActivities(home, 0);
            if (ris != null) {
                for (int i = 0; i < ris.size(); i++) {
                    if (ris.get(i).activityInfo != null) {
                        out.add(ris.get(i).activityInfo.packageName);
                    }
                }
            }
        } catch (Throwable ignored) { }

        try {
            DevicePolicyManager dpm =
                    (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> admins = (dpm == null) ? null : dpm.getActiveAdmins();
            if (admins != null) {
                for (int i = 0; i < admins.size(); i++) out.add(admins.get(i).getPackageName());
            }
        } catch (Throwable ignored) { }

        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            List<android.accessibilityservice.AccessibilityServiceInfo> svcs = (am == null)
                    ? null
                    : am.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (svcs != null) {
                for (int i = 0; i < svcs.size(); i++) {
                    String id = svcs.get(i).getId();
                    int slash = (id == null) ? -1 : id.indexOf('/');
                    if (slash > 0) out.add(id.substring(0, slash));
                }
            }
        } catch (Throwable ignored) { }

        return out;
    }

    // ---------------------------------------------------------------- browse everything

    static final class Installed {
        final String pkg;
        final String label;
        final int state;
        final boolean system;
        Installed(String pkg, String label, int state, boolean system) {
            this.pkg = pkg; this.label = label; this.state = state; this.system = system;
        }
    }

    /**
     * Every installed package with its state, sorted by label. Protected packages are
     * included; callers must check protectionReason() before offering a toggle.
     */
    static List<Installed> installed(Context c, boolean systemOnly) {
        List<Installed> out = new ArrayList<Installed>();
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> all =
                    pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
            for (int i = 0; i < all.size(); i++) {
                ApplicationInfo ai = all.get(i);
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (systemOnly && !sys) continue;
                String label;
                try {
                    CharSequence l = ai.loadLabel(pm);
                    label = (l == null) ? ai.packageName : l.toString();
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                out.add(new Installed(ai.packageName, label,
                        ai.enabled ? ENABLED : DISABLED, sys));
            }
        } catch (Throwable ignored) { }
        java.util.Collections.sort(out, new java.util.Comparator<Installed>() {
            public int compare(Installed a, Installed b) {
                int n = a.label.compareToIgnoreCase(b.label);
                return (n != 0) ? n : a.pkg.compareTo(b.pkg);
            }
        });
        return out;
    }
}
