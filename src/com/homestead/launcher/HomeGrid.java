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
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The launcher UI, built against a plain Context so the same screen can be hosted by an
 * Activity or a WindowManager overlay.
 *
 * Plain, minimal shape on purpose: a greeting and clock top-left, one round Settings button
 * top-right, a translucent dock of the most-used apps, then a single uniform grid for
 * everything else - inputs, Settings, TV Health, Amazon Home and the rest of the apps - with
 * no section headers. "Show Inputs &amp; System" and "Show Non-TV Applications" in Settings
 * still gate exactly what they always did; there just is no longer a labelled band for them.
 *
 * Sections are plain focusable views in a ScrollView rather than a GridView: no recycling,
 * so each view is its item and focus survives a re-sort by key. Data and bitmaps come from
 * the process-wide AppModel.
 */
public class HomeGrid implements SettingsPanel.Host {

    public interface Listener {
        /** Called before the item is acted on, so an overlay host can dismiss itself. */
        void onPick(AppModel.Item it);
    }

    /**
     * Set as a panel root's tag to handle Back itself: return true to consume it, false to
     * let the panel close.
     */
    public interface BackHandler { boolean onBack(); }

    // ---------------------------------------------------------------- layout metrics
    // Margins and gaps match AppModel's own grid math, so every tile - dock or grid - lines
    // up on the same rhythm regardless of column count.
    private static final int MARGIN_DP = 32;
    private static final int GAP_DP = 16;
    private static final int CORNER_DP = 10;
    private static final int TOP_RESERVE_DP = 152;
    /** Height of the Live TV tile that sits above the dock when "Live TV first" is on. */
    private static final int HERO_H_DP = 104;
    private static final int TOPBAR_SIDE_DP = 32;
    private static final int TOPBAR_TOP_DP = 26;
    private static final int DOCK_COUNT = 5;
    // Dock tile height per Prefs.DOCK_SIZE_NAMES; tiles are 16:9, so Large and up scroll
    // sideways past the screen edge rather than shrinking to fit.
    private static final int[] DOCK_H_DP = { 88, 112, 136, 160 };
    private static final int DOCK_PAD_DP = 14;
    private static final int DOCK_BOTTOM_DP = 26;
    private static final int ICON_SZ_DP = 26;
    /** The icon inside a top-bar pill; the pills are small, so it is smaller than ICON_SZ_DP. */
    private static final int PILL_ICON_DP = 17;
    // One flat neutral card colour for every non-banner tile - hierarchy comes from the
    // dock's translucent band standing apart from the grid, not from a different hue per
    // tile type.
    private static final int CARD_COLOR = 0xFF1B1B1E;
    private static final int DOCK_BAND_COLOR = 0x24FFFFFF;
    private static final int PILL_COLOR = 0x26FFFFFF;
    private static final int PILL_COLOR_FOCUSED = 0x40FFFFFF;
    // The ambient glow behind the dock: a big soft radial blob, recoloured (not moved) to
    // whichever tile's own art is focused - most of it sits off the top-left of the screen,
    // the same way the reference look only ever shows the bottom-right curve of it.
    private static final int GLOW_SIZE_DP = 900;
    private static final int GLOW_TOP_DP = -420;
    private static final int GLOW_LEFT_DP = -260;
    private static final int GLOW_ALPHA = 0x8A;
    // A short, calm tween for the pills' focus change.
    private static final int FOCUS_MS = 140;
    private static final android.view.animation.Interpolator FOCUS_INTERP =
            new android.view.animation.DecelerateInterpolator(1.5f);
    // Tile focus feel: the "springs up and settles" motion of a premium TV launcher - a
    // gentle overshoot in, a softer relax out. See style() below.
    private static final float FOCUS_SCALE = 1.12f;
    private static final int FOCUS_LIFT_DP = 10;    // z-lift, so a focused card casts a shadow
    private static final long FOCUS_IN_MS = 220L;   // grow + settle
    private static final long FOCUS_OUT_MS = 160L;  // relax back
    private static final android.view.animation.Interpolator FOCUS_IN_INTERP =
            new android.view.animation.OvershootInterpolator(2.2f);
    private static final android.view.animation.Interpolator FOCUS_OUT_INTERP =
            new android.view.animation.DecelerateInterpolator(1.4f);

    private final Context ctx;
    private final Listener listener;

    public final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final TextView greeting;
    private final TextView clock;
    private final TextView dateLine;
    private final FrameLayout settingsPill;
    private final TextView settingsLabel;
    private final FrameLayout inputsPill;
    private final TextView inputsLabel;
    /** The quick Fire TV settings pills, left of Inputs and Settings; rebuilt with the grid. */
    private final LinearLayout shortcutRow;
    private final List<TextView> shortcutLabels = new ArrayList<TextView>();
    private final List<View> shortcutPills = new ArrayList<View>();
    private String pillsKey = "";
    /** How many quick settings the bar shows before "More settings" and "Settings". */
    private static final int BAR_SHORTCUTS = 3;
    /** A soft, colour-shifting glow behind the dock - see setGlow(). */
    private final View glow;
    private int glowColor;
    /** The dock's title line, showing whichever dock tile is focused; null when no dock. */
    private TextView dockTitleView;
    /**
     * True once this build has already pointed the Settings pill's down-focus at the first
     * tile below it. ScrollView intercepts an UP/DOWN press at its own edge before normal
     * focus search would reach a sibling outside it (the pill lives on root, not in scroll),
     * so the very first row - the dock's, if there is one, else the grid's - gets an
     * explicit nextFocusUpId back to the pill instead of relying on geometry alone.
     */
    private boolean topRowWired;

    private final Map<String, View> cellsByKey = new HashMap<String, View>();
    /** buildCard() cells and whether each is an input/Live TV tile, for repainting their glass. */
    private final Map<View, Boolean> cardCells = new HashMap<View, Boolean>();
    /** Keys of the tiles currently in the dock, so focusing one can scroll to the top. */
    private final Set<String> dockKeys = new HashSet<String>();
    /** The horizontally scrolling rows (the dock, if present), in build order. */
    private final List<HorizontalScrollView> rows = new ArrayList<HorizontalScrollView>();
    private View focusedCell;
    private String focusedKey;
    /** AppModel.order() as of the last build, to spot a re-sort done by the other grid. */
    private String builtOrder;
    private boolean restoreShown;
    /** What holds the restore tile; it slides the tile out when the shared clock runs out. */
    private FrameLayout restoreSlot;
    private final Runnable restoreTimeUp = new Runnable() {
        public void run() { slideOutRestore(); }
    };
    /**
     * Bumped whenever either grid changes a layout setting or hides a tile; each grid builds
     * against the value it saw, so the other one can tell it is out of date.
     */
    private static int layoutGen;
    private int builtGen = -1;
    /** Settings / TV Health render inside this window; Back pops one level. */
    private final List<View> panels = new ArrayList<View>();
    /** For each open panel, the view that had focus when the next one opened over it. */
    private final List<View> panelFocus = new ArrayList<View>();
    /**
     * What the panels opened here call back into. A panel keeps the host it was built with,
     * so when the overlay hands its panels to the activity, this is pointed at the
     * activity's grid and the overlay starts a new one.
     */
    private PanelHost panelHost = new PanelHost(this);
    /** The last key released here, logged when a panel opens from a tile. */
    private int lastKey;
    private int lastKeyDevice;
    private int lastKeySource;
    private long lastKeyAt;
    /** Shared by the overlay and activity grids, so presses across the handoff still count. */
    private static final MenuTaps menuTaps = new MenuTaps();

    /** Simple mode's way into Settings: Menu pressed five times, each within 2 s of the last. */
    static final class MenuTaps {
        static final int COUNT = 5;
        private int n;
        private long last;

        /** @return true on the press that completes the sequence. */
        boolean tap() {
            long now = android.os.SystemClock.uptimeMillis();
            n = (now - last <= 2000L) ? n + 1 : 1;
            last = now;
            if (n < COUNT) return false;
            n = 0;
            return true;
        }
    }

    public HomeGrid(Context c, Listener l) {
        this.ctx = c;
        this.listener = l;
        // Before anything below, including the pills' own icons: AppModel.loaded is a
        // process-wide static, but nothing guarantees some earlier HomeGrid in this process
        // already reached it, and the pill needs the Settings item's icon immediately.
        AppModel.ensureLoaded(c);
        Backgrounds.ensureFolder(c);

        // Overridden so Back and Menu reach us before the focused child consumes them; a
        // key listener on the root would never fire.
        root = new FrameLayout(c) {
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    lastKey = e.getKeyCode();
                    lastKeyDevice = e.getDeviceId();
                    lastKeySource = e.getSource();
                    lastKeyAt = android.os.SystemClock.uptimeMillis();
                }
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && !panels.isEmpty()) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                        View top = panels.get(panels.size() - 1);
                        Object t = top.getTag();
                        if (t instanceof BackHandler && ((BackHandler) t).onBack()) return true;
                        closePanel();
                    }
                    return true;
                }
                // Menu hides the focused tile, matching the stock Fire TV launcher's
                // convention (long-press also works). In Simple mode it hides nothing, and
                // five presses in a row open Settings instead.
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_MENU && panels.isEmpty()) {
                    if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                        if (!Prefs.simpleMode(ctx)) hideFocused();
                        else if (menuTaps.tap()) {
                            logOpen("Settings (Menu x" + MenuTaps.COUNT + ")");
                            showPanel(SettingsPanel.build(ctx, panelHost));
                        }
                    }
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setClipChildren(false);

        // Added first, so it draws behind everything else: a big soft colour blob that
        // recolours (never moves) to whichever tile's own art is focused, the same ambient
        // glow the cleanyourtv default theme sits its dock on.
        glow = new View(c);
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int screenH = c.getResources().getDisplayMetrics().heightPixels;
        int glowSize = Math.round(screenH * 1.05f);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(glowSize, glowSize);
        glowLp.leftMargin = Math.round(screenW * 0.12f) - glowSize / 2;
        glowLp.topMargin = Math.round(screenH * 0.42f) - glowSize / 2;
        root.addView(glow, glowLp);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(dp(MARGIN_DP), dp(TOP_RESERVE_DP), dp(MARGIN_DP), dp(MARGIN_DP));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Top-left: a small tracked-out greeting over a large plain clock, then the date -
        // the only "chrome" on the whole screen besides the Settings pill.
        LinearLayout topLeft = new LinearLayout(c);
        topLeft.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(c);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        greeting.setLetterSpacing(0.12f);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Wrap-content and one line: a match-parent TextView doesn't re-measure its parent
        // on setText, so the column kept its old width and "GOOD AFTERNOON" was cut short.
        greeting.setSingleLine(true);
        topLeft.addView(greeting, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new TextView(c);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.topMargin = dp(2);
        topLeft.addView(clock, clockLp);

        dateLine = new TextView(c);
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // Same as the greeting: wrap-content and one line, or a longer date is cut short.
        dateLine.setSingleLine(true);
        topLeft.addView(dateLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams topLeftLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT);
        topLeftLp.topMargin = dp(TOPBAR_TOP_DP);
        topLeftLp.leftMargin = dp(TOPBAR_SIDE_DP);
        root.addView(topLeft, topLeftLp);

        // Top-right: Inputs, then Settings - the two persistent actions, frosted pills over
        // the glow the way the reference dock's own top bar sits over its ambient colour.
        inputsLabel = new TextView(c);
        inputsPill = buildPill(inputsLabel, ctx.getString(R.string.home_inputs_pill),
                inputsIcon(dp(ICON_SZ_DP)), new Runnable() {
                    public void run() { jumpToInputs(); }
                });
        inputsPill.setId(View.generateViewId());

        settingsLabel = new TextView(c);
        settingsPill = buildPill(settingsLabel, ctx.getString(R.string.home_settings_pill),
                settingsIcon(), new Runnable() {
                    public void run() {
                        logOpen("Settings (pill)");
                        showPanel(SettingsPanel.build(ctx, panelHost));
                    }
                });
        settingsPill.setId(View.generateViewId());

        LinearLayout pillRow = new LinearLayout(c);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setClipChildren(false);
        shortcutRow = new LinearLayout(c);
        shortcutRow.setOrientation(LinearLayout.HORIZONTAL);
        shortcutRow.setClipChildren(false);
        pillRow.addView(shortcutRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams inputsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputsLp.rightMargin = dp(8);
        pillRow.addView(inputsPill, inputsLp);
        pillRow.addView(settingsPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        pillLp.topMargin = dp(TOPBAR_TOP_DP);
        pillLp.rightMargin = dp(TOPBAR_SIDE_DP);
        root.addView(pillRow, pillLp);

        applyBackground();
        build();
        updateClock();
    }

    // ---------------------------------------------------------------- top bar

    /**
     * The quick Fire TV settings: up to BAR_SHORTCUTS screens the user chose, then "More
     * settings" (all of Amazon's), left of Inputs and Settings. The Inputs pill is only there
     * on a TV that has inputs to jump to.
     */
    private void rebuildShortcutPills() {
        boolean inputs = false;
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).type == AppModel.TYPE_INPUT) inputs = true;
        }
        inputsPill.setVisibility(inputs ? View.VISIBLE : View.GONE);

        boolean on = Prefs.shortcutsPlace(ctx) != Prefs.SHORTCUTS_OFF && !Prefs.simpleMode(ctx);
        java.util.Set<String> want = Prefs.shortcuts(ctx);
        List<String[]> screens = FireTvSettingsPanel.available(ctx, null);
        StringBuilder key = new StringBuilder(on ? "on" : "off");
        for (int i = 0; i < screens.size(); i++) {
            if (want.contains(screens.get(i)[FireTvSettingsPanel.ID])) {
                key.append(',').append(screens.get(i)[FireTvSettingsPanel.ID]);
            }
        }
        // Same pills as before: leave them, so one with focus keeps it through a reload.
        if (key.toString().equals(pillsKey)) return;
        pillsKey = key.toString();

        shortcutRow.removeAllViews();
        shortcutLabels.clear();
        shortcutPills.clear();
        if (!on) return;
        int shown = 0;
        for (int i = 0; i < screens.size() && shown < BAR_SHORTCUTS; i++) {
            final String[] s = screens.get(i);
            if (!want.contains(s[FireTvSettingsPanel.ID])) continue;
            addShortcutPill(shortLabel(s[FireTvSettingsPanel.TITLE]),
                    ShortcutIcons.render(s[FireTvSettingsPanel.ID], dp(ICON_SZ_DP)),
                    new Runnable() {
                        public void run() {
                            logOpen(s[FireTvSettingsPanel.TITLE]);
                            SetupPanel.start(ctx, FireTvSettingsPanel.intentFor(
                                    s[FireTvSettingsPanel.ACTION]));
                        }
                    });
            shown++;
        }
        addShortcutPill("More settings", ShortcutIcons.render("more", dp(ICON_SZ_DP)),
                new Runnable() {
                    public void run() {
                        logOpen("More settings");
                        FireTvSettingsPanel.openAll(ctx, panelHost);
                    }
                });
    }

    private void addShortcutPill(String text, Bitmap icon, Runnable action) {
        TextView label = new TextView(ctx);
        FrameLayout pill = buildPill(label, text, icon, action);
        pill.setId(View.generateViewId());
        paintText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        shortcutRow.addView(pill, lp);
        shortcutLabels.add(label);
        shortcutPills.add(pill);
    }

    /** Pills are small: the long screen names lose their second half. */
    private static String shortLabel(String title) {
        if ("Controllers & Bluetooth".equals(title)) return "Controllers";
        if ("Display & sounds".equals(title)) return "Display";
        if ("Amazon account".equals(title)) return "Account";
        if ("Data monitoring".equals(title)) return "Data";
        return title;
    }

    /** The Settings item's own icon, already drawn once by AppModel; reused here at pill size. */
    private Bitmap settingsIcon() {
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).type == AppModel.TYPE_SETTINGS) return all.get(i).icon;
        }
        return null;
    }

    /** A plain monitor glyph for the Inputs pill - this is chrome, not a grid item, so it is
     *  drawn here rather than added to AppModel's own icon set. */
    private Bitmap inputsIcon(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(px * 0.09f);
        stroke.setColor(Color.WHITE);
        android.graphics.RectF screen = new android.graphics.RectF(
                px * 0.10f, px * 0.14f, px * 0.90f, px * 0.68f);
        cv.drawRoundRect(screen, px * 0.08f, px * 0.08f, stroke);

        android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        cv.drawRect(px * 0.42f, px * 0.70f, px * 0.58f, px * 0.80f, fill);
        android.graphics.RectF base = new android.graphics.RectF(
                px * 0.26f, px * 0.80f, px * 0.74f, px * 0.86f);
        cv.drawRoundRect(base, px * 0.02f, px * 0.02f, fill);
        return bmp;
    }

    /** Jumps focus straight to the first Live TV / HDMI tile - the Inputs pill's one job. */
    private void jumpToInputs() {
        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        for (int i = 0; i < all.size(); i++) {
            int t = all.get(i).type;
            if (AppModel.isLive(t) || t == AppModel.TYPE_INPUT) {
                selectKey(all.get(i).key());
                return;
            }
        }
    }

    private FrameLayout buildPill(final TextView label, String text, Bitmap icon,
                                   final Runnable action) {
        final FrameLayout cell = new FrameLayout(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(false);
        cell.setPadding(dp(12), dp(6), dp(15), dp(6));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    dp(PILL_ICON_DP), dp(PILL_ICON_DP));
            ilp.rightMargin = dp(7);
            row.addView(iv, ilp);
        }

        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(label);

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { pillStyle(cell, has); }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
        pillStyle(cell, false);
        return cell;
    }

    /**
     * A frosted-glass look: a soft gradient fill (brighter at the top, like light catching
     * the top of a glass surface) plus a thin bright rim, rather than one flat translucent
     * tone - this device has no real-time blur (RenderEffect needs API 31; this TV is 30),
     * so the glass read comes from the gradient and rim alone.
     */
    private void pillStyle(FrameLayout cell, boolean focused) {
        cell.animate().cancel();
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                focused ? new int[]{ 0x4DFFFFFF, 0x33FFFFFF } : new int[]{ 0x33FFFFFF, 0x1ABEBEBE });
        bg.setCornerRadius(dp(999));
        bg.setStroke(dp(1), focused ? 0x66FFFFFF : 0x29FFFFFF);
        cell.setBackground(bg);
        float scale = focused ? 1.05f : 1f;
        cell.animate().scaleX(scale).scaleY(scale)
                .setDuration(FOCUS_MS).setInterpolator(FOCUS_INTERP).start();
    }

    /**
     * Recolours the ambient glow to the focused tile's own banner colour, or turns it off
     * for a tile with no art (Settings, an HDMI input, TV Health...). Set directly, not
     * animated - a cross-fade here would mean caching two gradients per move for a cosmetic
     * touch the eye mostly reads as "the colour changed", not "it faded".
     */
    private void setGlow(int accent) {
        if (accent == glowColor) return;
        glowColor = accent;
        if (accent == 0) {
            glow.setBackground(null);
            return;
        }
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(glow.getLayoutParams().width / 2f);
        d.setColors(new int[]{ (GLOW_ALPHA << 24) | (accent & 0x00FFFFFF), accent & 0x00FFFFFF });
        glow.setBackground(d);
    }

    // ---------------------------------------------------------------- background / theme

    /** The chosen look; the overlay and the activity re-read it when they come up. */
    private Backgrounds.Preset look;
    /** What is painted now, so an unchanged setting costs only a file stat. */
    private String painted;

    /**
     * Read the background setting and repaint what depends on it. Cheap; no rebuild. A
     * photo is decoded off the main thread, and this runs again once it has loaded.
     */
    public void applyBackground() {
        Backgrounds.Preset p = Backgrounds.current(ctx);
        Bitmap photo = null;
        String state = p.name;
        if (!Backgrounds.photoChosen(ctx)) {
            Backgrounds.drop();
        } else {
            java.io.File f = Backgrounds.photoFile(ctx);
            if (f == null) Backgrounds.drop();
            String key = Backgrounds.keyOf(f);
            photo = Backgrounds.photoFor(key);
            if (f != null && photo == null) {
                Backgrounds.load(ctx, f, new Runnable() {
                    public void run() { applyBackground(); }
                });
                // Keep the previous photo up until the replaced one has loaded.
                photo = Backgrounds.lastPhoto();
                key = "loading";
            }
            state = "photo|" + (photo == null ? "none" : key) + "|" + Backgrounds.dimAlpha(ctx);
        }
        if (state.equals(painted)) return;
        painted = state;
        look = p;
        root.setBackground(photo != null ? Backgrounds.photoDrawable(ctx, photo)
                : Backgrounds.drawable(p));
        paintText(greeting);
        paintText(clock);
        paintText(dateLine);
        paintText(settingsLabel);
        paintText(inputsLabel);
        for (int i = 0; i < shortcutLabels.size(); i++) paintText(shortcutLabels.get(i));
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v instanceof TextView) paintText((TextView) v);
        }
        // The ring and the outline are per look, so every cell is repainted (not animated:
        // this is a palette swap, not a focus change).
        for (View cell : cellsByKey.values()) style(cell, cell == focusedCell, false);
        for (Map.Entry<View, Boolean> e : cardCells.entrySet()) {
            Drawable bg = e.getKey().getBackground();
            if (bg instanceof GradientDrawable) ((GradientDrawable) bg).setColor(cardColor(e.getValue()));
        }
    }

    /**
     * On a photo background, cards are translucent "glass" panels so the picture shows
     * through, matching the premium top-shelf look; the outline() hairline (the photo look
     * carries one) gives them a crisp lit edge. Presets keep the flat card.
     */
    private int cardColor(boolean textTile) {
        if (look == Backgrounds.PHOTO_LOOK) return textTile ? 0xA6222831 : 0xA6161A20;
        return CARD_COLOR;
    }

    /** Header and clock colour; on a photo, a soft shadow keeps them readable. */
    private void paintText(TextView t) {
        t.setTextColor(look.text);
        if (look == Backgrounds.PHOTO_LOOK) t.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        else t.setShadowLayer(0, 0, 0, 0);
    }

    private int dp(int v) { return AppModel.dp(ctx, v); }

    // ---------------------------------------------------------------- public API

    public void updateClock() {
        int mode = Prefs.clockMode(ctx);
        if (mode == Prefs.CLOCK_OFF) {
            clock.setText("");
            greeting.setText("");
            dateLine.setText("");
            return;
        }
        Date now = new Date();
        String fmt = (mode == Prefs.CLOCK_12H) ? "h:mm a" : "HH:mm";
        clock.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        // The greeting over the clock and the date under it follow their own settings.
        boolean g = Prefs.greeting(ctx);
        greeting.setVisibility(g ? View.VISIBLE : View.GONE);
        if (g) greeting.setText(greetingFor(Calendar.getInstance()).toUpperCase(Locale.getDefault()));
        int df = Prefs.dateFormat(ctx);
        dateLine.setVisibility(df != Prefs.DATE_OFF ? View.VISIBLE : View.GONE);
        if (df == Prefs.DATE_LONG) {
            String pat = android.text.format.DateFormat.getBestDateTimePattern(
                    Locale.getDefault(), "EEEEdMMMM");
            dateLine.setText(new SimpleDateFormat(pat, Locale.getDefault()).format(now));
        } else if (df == Prefs.DATE_SHORT) {
            dateLine.setText(android.text.format.DateFormat.getDateFormat(ctx).format(now));
        }
    }

    private String greetingFor(Calendar cal) {
        int h = cal.get(Calendar.HOUR_OF_DAY);
        if (h < 5) return ctx.getString(R.string.home_greeting_night);
        if (h < 12) return ctx.getString(R.string.home_greeting_morning);
        if (h < 18) return ctx.getString(R.string.home_greeting_afternoon);
        return ctx.getString(R.string.home_greeting_evening);
    }

    /** Focus for a fresh Home press: Live TV when it comes first, else as takeFocus(). */
    public void takeStartFocus() {
        // The restore tile is the one thing worth seeing before anything else.
        if (AppModel.restorePopupLive() && selectKey(AppModel.RESTORE_KEY)) {
            scroll.scrollTo(0, 0);
            return;
        }
        if (liveTvFirst() && (selectKey(AppModel.LIVETV_KEY) || selectKey(AppModel.CHANNELS_KEY))) {
            scroll.scrollTo(0, 0);
            return;
        }
        takeFocus();
    }

    /**
     * Live TV first is in effect: switched on, and this TV has the tile. Without the tile
     * the switch is not offered in Settings, so a stale "on" must not keep the layout.
     */
    private boolean liveTvFirst() {
        return Prefs.liveTvFirst(ctx) && AppModel.hasLiveTv();
    }

    /**
     * The dock has no header above it, so focusing one of its tiles scrolls the page to the
     * top. Repeated after layout, which would otherwise scroll it flush with the screen edge.
     */
    private void keepAtTop() {
        scroll.scrollTo(0, 0);
        scroll.post(new Runnable() {
            public void run() { scroll.scrollTo(0, 0); }
        });
    }

    /**
     * Scrolls the dock so the focused tile keeps the row's padding on both sides. The scroll
     * view on its own stops flush with the tile's unscaled edge, which clips the zoom and the
     * focus rim of an end tile against the band at the larger dock sizes.
     */
    private void revealInDock(final View cell) {
        cell.post(new Runnable() {
            public void run() {
                if (!(cell.getParent() instanceof ViewGroup)) return;
                ViewGroup row = (ViewGroup) cell.getParent();
                if (!(row.getParent() instanceof HorizontalScrollView)) return;
                HorizontalScrollView hs = (HorizontalScrollView) row.getParent();
                int x = hs.getScrollX();
                int left = cell.getLeft() - row.getPaddingLeft();
                int right = cell.getRight() + row.getPaddingRight();
                if (left < x) hs.smoothScrollTo(left, 0);
                else if (right > x + hs.getWidth()) hs.smoothScrollTo(right - hs.getWidth(), 0);
            }
        });
    }

    public void takeFocus() {
        if (focusedKey != null && selectKey(focusedKey)) return;
        List<AppModel.Item> items = AppModel.visibleItems(ctx);
        if (!items.isEmpty()) selectKey(items.get(0).key());
    }

    // ------------------------------------------------------------ SettingsPanel.Host

    public boolean isPanelOpen() { return !panels.isEmpty(); }

    /**
     * Hide whatever tile currently has focus.
     * @return true if something was hidden.
     */
    public boolean hideFocused() {
        if (focusedKey == null) return false;
        AppModel.Item target = null;
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            if (focusedKey.equals(all.get(i).key())) { target = all.get(i); break; }
        }
        if (target == null) return false;
        // Settings and TV Health stay reachable so hidden tiles can always be restored.
        if (target.type == AppModel.TYPE_SETTINGS || target.type == AppModel.TYPE_HEALTH
                || target.type == AppModel.TYPE_RESTORE) {
            return false;
        }
        Prefs.setHidden(ctx, target.key(), true);
        try {
            Toast.makeText(ctx, target.label + " hidden - restore it in Settings",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) { }
        layoutGen++;
        rebuild();
        return true;
    }

    public void showPanel(final View v) {
        // Stop the grid, and any panel underneath, from stealing D-pad focus.
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View under = panels.isEmpty() ? null : panels.get(panels.size() - 1);
        // Taken first: blocking and hiding the page below can clear its focus.
        if (under != null) panelFocus.set(panelFocus.size() - 1, under.findFocus());
        if (under instanceof ViewGroup) {
            ((ViewGroup) under).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        // Only the top page is drawn, so a sheet over a sheet does not dim the grid twice.
        if (under != null) under.setVisibility(View.INVISIBLE);
        root.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panels.add(v);
        panelFocus.add(null);
        v.post(new Runnable() { public void run() { v.requestFocus(); } });
    }

    public void closePanel() {
        if (panels.isEmpty()) return;
        View top = panels.remove(panels.size() - 1);
        panelFocus.remove(panelFocus.size() - 1);
        root.removeView(top);
        if (panels.isEmpty()) {
            scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            takeFocus();
            // The restore tile was under the page; its time on screen starts now.
            if (restoreSlot != null) {
                if (AppModel.restorePopupLive()) armRestore();
                else dropRestore();
            }
        } else {
            final View below = panels.get(panels.size() - 1);
            below.setVisibility(View.VISIBLE);
            if (below instanceof ViewGroup) {
                ((ViewGroup) below).setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            }
            // Back on the row that opened the closed page, if it is still there.
            final View was = panelFocus.get(panelFocus.size() - 1);
            below.post(new Runnable() {
                public void run() {
                    if (was == null || !was.isAttachedToWindow() || !was.requestFocus()) {
                        below.requestFocus();
                    }
                }
            });
        }
    }

    /**
     * Back to the plain grid. A Home press lands here, not on a page left open earlier.
     * The first-run wizard stays, since losing it halfway through setup is worse.
     */
    public void closePanels() {
        while (!panels.isEmpty()) {
            if (!Prefs.onboarded(ctx)
                    && panels.get(panels.size() - 1).getTag() instanceof BackHandler) {
                return;
            }
            closePanel();
        }
    }

    /**
     * Take over the panels open in another grid, as they are. The overlay hands them to the
     * activity just before it lifts, so a page opened in the first seconds after Home stays
     * open on the same row. Skipped if a panel is already open here.
     * @return true if panels were moved.
     */
    public boolean adoptPanels(HomeGrid from) {
        if (from == this || from.panels.isEmpty() || !panels.isEmpty()) return false;
        // Settings changed on those pages since this grid last caught up.
        syncOrder();
        applyBackground();
        updateClock();
        View top = from.panels.get(from.panels.size() - 1);
        // Taken first: leaving its window clears the focus.
        View focus = top.findFocus();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (int i = 0; i < from.panels.size(); i++) {
            View v = from.panels.get(i);
            from.root.removeView(v);
            root.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            panels.add(v);
            panelFocus.add(from.panelFocus.get(i));
        }
        from.panels.clear();
        from.panelFocus.clear();
        from.scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        from.panelHost.grid = this;
        from.panelHost = new PanelHost(from);
        if (focus == null || !focus.requestFocus()) top.requestFocus();
        return true;
    }

    /** Passes a panel's calls on to the grid it is in now; see panelHost. */
    private static final class PanelHost implements SettingsPanel.Host {
        HomeGrid grid;
        PanelHost(HomeGrid g) { grid = g; }
        public void rebuildGrid() { grid.rebuildGrid(); }
        public void showPanel(View v) { grid.showPanel(v); }
        public void closePanel() { grid.closePanel(); }
    }

    /** A setting changed from a panel in this grid; the other grid catches up via syncOrder(). */
    public void rebuildGrid() {
        layoutGen++;
        AppModel.invalidateMetrics();
        applyBackground();
        rebuild();
    }

    /** Carried across the overlay -> activity handoff so the highlight does not jump. */
    public String getSelectedKey() { return focusedKey; }

    /** Also carried across the handoff; restoring focus alone does not restore scroll. */
    public int getScrollY() { return scroll.getScrollY(); }

    /** Horizontal offset of each scrolling row (the dock, if present). */
    public int[] getRowScrollX() {
        int[] out = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) out[i] = rows.get(i).getScrollX();
        return out;
    }

    /**
     * Apply scroll offsets after layout: scrollTo() before layout is clamped to 0. The
     * second post covers a late re-layout caused by restoring focus.
     */
    public void applyScroll(final int y, final int[] rowX) {
        if (y <= 0 && rowX == null) return;
        Runnable apply = new Runnable() {
            public void run() {
                if (y > 0) scroll.scrollTo(0, y);
                if (rowX != null) {
                    for (int i = 0; i < rows.size() && i < rowX.length; i++) {
                        if (rowX[i] > 0) rows.get(i).scrollTo(rowX[i], 0);
                    }
                }
            }
        };
        final Runnable again = apply;
        scroll.post(new Runnable() {
            public void run() { again.run(); scroll.post(again); }
        });
    }

    /** Re-select by identity. */
    public boolean selectKey(String key) {
        if (key == null) return false;
        View v = cellsByKey.get(key);
        if (v == null) return false;
        v.requestFocus();
        return true;
    }

    /** Re-sort; rebuilds only if the order changed. @return true if rebuilt. */
    public boolean resort() {
        String keep = focusedKey;
        int y = scroll.getScrollY();
        int[] rx = getRowScrollX();
        if (!AppModel.resort(ctx)) return false;
        build();
        if (panels.isEmpty() && keep != null) selectKey(keep);
        applyScroll(y, rx);
        return true;
    }

    /**
     * The overlay and the activity each have a grid over one shared model, so a re-sort or
     * a settings change made in one leaves the other stale. Rebuild if that happened.
     * @return true if rebuilt.
     */
    public boolean syncOrder() {
        if (AppModel.order().equals(builtOrder) && builtGen == layoutGen) return false;
        android.util.Log.i("Homestead", "grid out of date, rebuilding");
        rebuild();
        return true;
    }

    /** Rebuild the views over the current model; nothing is re-decoded. */
    public void rebuild() {
        String keep = focusedKey;
        int y = scroll.getScrollY();
        int[] rx = getRowScrollX();
        View focus = root.findFocus();
        boolean onPill = focus != null && (focus == settingsPill || focus == inputsPill
                || shortcutPills.contains(focus));
        build();
        // Leave focus alone while a panel is open, or the grid would take it from the panel.
        // And on a pill: a reload of the model must not pull it back to a tile.
        if (panels.isEmpty() && onPill) {
            if (!focus.isAttachedToWindow()) settingsPill.requestFocus();
        } else if (panels.isEmpty()) {
            if (keep == null || !selectKey(keep)) takeFocus();
        }
        applyScroll(y, rx);
        // A restore tile that appears while someone is browsing does not take the focus:
        // a Select meant for the tile under the remote would land on it. It is at the top,
        // and a fresh Home press selects it (takeStartFocus()).
    }

    // ---------------------------------------------------------------- layout

    /**
     * Layout reads metadata from other packages, so one malformed package must not leave a
     * blank home screen. On any failure, draw a minimal row with Settings and TV Health.
     */
    private void build() {
        builtOrder = AppModel.order();
        builtGen = layoutGen;
        updateClock();
        try {
            rebuildShortcutPills();
            buildSections();
        } catch (Throwable t) {
            android.util.Log.e("Homestead", "grid build failed, drawing fallback", t);
            try { buildFallback(); } catch (Throwable ignored) { }
        }
    }

    /** Enough to get to Settings, using nothing that can throw. */
    private void buildFallback() {
        column.removeAllViews();
        cellsByKey.clear();
        cardCells.clear();
        rows.clear();
        dockKeys.clear();
        dockTitleView = null;
        topRowWired = false;
        focusedCell = null;
        restoreSlot = null;

        List<AppModel.Item> safe = new ArrayList<AppModel.Item>();
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            int t = all.get(i).type;
            if (t == AppModel.TYPE_SETTINGS || t == AppModel.TYPE_HEALTH
                    || t == AppModel.TYPE_AMAZON_HOME) {
                safe.add(all.get(i));
            }
        }
        if (!safe.isEmpty()) addTileGrid(safe);
    }

    private void buildSections() {
        column.removeAllViews();
        cellsByKey.clear();
        cardCells.clear();
        rows.clear();
        dockKeys.clear();
        dockTitleView = null;
        topRowWired = false;
        focusedCell = null;
        restoreSlot = null;

        List<AppModel.Item> inputs = new ArrayList<AppModel.Item>();
        List<AppModel.Item> nonTv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> tv = new ArrayList<AppModel.Item>();

        // Live TV first takes the Live TV tile out of its section and puts it first in the
        // dock, wider than the rest, instead of the old full-width tile above everything.
        boolean tvFirst = liveTvFirst();
        AppModel.Item liveTv = null;
        AppModel.Item restore = null;

        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        restoreShown = false;
        for (int i = 0; i < all.size(); i++) {
            AppModel.Item it = all.get(i);
            if (it.type == AppModel.TYPE_RESTORE) {
                restoreShown = true;
                restore = it;
            } else if (tvFirst && AppModel.isLive(it.type) && liveTv == null) {
                liveTv = it;
            } else if (it.type == AppModel.TYPE_INPUT || AppModel.isLive(it.type)
                    || it.type == AppModel.TYPE_AMAZON_HOME
                    || it.type == AppModel.TYPE_SETTINGS || it.type == AppModel.TYPE_HEALTH) {
                inputs.add(it);
            } else if (it.tvApp && it.banner != null) {
                tv.add(it);
            } else {
                nonTv.add(it);
            }
        }

        // Same gating "Show Inputs & System" / "Show Non-TV Applications" always had -
        // there is just no labelled band left to turn off along with the tiles.
        if (!Prefs.showInputs(ctx)) {
            List<AppModel.Item> keep = new ArrayList<AppModel.Item>();
            for (int i = 0; i < inputs.size(); i++) {
                int t = inputs.get(i).type;
                if (t == AppModel.TYPE_SETTINGS || t == AppModel.TYPE_HEALTH) keep.add(inputs.get(i));
            }
            inputs = keep;
        }
        if (!Prefs.showNonTv(ctx)) nonTv.clear();

        // Simple mode drops them too, unless nothing else is left: an empty grid has
        // nothing to focus, so the Menu presses that open Settings may not reach us.
        if (Prefs.simpleMode(ctx)) {
            List<AppModel.Item> rest = new ArrayList<AppModel.Item>();
            for (int i = 0; i < inputs.size(); i++) {
                int t = inputs.get(i).type;
                if (t != AppModel.TYPE_SETTINGS && t != AppModel.TYPE_HEALTH) rest.add(inputs.get(i));
            }
            if (!rest.isEmpty() || !nonTv.isEmpty() || !tv.isEmpty() || liveTv != null) {
                inputs = rest;
            }
        }

        // Dock: Live TV first (if on), then the top few most-used TV apps. AppModel.resort()
        // already put the most relevant ones first, so the dock is just "take the front".
        // Live TV first is a tile of its own above the dock, not one of its slots.
        int dockAppCount = Math.min(DOCK_COUNT, tv.size());
        List<AppModel.Item> dockApps = tv.subList(0, dockAppCount);
        List<AppModel.Item> tvGrid = tv.subList(dockAppCount, tv.size());

        // The restore tile comes first, above the dock: it only exists while something has
        // to be put back, and slides out by itself.
        if (restore != null) addRestoreTile(restore);
        if (liveTv != null || !dockApps.isEmpty()) {
            addDock(liveTv, dockApps);
        }

        List<AppModel.Item> gridItems = new ArrayList<AppModel.Item>();
        gridItems.addAll(inputs);
        gridItems.addAll(tvGrid);
        gridItems.addAll(nonTv);
        if (!gridItems.isEmpty()) addTileGrid(gridItems);
    }

    /** The system undid something Debloat had switched off: one amber tile above everything. */
    private void addRestoreTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        // Like the dock tiles: focusing it scrolls the page back to the top.
        dockKeys.add(it.key());
        stopAt(cell, true, true);
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF9A6A12, 0xFF4A3208 });
        card.setCornerRadius(dp(8));
        cell.setBackground(card);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView title = new TextView(ctx);
        title.setText(it.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);

        List<String> pkgs = AppModel.reverted(ctx);
        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(pkgs.size() == 1 ? R.string.home_restore_hint_one
                : R.string.home_restore_hint_many, revertedNames(pkgs)));
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setMaxLines(2);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(hint);

        // Two lines of hint do not fit a banner cell's height at every density, so the tile
        // grows to its text and is never shorter than a cell.
        cell.setMinimumHeight(AppModel.cellH(ctx));
        cell.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL));

        // The clock starts when a window really shows the tile, scrolled into view: the
        // activity's window can be built while it is in the background, and the page can
        // be scrolled down when the tile appears.
        final android.view.ViewTreeObserver.OnScrollChangedListener inView =
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        if (!AppModel.restoreClockRunning()) armRestore();
                    }
                };
        final FrameLayout slot = new FrameLayout(ctx) {
            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                // Back on screen after its time ran out elsewhere: gone before it is drawn.
                if (!AppModel.restorePopupLive()) { dropRestore(); return; }
                getViewTreeObserver().addOnScrollChangedListener(inView);
                post(new Runnable() { public void run() { armRestore(); } });
            }
            @Override protected void onWindowVisibilityChanged(int v) {
                super.onWindowVisibilityChanged(v);
                if (v != View.VISIBLE) removeCallbacks(restoreTimeUp);
                else if (!AppModel.restorePopupLive()) dropRestore();
                else armRestore();
            }
            @Override protected void onDetachedFromWindow() {
                removeCallbacks(restoreTimeUp);
                getViewTreeObserver().removeOnScrollChangedListener(inView);
                super.onDetachedFromWindow();
            }
        };
        slot.setClipChildren(false);
        slot.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        restoreSlot = slot;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                AppModel.cellW(ctx) * 3 + dp(GAP_DP) * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(GAP_DP);
        column.addView(slot, lp);
    }

    /** The tile is on screen here: start the shared clock if this is the first sight, and
     *  slide out when it runs out, at the same moment as the other grid. A page open over
     *  the grid hides it, so the clock waits for that too (closePanel() arms it again). */
    private void armRestore() {
        FrameLayout slot = restoreSlot;
        if (slot == null || slot.getParent() == null) return;
        if (slot.getWindowVisibility() != View.VISIBLE || !slot.isShown()) return;
        if (!panels.isEmpty() && !AppModel.restoreClockRunning()) return;
        // Not counted until most of it is on screen.
        android.graphics.Rect seen = new android.graphics.Rect();
        if (!AppModel.restoreClockRunning()
                && (!slot.getGlobalVisibleRect(seen) || seen.height() < slot.getHeight() / 2)) {
            return;
        }
        long left = AppModel.startRestorePopup();
        android.util.Log.i("Homestead", "restore tile on screen ("
                + ctx.getClass().getSimpleName() + "), " + left + " ms left");
        slot.removeCallbacks(restoreTimeUp);
        slot.postDelayed(restoreTimeUp, Math.max(0L, left));
    }

    /** Its time ran out while this grid was not showing it: take it out with no animation. */
    private void dropRestore() {
        FrameLayout slot = restoreSlot;
        if (slot == null) return;
        restoreSlot = null;
        cellsByKey.remove(AppModel.RESTORE_KEY);
        slot.setVisibility(View.GONE);
        root.post(new Runnable() {
            public void run() { rebuild(); }
        });
    }

    /** Time is up: the tile slides out to the left, then the rows below close the gap. */
    private void slideOutRestore() {
        final FrameLayout slot = restoreSlot;
        if (slot == null || slot.getParent() == null || slot.getChildCount() == 0) return;
        restoreSlot = null;
        android.util.Log.i("Homestead", "restore tile time up, sliding out ("
                + ctx.getClass().getSimpleName() + ")");
        final View tile = slot.getChildAt(0);
        // Out of reach from here on, so Select cannot land on a tile that is leaving.
        cellsByKey.remove(AppModel.RESTORE_KEY);
        boolean had = tile.hasFocus();
        tile.setFocusable(false);
        if (had && panels.isEmpty()) takeStartFocus();
        tile.animate().translationX(-(slot.getWidth() + dp(MARGIN_DP))).alpha(0f)
                .setDuration(350L)
                .withEndAction(new Runnable() {
                    public void run() { collapseRestore(slot); }
                });
    }

    private void collapseRestore(final FrameLayout slot) {
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) slot.getLayoutParams();
        final int h = slot.getHeight();
        final int m = lp.bottomMargin;
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(250L);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float f = (Float) va.getAnimatedValue();
                lp.height = Math.round(h * f);
                lp.bottomMargin = Math.round(m * f);
                slot.setLayoutParams(lp);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                // The model no longer offers the tile, so this drops the empty slot.
                if (slot.getParent() != column) return;
                // Seen at the top of the page, it leaves the page at the top: the rows that
                // moved up would otherwise sit under the edge with their header cut off.
                boolean atTop = scroll.getScrollY() < h;
                rebuild();
                if (atTop && panels.isEmpty()) keepAtTop();
            }
        });
        a.start();
    }

    /** The reverted packages by name, short enough for the tile: "A", "A and B",
     *  "A, B and C", or "A, B and 4 more". */
    private static String revertedNames(List<String> pkgs) {
        int n = pkgs.size();
        if (n == 0) return "";
        String a = Debloat.titleOf(pkgs.get(0));
        if (n == 1) return a;
        String b = Debloat.titleOf(pkgs.get(1));
        if (n == 2) return a + " and " + b;
        if (n == 3) return a + ", " + b + " and " + Debloat.titleOf(pkgs.get(2));
        return a + ", " + b + " and " + (n - 2) + " more";
    }

    /** Switch off again whatever the system switched back on; open Setup if there is no link. */
    private void restoreReverted() {
        List<String> pkgs = AppModel.reverted(ctx);
        if (pkgs.isEmpty()) return;
        if (!AdbBridge.canRun(ctx)) {
            Toast.makeText(ctx, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
            showPanel(DebloatPanel.build(ctx, panelHost));
            return;
        }
        final String[] list = pkgs.toArray(new String[pkgs.size()]);
        String[] cmds = new String[list.length];
        for (int i = 0; i < list.length; i++) cmds[i] = AdbBridge.disableCommand(list[i]);
        Toast.makeText(ctx, R.string.home_connecting, Toast.LENGTH_SHORT).show();
        AdbBridge.runRetry(cmds, new AdbBridge.Callback() {
            public void onDone(boolean ok, String out) {
                // Never started: nothing changed, so keep the tile and the record of what
                // is still on, and say so. Forgetting them here would hide the problem.
                if (!AdbBridge.ran(out)) {
                    Toast.makeText(ctx, R.string.home_restore_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder kept = new StringBuilder();
                for (int i = 0; i < list.length; i++) {
                    boolean off = Debloat.stateOf(ctx, list[i]) == Debloat.DISABLED;
                    Prefs.noteDisabled(ctx, list[i], off);
                    if (!off) {
                        if (kept.length() > 0) kept.append(", ");
                        kept.append(Debloat.titleOf(list[i]));
                    }
                }
                // Fire OS refuses some packages; the record is dropped so the tile does not
                // come back for good, but the user is told.
                if (kept.length() > 0) {
                    Toast.makeText(ctx, ctx.getString(R.string.home_restore_refused, kept),
                            Toast.LENGTH_LONG).show();
                }
                AppModel.reload(ctx);
                rebuild();
            }
        });
    }

    /**
     * A scrolling row of cards. HorizontalScrollView moves the focus for Left and Right itself
     * and reports the key handled, so the system's navigation click, which every other D-pad
     * move makes, never plays; the row plays it when the focus actually moved.
     */
    private static final class CardRow extends HorizontalScrollView {
        CardRow(Context c) { super(c); }

        @Override public boolean arrowScroll(int direction) {
            View before = findFocus();
            boolean handled = super.arrowScroll(direction);
            View after = findFocus();
            if (handled && after != null && after != before && after != this) {
                playSoundEffect(android.view.SoundEffectConstants
                        .getContantForFocusDirection(direction));
            }
            return handled;
        }
    }

    /** The translucent dock band: Live TV (if first) wider, then the most-used TV apps. */
    /**
     * Called for every cell in whichever row is the first one built - every dock cell
     * (the dock, when present, is always row one), or the grid's first row when there is
     * no dock (see the dockKeys.isEmpty() guard at its one call site below). Points the
     * cell's UP back at the Settings pill, and - the first time this runs per build - both
     * pills' DOWN back at that cell; LEFT/RIGHT between the two pills needs no wiring, since
     * they are plain siblings in the same row, not across a ScrollView boundary. See
     * topRowWired.
     */
    private void wireTopRow(View cell) {
        if (cell.getId() == View.NO_ID) cell.setId(View.generateViewId());
        cell.setNextFocusUpId(settingsPill.getId());
        if (!topRowWired) {
            topRowWired = true;
            settingsPill.setNextFocusDownId(cell.getId());
            inputsPill.setNextFocusDownId(cell.getId());
            for (int i = 0; i < shortcutPills.size(); i++) {
                shortcutPills.get(i).setNextFocusDownId(cell.getId());
            }
        }
    }

    private void addDock(AppModel.Item liveTv, List<AppModel.Item> dockApps) {
        TextView title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        paintText(title);
        title.setText(dockApps.isEmpty() ? "" : dockApps.get(0).label);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(10);

        // Pin the dock to the bottom of the screen, wallpaper open above it, the way the
        // reference dock sits: a spacer fills whatever the title + band leave of the first
        // screen. The grid follows below the fold, and keepAtTop() brings the dock back.
        title.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        int bandH = dp(DOCK_H_DP[Prefs.dockSize(ctx)]) + 2 * dp(DOCK_PAD_DP);
        int spacer = screenH - dp(TOP_RESERVE_DP) - title.getMeasuredHeight() - tlp.bottomMargin
                - bandH - dp(DOCK_BOTTOM_DP);
        // The restore tile sits above the spacer while it is up: take its room from the spacer,
        // so the dock stays at the bottom of the screen instead of being pushed down by it.
        if (restoreSlot != null) {
            restoreSlot.measure(View.MeasureSpec.makeMeasureSpec(
                    AppModel.cellW(ctx) * 3 + dp(GAP_DP) * 2, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            spacer -= restoreSlot.getMeasuredHeight() + dp(GAP_DP);
        }
        int dockH = dp(DOCK_H_DP[Prefs.dockSize(ctx)]);
        int heroH = dp(HERO_H_DP);
        if (liveTv != null) spacer -= heroH + dp(GAP_DP);
        if (spacer > 0) column.addView(new View(ctx), new LinearLayout.LayoutParams(1, spacer));

        // Live TV first: a wide tile above the dock, on the wallpaper.
        View hero = null;
        if (liveTv != null) {
            hero = buildCard(liveTv);
            wireTopRow(hero);
            stopAt(hero, true, true);
            dockKeys.add(liveTv.key());
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    dockH * 16 / 9 * 2 + dp(GAP_DP), heroH);
            hlp.bottomMargin = dp(GAP_DP);
            column.addView(hero, hlp);
        }

        column.addView(title, tlp);
        dockTitleView = title;

        HorizontalScrollView hs = new CardRow(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        rows.add(hs);

        int h = dp(DOCK_H_DP[Prefs.dockSize(ctx)]);
        int normalW = h * 16 / 9;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        // The band's inset lives here, inside the scrolling row, so a focused tile scaled to
        // FOCUS_SCALE has room inside the scroll view's clip - and clipToPadding is off,
        // since clipChildren(false) alone still clips to the padding, which cut the focused
        // tile (and its focus rim) off at its unscaled edges. The sides grow with the widest
        // tile, so an end tile's zoom still fits at the larger dock sizes.
        int grow = Math.round(normalW * (FOCUS_SCALE - 1f) / 2f) + dp(2);
        int padV = dp(DOCK_PAD_DP);
        int padH = Math.max(padV, grow);
        row.setPadding(padH, padV, padH, padV);
        row.setClipToPadding(false);

        for (int i = 0; i < dockApps.size(); i++) {
            AppModel.Item it = dockApps.get(i);
            View cell = buildTile(it);
            if (hero == null) {
                wireTopRow(cell);
            } else {
                // UP from the dock goes to the Live TV tile, which goes on to the pills.
                cell.setNextFocusUpId(hero.getId());
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(normalW, h);
            if (i > 0) lp.leftMargin = dp(GAP_DP);
            stopAt(cell, i == 0, i == dockApps.size() - 1);
            row.addView(cell, lp);
            dockKeys.add(it.key());
        }
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The band clips the scroll view to its own edges, so tiles that don't fit (at the
        // larger dock sizes) are cut off cleanly inside it instead of running on past its
        // end. The row's padding keeps a focused tile's zoom inside that clip.
        FrameLayout band = new FrameLayout(ctx);
        GradientDrawable bandBg = new GradientDrawable();
        bandBg.setCornerRadius(dp(CORNER_DP + 8));
        bandBg.setColor(DOCK_BAND_COLOR);
        band.setBackground(bandBg);
        band.addView(hs, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(DOCK_BOTTOM_DP);
        column.addView(band, blp);
    }

    /** The single grid below the dock: banner tiles and icon+label cards, side by side. */
    private void addTileGrid(List<AppModel.Item> items) {
        int w = AppModel.cellW(ctx);
        int h = AppModel.cellH(ctx);
        int cols = AppModel.columns(ctx);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(GAP_DP);
                column.addView(row, rlp);
            }
            AppModel.Item it = items.get(i);
            View cell = (it.banner != null) ? buildTile(it) : buildCard(it);
            // Only when there is no dock above this grid - otherwise UP from here must
            // reach the dock normally, not jump straight past it to the pill.
            if (i < cols && dockKeys.isEmpty()) wireTopRow(cell);
            boolean rowEnd = i % cols == cols - 1 || i == items.size() - 1;
            stopAt(cell, i % cols == 0, rowEnd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % cols != 0) lp.leftMargin = dp(GAP_DP);
            row.addView(cell, lp);
        }
    }

    // ---------------------------------------------------------------- cells

    private FrameLayout newCell(final AppModel.Item it) {
        final FrameLayout cell = new FrameLayout(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(false);
        // A rounded-rect outline so a focused tile's elevation shadow is shaped like the
        // card, not the square it would otherwise fall back to.
        final int radius = dp(CORNER_DP);
        cell.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cell.setClipToOutline(true);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    focusedCell = v;
                    focusedKey = it.key();
                    style(v, true);
                    setGlow(it.accent);
                    if (dockKeys.contains(it.key())) {
                        keepAtTop();
                        revealInDock(v);
                        if (dockTitleView != null) dockTitleView.setText(it.label);
                    }
                } else {
                    style(v, false, true);
                }
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // TV Health opens a panel in this window, so it is not reported as a
                // pick; that would make an overlay host dismiss itself.
                boolean leaves = it.type != AppModel.TYPE_HEALTH && it.type != AppModel.TYPE_CHANNELS;
                if (listener != null && leaves) listener.onPick(it);
                act(it);
            }
        });
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            // In Simple mode a held Select falls through to a normal click.
            public boolean onLongClick(View v) {
                return !Prefs.simpleMode(ctx) && hideFocused();
            }
        });
        cellsByKey.put(it.key(), cell);
        style(cell, false, false);
        return cell;
    }

    /** The preset's edge around an unfocused cell, or null when the preset has none. */
    private Drawable outline() {
        if (look == null || look.outline == 0) return null;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(CORNER_DP));
        d.setStroke(dp(1), look.outline);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    /**
     * The selection cursor: a bright rounded rim over the focused cell. A crisp near-white
     * inner edge reads cleanly on both light banners and a photo background, the Apple-TV
     * look. Drawn as the cell's foreground so it sits above the banner, with the same
     * corner radius as the tile so it never shows sharp corners against rounded art.
     */
    private Drawable focusRim() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(CORNER_DP));
        d.setStroke(dp(3), 0xFFFFFFFF);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    private void style(View v, boolean focused) { style(v, focused, true); }

    /**
     * Focus treatment, animated for a premium feel: the cell springs to {@link #FOCUS_SCALE}
     * with a gentle overshoot, lifts on the z-axis so it casts a rounded shadow (newCell()
     * gives every cell a rounded outline), and gains the bright selection rim. The rim is
     * set immediately, so the right edge shows even if the overlay -> activity handoff cuts
     * the animation short. animate is false for a cell's first style (nothing was focused a
     * moment ago to animate from) and for the repaint applyBackground() does across every
     * cell at once, where an instant re-skin reads as a palette swap, not a focus move.
     */
    private void style(View v, boolean focused, boolean animate) {
        if (!(v instanceof FrameLayout)) return;
        FrameLayout f = (FrameLayout) v;
        f.animate().cancel();
        f.setForeground(focused ? focusRim() : outline());
        float scale = focused ? FOCUS_SCALE : 1f;
        float z = focused ? dp(FOCUS_LIFT_DP) : 0f;
        if (!animate || !f.isAttachedToWindow() || f.getWidth() == 0) {
            f.setScaleX(scale);
            f.setScaleY(scale);
            f.setTranslationZ(z);
            return;
        }
        f.animate().scaleX(scale).scaleY(scale).translationZ(z)
                .setDuration(focused ? FOCUS_IN_MS : FOCUS_OUT_MS)
                .setInterpolator(focused ? FOCUS_IN_INTERP : FOCUS_OUT_INTERP)
                .withLayer()
                .start();
    }

    /**
     * Keep focus on the first or last cell of a row when the D-pad points past it. Otherwise
     * the focus search picks the nearest cell that way anywhere on the page, often in another
     * row (a scrolled card row has cards off the left edge), which reads as a jump.
     */
    private static void stopAt(View cell, final boolean first, final boolean last) {
        if (!first && !last) return;
        cell.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, android.view.KeyEvent e) {
                return (first && code == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                        || (last && code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            }
        });
    }

    /**
     * A TV app: full-bleed banner, already rounded in the bitmap - which means its four
     * corners are genuinely transparent pixels (AppModel clips banners to a rounded path on
     * decode), and some apps' own banner art carries large transparent regions besides
     * (a logo on nothing, rather than a full-bleed image). Without an opaque plate under it,
     * either one lets whatever is behind the tile - the dock band, the photo wallpaper -
     * show through unevenly next to tiles whose own art happens to be fully opaque, which
     * reads as a rendering glitch rather than a deliberate look.
     */
    private View buildTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(dp(CORNER_DP));
        plate.setColor(CARD_COLOR);
        cell.setBackground(plate);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(it.banner);
        cell.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    /** An input, Live TV, Settings, TV Health, Amazon Home, or a non-TV app: one flat card. */
    private View buildCard(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(CORNER_DP));
        boolean textTile = it.type == AppModel.TYPE_INPUT || AppModel.isLive(it.type);
        card.setColor(cardColor(textTile));
        cell.setBackground(card);
        cardCells.put(cell, Boolean.valueOf(textTile));

        if (textTile) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            if (it.glyph != 0) {
                ImageView ic = new ImageView(ctx);
                ic.setImageResource(it.glyph);
                ic.setColorFilter(0xFFBFE9EE,
                        android.graphics.PorterDuff.Mode.SRC_IN);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
                ilp.bottomMargin = dp(6);
                col.addView(ic, ilp);
            }
            TextView tv = new TextView(ctx);
            tv.setText(it.label);
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(tv);
            if (it.connected) {
                TextView tag = new TextView(ctx);
                tag.setText(R.string.home_connected);
                tag.setTextColor(0xFF7BD88F);
                tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tag.setGravity(Gravity.CENTER);
                col.addView(tag);
            }
            cell.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return cell;
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        ImageView ic = new ImageView(ctx);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (it.icon != null) ic.setImageBitmap(it.icon);
        int isz = dp(34);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = dp(10);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(it.label);
        tv.setTextColor(0xFFB8B8BD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        tv.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    // ---------------------------------------------------------------- launching

    /**
     * Says what opened a panel. Device -1 means the key was injected rather than sent by a
     * remote; HDMI-CEC keys also carry the HDMI source.
     */
    private void logOpen(String what) {
        String how = (lastKeyAt == 0L) ? "no key seen"
                : "key " + android.view.KeyEvent.keyCodeToString(lastKey) + " from device "
                        + lastKeyDevice + ", source 0x" + Integer.toHexString(lastKeySource)
                        + ((lastKeySource & android.view.InputDevice.SOURCE_HDMI)
                                == android.view.InputDevice.SOURCE_HDMI ? " (HDMI-CEC)" : "")
                        + ", " + (android.os.SystemClock.uptimeMillis() - lastKeyAt) + "ms ago";
        android.util.Log.i("Homestead", "opening " + what + " (" + how + ")");
    }

    private void act(AppModel.Item it) {
        if (it.type == AppModel.TYPE_SETTINGS) {
            // The tile opens the TV's own Settings; Homestead's are on the Settings pill.
            logOpen(it.label);
            if (!SetupPanel.openTvSettings(ctx)) showPanel(SettingsPanel.build(ctx, panelHost));
            return;
        }
        if (it.type == AppModel.TYPE_HEALTH) {
            logOpen(it.label);
            showPanel(HealthPanel.build(ctx, panelHost));
            return;
        }
        if (it.type == AppModel.TYPE_RESTORE) {
            restoreReverted();
            return;
        }
        if (it.type == AppModel.TYPE_CHANNELS) {
            logOpen(it.label);
            showPanel(ChannelsPanel.build(ctx, panelHost));
            return;
        }
        if (it.type == AppModel.TYPE_LIVETV) {
            logOpen(it.label);
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("livetv://showLiveTVButtonContent/"));
                i.setComponent(new ComponentName(AppModel.LIVETV_PKG, AppModel.LIVETV_SVC));
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Throwable t) {
                Toast.makeText(ctx, R.string.home_livetv_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (it.type == AppModel.TYPE_INPUT) {
            Intent i = new Intent(ctx, HdmiActivity.class);
            i.putExtra(HdmiActivity.EXTRA_INPUT_ID, it.inputId);
            i.putExtra(HdmiActivity.EXTRA_LABEL, it.label);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { ctx.startActivity(i); } catch (Throwable t) { }
            return;
        }
        if (it.type == AppModel.TYPE_AMAZON_HOME) {
            HomeRedirectService.suspend(30000L);
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(AppModel.AMAZON_LAUNCHER, AppModel.AMAZON_HOME_CLS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t) { }
            return;
        }
        // Fire OS lets only its own Settings/home open some apps' screens, so a direct launch is
        // refused and the tile did nothing. Open the TV's Settings and point the way with a brief
        // bubble, or (for one with no settings home) just show where to go.
        if (AppModel.AIRPLAY_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_airplay_hint);
            return;
        }
        if (AppModel.PROFILE_PICKER_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_profile_hint);
            return;
        }
        if (AppModel.AMAZON_KIDS_PKG.equals(it.pkg)) {
            openTvSettings(R.string.home_kids_hint);
            return;
        }
        if (AppModel.WSIW_PKG.equals(it.pkg)) {
            Toast.makeText(ctx, R.string.home_wsiw_hint, Toast.LENGTH_LONG).show();
            return;
        }
        AppModel.noteLaunched(ctx, it);
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(it.pkg, it.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return;
        } catch (Throwable t) { }
        try {
            Intent f = ctx.getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (f != null) { f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(f); }
        } catch (Throwable t) { }
    }

    /**
     * Open Fire TV's own Settings menu (the standard LEANBACK_SETTINGS entry any launcher may use) and
     * show a brief bubble saying where to go, for apps Fire OS won't let a third-party launcher open
     * directly. The Home redirect stands down for Settings, so the overlay does not cover it.
     */
    private void openTvSettings(int hintRes) {
        HomeRedirectService.suspend(30000L);
        Toast.makeText(ctx, hintRes, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { ctx.startActivity(i); return; } catch (Throwable t) { }
        // Fallback: the launcher's main Settings activity by name.
        try {
            Intent e = new Intent(Intent.ACTION_MAIN)
                    .setClassName(AppModel.AMAZON_LAUNCHER, "com.amazon.tv.launcher.ui.MainSettingsActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(e); return;
        } catch (Throwable t) { }
        try { ctx.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { }
    }
}
