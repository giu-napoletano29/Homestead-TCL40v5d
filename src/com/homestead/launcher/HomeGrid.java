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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher UI, built against a plain Context so the same screen can be hosted by an
 * Activity or a WindowManager overlay.
 *
 * Sections (headers, scrolling card rows, a banner-tile grid) are plain focusable views in
 * a ScrollView rather than a GridView: no recycling, so each view is its item and focus
 * survives a re-sort by key. Data and bitmaps come from the process-wide AppModel.
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

    // Layout metrics.
    private static final int MARGIN_DP = 32;
    private static final int GAP_DP = 16;
    private static final int CARD_W_DP = 167;   // wide card, non-TV row
    private static final int CARD_H_DP = 94;
    private static final int HEADER_SP = 21;
    private static final int HEADER_TOP_DP = 28;
    private static final int HEADER_BOTTOM_DP = 12;

    private final Context ctx;
    private final Listener listener;

    public final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final TextView clock;
    private final LinearLayout topBar;
    /** The date beside a large clock, drawn only with the top bar. */
    private final TextView dateText;
    private boolean barShown;
    private View barSpacer, barRule;

    private final Map<String, View> cellsByKey = new HashMap<String, View>();
    /** The horizontally scrolling section rows, in build order. */
    private final List<HorizontalScrollView> rows = new ArrayList<HorizontalScrollView>();
    private View focusedCell;
    private String focusedKey;
    /** The Live TV first tile, or null when that layout is not in use. */
    private View topTile;
    /** AppModel.order() as of the last build, to spot a re-sort done by the other grid. */
    private String builtOrder;
    private boolean restoreShown;
    private boolean restoreAppeared;
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

        scroll = new ScrollView(c) {
            // Only the top edge fades under the top bar; the bottom row is not dimmed.
            @Override protected float getBottomFadingEdgeStrength() { return 0f; }
        };
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
        column.setPadding(dp(MARGIN_DP), dp(46), dp(MARGIN_DP), dp(MARGIN_DP));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new TextView(c);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        // The clock, with the settings pills to its left when that shortcut row is chosen.
        dateText = new TextView(c);
        dateText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dateText.setLetterSpacing(0.1f);
        dateText.setSingleLine(true);
        topBar = new LinearLayout(c) {
            // The date is the first thing to go when the clock and the pills do not fit side by
            // side (a 12-hour clock is wider): better no date than a pill pushed off the screen.
            @Override protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                if (dateText.getVisibility() != View.VISIBLE) return;
                int avail = MeasureSpec.getSize(w) - getPaddingLeft() - getPaddingRight();
                int need = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (v == barSpacer || v.getVisibility() == View.GONE) continue;
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    need += v.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                }
                if (need > avail) {
                    dateText.setVisibility(View.GONE);
                    if (barRule != null) barRule.setVisibility(View.GONE);
                    super.onMeasure(w, h);
                }
            }
        };
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        clp.topMargin = dp(6);
        // Room round the pills for the focus ring, which grows past their edge.
        topBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        topBar.setClipToPadding(false);
        clp.leftMargin = dp(MARGIN_DP - 6);
        clp.rightMargin = dp(33 - 6);
        root.addView(topBar, clp);

        AppModel.ensureLoaded(c);
        Backgrounds.ensureFolder(c);
        applyBackground();
        build();
        updateClock();
    }

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
        paintText(clock);
        paintText(dateText);
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v instanceof TextView) paintText((TextView) v);
        }
        // The ring and the outline are per look, so every cell is repainted.
        for (View cell : cellsByKey.values()) style(cell, cell == focusedCell);
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
        if (mode == Prefs.CLOCK_OFF) { clock.setText(""); dateText.setText(""); return; }
        Date now = new Date();
        if (mode == Prefs.CLOCK_12H && barShown) {
            // The large clock keeps AM/PM small, so a 12-hour time is no wider than a 24-hour one.
            String t = new SimpleDateFormat("h:mm", Locale.getDefault()).format(now);
            String ap = new SimpleDateFormat("a", Locale.getDefault()).format(now);
            SpannableString s = new SpannableString(t + " " + ap);
            s.setSpan(new RelativeSizeSpan(0.4f), t.length() + 1, s.length(), 0);
            clock.setText(s);
        } else {
            String fmt = (mode == Prefs.CLOCK_12H) ? "h:mm a" : "HH:mm";
            clock.setText(new SimpleDateFormat(fmt, Locale.getDefault()).format(now));
        }
        dateText.setText(new SimpleDateFormat("EEE d MMM", Locale.getDefault())
                .format(now).toUpperCase(Locale.getDefault()));
    }

    /** Focus for a fresh Home press: Live TV when it comes first, else as takeFocus(). */
    public void takeStartFocus() {
        // The restore tile is the one thing worth seeing before anything else.
        if (AppModel.hasRestore() && selectKey(AppModel.RESTORE_KEY)) {
            scroll.scrollTo(0, 0);
            return;
        }
        if (liveTvFirst() && selectKey(AppModel.LIVETV_KEY)) {
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
     * The Live TV first tile has no header above it, so focusing it scrolls the page to the
     * top. Repeated after layout, which would otherwise scroll it flush with the screen edge.
     */
    private void keepAtTop() {
        scroll.scrollTo(0, 0);
        scroll.post(new Runnable() {
            public void run() { scroll.scrollTo(0, 0); }
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
            android.widget.Toast.makeText(ctx,
                    target.label + " hidden - restore it in Settings",
                    android.widget.Toast.LENGTH_SHORT).show();
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

    /** Horizontal offset of each card row. */
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
        build();
        // Leave focus alone while a panel is open, or the grid would take it from the panel.
        if (panels.isEmpty()) {
            if (keep == null || !selectKey(keep)) takeFocus();
        }
        applyScroll(y, rx);
        // The tile is the one thing worth seeing: move to it the moment it appears.
        if (restoreAppeared && panels.isEmpty() && selectKey(AppModel.RESTORE_KEY)) keepAtTop();
    }

    // ---------------------------------------------------------------- layout

    /**
     * Layout reads metadata from other packages, so one malformed package must not leave a
     * blank home screen. On any failure, draw a minimal row with Settings and TV Health.
     */
    private void build() {
        builtOrder = AppModel.order();
        builtGen = layoutGen;
        try {
            buildTopBar();
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
        rows.clear();
        focusedCell = null;
        topTile = null;

        List<AppModel.Item> safe = new ArrayList<AppModel.Item>();
        List<AppModel.Item> all = AppModel.allItems();
        for (int i = 0; i < all.size(); i++) {
            int t = all.get(i).type;
            if (t == AppModel.TYPE_SETTINGS || t == AppModel.TYPE_HEALTH
                    || t == AppModel.TYPE_AMAZON_HOME) {
                safe.add(all.get(i));
            }
        }
        addHeader("Homestead", true);
        if (!safe.isEmpty()) addCardRow(safe);
    }

    private void buildSections() {
        column.removeAllViews();
        cellsByKey.clear();
        rows.clear();
        focusedCell = null;

        List<AppModel.Item> inputs = new ArrayList<AppModel.Item>();
        List<AppModel.Item> nonTv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> tv = new ArrayList<AppModel.Item>();
        List<AppModel.Item> shortcuts = new ArrayList<AppModel.Item>();

        // Live TV first takes the Live TV tile out of its section and puts it above them all.
        boolean tvFirst = liveTvFirst();
        AppModel.Item liveTv = null;
        AppModel.Item restore = null;
        topTile = null;

        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        boolean hadRestore = restoreShown;
        restoreShown = false;
        for (int i = 0; i < all.size(); i++) {
            AppModel.Item it = all.get(i);
            if (it.type == AppModel.TYPE_RESTORE) {
                restoreShown = true;
                restore = it;
            } else if (it.type == AppModel.TYPE_SHORTCUT) {
                shortcuts.add(it);
            } else if (barShown && it.type == AppModel.TYPE_SETTINGS) {
                // The top bar has its own Settings pill; the tile would only repeat it.
                continue;
            } else if (tvFirst && it.type == AppModel.TYPE_LIVETV) {
                liveTv = it;
            } else if (it.type == AppModel.TYPE_INPUT || it.type == AppModel.TYPE_LIVETV
                    || it.type == AppModel.TYPE_AMAZON_HOME
                    || it.type == AppModel.TYPE_SETTINGS || it.type == AppModel.TYPE_HEALTH) {
                inputs.add(it);
            } else if (it.tvApp && it.banner != null) {
                tv.add(it);
            } else {
                nonTv.add(it);
            }
        }

        restoreAppeared = restoreShown && !hadRestore;
        // Settings and TV Health survive the section being switched off.
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

        String inputsTitle = ctx.getString(R.string.home_sec_inputs);
        String nonTvTitle = ctx.getString(R.string.home_sec_nontv);
        String tvTitle = ctx.getString(R.string.home_sec_tv);
        String shortcutsTitle = ctx.getString(R.string.home_sec_settings);
        boolean scTop = !shortcuts.isEmpty() && Prefs.shortcutsPlace(ctx) == Prefs.SHORTCUTS_TOP;
        boolean scBottom = !shortcuts.isEmpty()
                && Prefs.shortcutsPlace(ctx) == Prefs.SHORTCUTS_BOTTOM;
        boolean first = true;
        if (restore != null) { addRestoreTile(restore); first = false; }
        if (tvFirst) {
            if (liveTv != null)    { addLiveTvTile(liveTv); first = false; }
            if (scTop)             { addHeader(shortcutsTitle, first); first = false; addCardRow(shortcuts); }
            if (!tv.isEmpty())     { addHeader(tvTitle, first); first = false; addTileGrid(tv); }
            if (!inputs.isEmpty()) { addHeader(inputsTitle, first); first = false; addCardRow(inputs); }
            if (!nonTv.isEmpty())  { addHeader(nonTvTitle, first); first = false; addCardRow(nonTv); }
            if (scBottom)          { addHeader(shortcutsTitle, first); addCardRow(shortcuts); }
            return;
        }
        if (scTop)             { addHeader(shortcutsTitle, first); first = false; addCardRow(shortcuts); }
        if (!inputs.isEmpty()) { addHeader(inputsTitle, first); first = false; addCardRow(inputs); }
        if (!nonTv.isEmpty())  { addHeader(nonTvTitle, first); first = false; addCardRow(nonTv); }
        if (!tv.isEmpty())     { addHeader(tvTitle, first); first = false; addTileGrid(tv); }
        if (scBottom)          { addHeader(shortcutsTitle, first); addCardRow(shortcuts); }
    }

    /** Live TV first: one large tile, two banner cells wide, on its own row. */
    private void addLiveTvTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        if (topTile == null) topTile = cell;
        // Alone on its row, so Left or Right would otherwise jump to a tile in another row.
        stopAt(cell, true, true);
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF168494, 0xFF0A3642 });
        card.setCornerRadius(dp(8));
        cell.setBackground(card);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(24), dp(12), dp(24), dp(12));

        TextView title = new TextView(ctx);
        title.setText(it.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.home_livetv_hint));
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(hint);

        cell.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int w = AppModel.cellW(ctx) * 2 + dp(GAP_DP);
        int h = AppModel.cellH(ctx) * 3 / 2;
        column.addView(cell, new LinearLayout.LayoutParams(w, h));
    }

    /** The most pills the top bar takes, More settings and Settings included. */
    private static final int BAR_MAX = 5;

    /**
     * The "top bar" shortcut row: small rounded buttons left of the clock, at the same height
     * so nothing below moves. The chosen screens come first, then More settings (Amazon's
     * full menu) and Settings (this launcher's). Not shown in Simple mode.
     */
    private void buildTopBar() {
        topBar.removeAllViews();
        boolean bar = Prefs.shortcutsPlace(ctx) == Prefs.SHORTCUTS_BAR && !Prefs.simpleMode(ctx);
        barShown = bar;
        boolean showClock = Prefs.clockMode(ctx) != Prefs.CLOCK_OFF;

        View spacer = new View(ctx);
        barSpacer = spacer;
        barRule = null;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);

        if (!bar) {
            // The plain layout: the clock alone at the right.
            clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            clock.setTypeface(Typeface.DEFAULT);
            dateText.setVisibility(View.GONE);
            topBar.addView(spacer, slp);
            topBar.addView(clock);
            updateClock();
            setScrollTop(0);
            column.setPadding(dp(MARGIN_DP), dp(46), dp(MARGIN_DP), dp(MARGIN_DP));
            return;
        }

        // Clock and date at the left, the glass pills at the right.
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        topBar.addView(clock);
        if (showClock) {
            View rule = new View(ctx);
            barRule = rule;
            rule.setBackgroundColor(0x55FFFFFF);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(dp(1), dp(30));
            rlp.leftMargin = dp(16);
            rlp.rightMargin = dp(16);
            topBar.addView(rule, rlp);
            dateText.setVisibility(View.VISIBLE);
            dateText.setAlpha(0.8f);
            topBar.addView(dateText);
        } else {
            dateText.setVisibility(View.GONE);
        }
        topBar.addView(spacer, slp);

        List<AppModel.Item> pills = new ArrayList<AppModel.Item>();
        List<AppModel.Item> all = AppModel.visibleItems(ctx);
        for (int i = 0; i < all.size() && pills.size() < BAR_MAX - 2; i++) {
            if (all.get(i).type == AppModel.TYPE_SHORTCUT) pills.add(all.get(i));
        }
        pills.add(barItem(AppModel.SC_MORE, "More settings", "more"));
        pills.add(barItem(AppModel.SC_SETTINGS, "Settings", "settings"));
        for (int i = 0; i < pills.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(PILL_H_DP));
            if (i > 0) lp.leftMargin = dp(12);
            View pill = buildPill(pills.get(i));
            // Nothing above the bar, and no jumping to a tile past either end of it.
            boolean firstPill = i == 0, lastPill = i == pills.size() - 1;
            stopKeys(pill, android.view.KeyEvent.KEYCODE_DPAD_UP,
                    firstPill ? android.view.KeyEvent.KEYCODE_DPAD_LEFT : -1,
                    lastPill ? android.view.KeyEvent.KEYCODE_DPAD_RIGHT : -1);
            topBar.addView(pill, lp);
        }
        // The page scrolls below the bar, never behind it, and fades out as it nears it. The
        // padding keeps the first tile well clear of the pills when the page is at the top.
        updateClock();
        setScrollTop(BAR_H_DP);
        column.setPadding(dp(MARGIN_DP), dp(28), dp(MARGIN_DP), dp(MARGIN_DP));
    }

    /** The bar's height, which the scrolling page starts under. */
    private static final int BAR_H_DP = 58;

    /** Where the scrolling page starts; with the bar it also fades at that edge. */
    private void setScrollTop(int dpTop) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        if (lp.topMargin != dp(dpTop)) {
            lp.topMargin = dp(dpTop);
            scroll.setLayoutParams(lp);
        }
        scroll.setVerticalFadingEdgeEnabled(dpTop > 0);
        scroll.setFadingEdgeLength(dp(48));
        // The root normally lets children draw past their own edge so a focused tile can grow; under the bar
        // that would let it show through, so here it is cut at the edge instead.
        root.setClipChildren(dpTop > 0);
    }

    private static final int PILL_H_DP = 40;

    private AppModel.Item barItem(String action, String label, String icon) {
        AppModel.Item it = new AppModel.Item();
        it.type = AppModel.TYPE_SHORTCUT;
        it.label = label;
        it.cls = action;
        it.icon = ShortcutIcons.render(icon, dp(40));
        return it;
    }

    /** Pills are small: the long screen names lose their second half. */
    private static String shortLabel(String title) {
        if ("Controllers & Bluetooth".equals(title)) return "Controllers";
        if ("Display & sounds".equals(title)) return "Display";
        if ("Amazon account".equals(title)) return "Account";
        if ("Data monitoring".equals(title)) return "Data";
        return title;
    }

    /**
     * A glass pill: a faint white wash that fades down, a hairline light edge, fully rounded.
     * Over a photo or a gradient it picks up what is behind it instead of hiding it. The
     * focus ring follows the same rounded shape and the fill brightens, so the pill reads
     * as lit from within rather than boxed.
     */
    private View buildPill(AppModel.Item it) {
        final FrameLayout cell = newCell(it);
        final int radius = dp(PILL_H_DP / 2);
        final GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0x40FFFFFF, 0x14FFFFFF });
        glass.setCornerRadius(radius);
        glass.setStroke(dp(1), 0x59FFFFFF);
        cell.setBackground(glass);
        // newCell draws a tile outline until the first focus change; a pill has none.
        cell.setForeground(null);

        final View.OnFocusChangeListener base = cell.getOnFocusChangeListener();
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (base != null) base.onFocusChange(v, has);
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(radius);
                if (has) {
                    ring.setStroke(dp(2), 0xF2FFFFFF);
                    ring.setColor(0x26FFFFFF);
                    cell.setForeground(ring);
                    cell.setScaleX(1.06f);
                    cell.setScaleY(1.06f);
                } else {
                    cell.setForeground(null);
                    cell.setScaleX(1f);
                    cell.setScaleY(1f);
                }
            }
        });

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(16), 0);

        ImageView ic = new ImageView(ctx);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (it.icon != null) ic.setImageBitmap(it.icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.rightMargin = dp(8);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(shortLabel(it.label));
        tv.setTextColor(0xF2FFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLetterSpacing(0.03f);
        tv.setSingleLine(true);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cell.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    /** The system undid something Debloat had switched off: one amber tile above everything. */
    private void addRestoreTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        topTile = cell;
        stopAt(cell, true, true);
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFF9A6A12, 0xFF4A3208 });
        card.setCornerRadius(dp(8));
        cell.setBackground(card);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setPadding(dp(24), dp(10), dp(24), dp(10));

        TextView title = new TextView(ctx);
        title.setText(it.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText(ctx.getString(R.string.home_restore_hint, revertedNames()));
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.addView(hint);

        cell.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                AppModel.cellW(ctx) * 3 + dp(GAP_DP) * 2, AppModel.cellH(ctx));
        lp.bottomMargin = dp(GAP_DP);
        column.addView(cell, lp);
    }

    /** The reverted packages by name, up to three: "A, B and 2 more". */
    private String revertedNames() {
        List<String> pkgs = AppModel.reverted(ctx);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size() && i < 3; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Debloat.titleOf(pkgs.get(i)));
        }
        if (pkgs.size() > 3) sb.append(" and ").append(pkgs.size() - 3).append(" more");
        return sb.toString();
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

    private void addHeader(String text, boolean first) {
        TextView h = new TextView(ctx);
        h.setText(text);
        paintText(h);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_SP);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = first ? 0 : dp(HEADER_TOP_DP);
        lp.bottomMargin = dp(HEADER_BOTTOM_DP);
        column.addView(h, lp);
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

    /** Wide cards in a horizontally scrolling row. */
    private void addCardRow(List<AppModel.Item> items) {
        HorizontalScrollView hs = new CardRow(ctx);
        rows.add(hs);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (int i = 0; i < items.size(); i++) {
            View cell = buildCard(items.get(i));
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
            if (i > 0) lp.leftMargin = dp(GAP_DP);
            stopAt(cell, i == 0, i == items.size() - 1);
            row.addView(cell, lp);
        }
        hs.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 6-column grid of banner tiles, built as plain rows. */
    private void addTileGrid(List<AppModel.Item> items) {
        int w = AppModel.cellW(ctx);
        int h = AppModel.cellH(ctx);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % AppModel.columns(ctx) == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(GAP_DP);
                column.addView(row, rlp);
            }
            View cell = buildTile(items.get(i));
            boolean rowEnd = i % AppModel.columns(ctx) == AppModel.columns(ctx) - 1
                    || i == items.size() - 1;
            stopAt(cell, i % AppModel.columns(ctx) == 0, rowEnd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % AppModel.columns(ctx) != 0) lp.leftMargin = dp(GAP_DP);
            row.addView(cell, lp);
        }
    }

    // ---------------------------------------------------------------- cells

    private FrameLayout newCell(final AppModel.Item it) {
        final FrameLayout cell = new FrameLayout(ctx);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(false);
        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has) {
                    focusedCell = v;
                    focusedKey = it.key();
                    style(v, true);
                    if (v == topTile) keepAtTop();
                } else {
                    style(v, false);
                }
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Settings and TV Health open a panel in this window, so they are not
                // reported as a pick; that would make an overlay host dismiss itself.
                boolean leaves = it.type != AppModel.TYPE_SETTINGS
                        && it.type != AppModel.TYPE_HEALTH;
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
        style(cell, false);
        return cell;
    }

    /** The preset's edge around an unfocused cell, or null when the preset has none. */
    private Drawable outline() {
        if (look == null || look.outline == 0) return null;
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(8));
        d.setStroke(dp(1), look.outline);
        d.setColor(Color.TRANSPARENT);
        return d;
    }

    /**
     * Focus treatment: 1.1x scale and a ring in the preset's colour (dark on the light
     * default, since white is invisible on white banners; light on the dark presets). Set
     * directly rather than via a selector so it survives the window losing focus during
     * the overlay -> activity handoff.
     */
    private void style(View v, boolean focused) {
        if (!(v instanceof FrameLayout)) return;
        FrameLayout f = (FrameLayout) v;
        if (focused) {
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(8));
            ring.setStroke(dp(3), look.ring);
            ring.setColor(Color.TRANSPARENT);
            f.setForeground(ring);
            f.setScaleX(1.10f);
            f.setScaleY(1.10f);
            f.setTranslationZ(dp(8));
        } else {
            f.setForeground(outline());
            f.setScaleX(1f);
            f.setScaleY(1f);
            f.setTranslationZ(0f);
        }
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

    /** Swallow the given D-pad keys on a cell, for the bar's edges and its Up. */
    private static void stopKeys(View cell, final int... codes) {
        cell.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, android.view.KeyEvent e) {
                for (int c : codes) if (c == code) return true;
                return false;
            }
        });
    }

    /** A TV app: full-bleed banner, already rounded in the bitmap. */
    private View buildTile(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        ImageView img = new ImageView(ctx);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setImageBitmap(it.banner);
        cell.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return cell;
    }

    /** An input, Amazon Home, or a non-TV app: icon + label on a card. */
    private View buildCard(AppModel.Item it) {
        FrameLayout cell = newCell(it);
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(8));
        boolean textTile = it.type == AppModel.TYPE_INPUT || it.type == AppModel.TYPE_LIVETV;
        // Live TV first: inputs go a quiet grey so only the Live TV tile stands out, and
        // app labels get larger and brighter.
        boolean tvFirst = liveTvFirst();
        if (textTile) card.setColor(tvFirst ? 0xFF2A333D : 0xFF0E5A63);
        else card.setColor(0xFF033058);
        cell.setBackground(card);

        if (textTile) {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            if (it.glyph != 0) {
                ImageView ic = new ImageView(ctx);
                ic.setImageResource(it.glyph);
                ic.setColorFilter(tvFirst ? 0xFFB4BECA : 0xFFBFE9EE,
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
        row.setPadding(dp(8), dp(6), dp(8), dp(6));

        ImageView ic = new ImageView(ctx);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (it.icon != null) ic.setImageBitmap(it.icon);
        int isz = dp(CARD_H_DP) * 62 / 100;
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
        ilp.rightMargin = dp(8);
        row.addView(ic, ilp);

        TextView tv = new TextView(ctx);
        tv.setText(it.label);
        tv.setTextColor(tvFirst ? 0xFFD5DDE5 : 0xFFA2AFBB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, tvFirst ? 14 : 12);
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
            logOpen(it.label);
            showPanel(SettingsPanel.build(ctx, panelHost));
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
        if (it.type == AppModel.TYPE_SHORTCUT) {
            logOpen(it.label);
            if (AppModel.SC_MORE.equals(it.cls)) {
                FireTvSettingsPanel.openAll(ctx, panelHost);
            } else if (AppModel.SC_SETTINGS.equals(it.cls)) {
                showPanel(SettingsPanel.build(ctx, panelHost));
            } else {
                SetupPanel.start(ctx, FireTvSettingsPanel.intentFor(it.cls));
            }
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
}
