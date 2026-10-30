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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * "Canali TV": what an antenna's channel list would be on a TV with no tuner. A full page of
 * cards, one per live channel of the streaming apps that are installed, each opening its app
 * on the channel itself where the app takes a link for it. Only apps that are on the device
 * are listed, so the page is never a list of things that do nothing.
 *
 * The cards carry the channel's logo. The official artwork is not shipped: a logo is a PNG
 * the user puts in the app's folder (logos/rai1.png), else a wordmark is drawn in code, and
 * an app that has no channels of its own shows its banner. Programme previews would need a
 * programme guide from the broadcasters' servers, which these apps do not offer to others.
 */
final class ChannelsPanel {

    /** One live channel, or an app whose live channels are chosen inside it. */
    private static final class Entry {
        final String group, id, name, pkg, uri;
        final int accent;
        /** A channel of an app that has no link per channel: drawn as a wordmark, opens the app. */
        final boolean wordmark;
        Entry(String group, String id, String name, String pkg, String uri, int accent) {
            this(group, id, name, pkg, uri, accent, false);
        }
        Entry(String group, String id, String name, String pkg, String uri, int accent,
                boolean wordmark) {
            this.group = group; this.id = id; this.name = name; this.pkg = pkg; this.uri = uri;
            this.accent = accent; this.wordmark = wordmark;
        }
    }

    private static final String RAIPLAY = "it.rainet.androidtv";
    private static final String INFINITY = "it.mediaset.infinitytv1";

    private static final String G_RAI = "Rai";
    private static final String G_MEDIASET = "Mediaset";
    private static final String G_MEDIASET_RADIO = "Radio Mediaset";
    private static final String G_OTHER = "Altre app con dirette";

    /**
     * In the order the page lists them. RaiPlay takes https://www.raiplay.it/dirette/<channel>
     * and starts that channel's live stream (tried with Rai 1 and Rai Movie on a Fire TV
     * Stick). Mediaset Infinity takes no link per channel (a made-up one ends in an error
     * box), so it opens on its own home, which has an "on air now" row.
     */
    private static final Entry[] CATALOGUE = {
            // Rai's own colours: 1 blue, 2 red, 3 green, 4 violet (the first four are the brand's
            // known ones; the rest are close guesses at each channel's look).
            rai("rai1", "Rai 1", 0xFF0057B8), rai("rai2", "Rai 2", 0xFFD7192E),
            rai("rai3", "Rai 3", 0xFF1FA15A), rai("rai4", "Rai 4", 0xFF6B2FA0),
            rai("rai5", "Rai 5", 0xFF2A8BB5), rai("raimovie", "Rai Movie", 0xFFE2711D),
            rai("raipremium", "Rai Premium", 0xFFC24C84), rai("raigulp", "Rai Gulp", 0xFF3BAF8C),
            rai("raiyoyo", "Rai YoYo", 0xFFE0842E), rai("raistoria", "Rai Storia", 0xFF7B6A4F),
            rai("raiscuola", "Rai Scuola", 0xFF4F7FB0),
            new Entry(G_RAI, "raiplay", "RaiPlay", RAIPLAY, null, 0xFF1E5BD6),
            // Infinity opens a channel from https://mediasetinfinity.mediaset.it/diretta/<slug>
            // (the slugs are the ones its own Dirette page links to). Checked on a Stick: Canale 5,
            // Italia 1 and Rete 4 open and play; the others follow the same pattern. A link
            // made up from the channel's name alone ends on its home, so the slug must be exact.
            mediaset("canale5", "Canale 5", 0xFF1C5BC0, "canale5_cC5"),
            mediaset("italia1", "Italia 1", 0xFFC98A00, "italia1_cI1"),
            mediaset("rete4", "Rete 4", 0xFFE2571E, "rete4_cR4"),
            mediaset("20", "20", 0xFF2A9D8F, "20mediaset_cLB"),
            mediaset("la5", "La5", 0xFFD81B8C, "la5_cKA"),
            mediaset("iris", "Iris", 0xFF8E2DE2, "iris_cKI"),
            mediaset("italia2", "Italia 2", 0xFF6A4FB3, "italia2_cI2"),
            mediaset("topcrime", "Top Crime", 0xFF8B1A1A, "topcrime_cLT"),
            mediaset("cine34", "Cine34", 0xFFB26A00, "cine34_cB6"),
            mediaset("focus", "Focus", 0xFF2E7D32, "focus_cFU"),
            mediaset("mediasetextra", "Mediaset Extra", 0xFF455A64, "mediasetextra_cKQ"),
            mediaset("tgcom24", "TGCOM24", 0xFF0D47A1, "video_cKF"),
            mediaset("twentyseven", "TwentySeven", 0xFF00838F, "_cTS"),
            mediaset("boing", "Boing", 0xFF00A0DC, "kids_cKB"),
            mediaset("cartoonito", "Cartoonito", 0xFFF28C28, "kids_cLA"),
            mediaset("mediasetitalia", "Mediaset Italia", 0xFF37474F, "mediaset-international_cMY"),
            new Entry(G_MEDIASET, "infinity", "Mediaset Infinity", INFINITY, null, 0xFF6A2BD6),
            // The radios the same page lists, with their video feed.
            radio("r101", "R101", 0xFFE53935, "r101_b7871969_cER"),
            radio("radio105", "Radio 105", 0xFFE08A00, "105.net_cEC"),
            radio("virgin", "Virgin Radio", 0xFFC62828, "virginradio.it_cEW"),
            radio("rmc", "Radio Montecarlo", 0xFF1565C0, "radiomontecarlo.net_cBB"),
            radio("norba", "Radio Norba", 0xFF2E7D32, "radio-norba-tv_cU4"),
            new Entry(G_OTHER, "plex", "Plex", "com.plexapp.android", null, 0xFFE5A00D),
            new Entry(G_OTHER, "pluto", "Pluto TV", "tv.pluto.android", null, 0xFF2B2B2B),
            new Entry(G_OTHER, "discovery", "discovery+", "com.discovery.discoplus", null, 0xFF1F4FD8),
            new Entry(G_OTHER, "now", "NOW", "com.nowtv.it", null, 0xFF0AA39A),
            new Entry(G_OTHER, "dazn", "DAZN", "com.dazn", null, 0xFF1A1A1A),
    };

    private static Entry mediaset(String id, String name, int accent, String slug) {
        return new Entry(G_MEDIASET, id, name, INFINITY,
                "https://mediasetinfinity.mediaset.it/diretta/" + slug, accent, true);
    }

    private static Entry radio(String id, String name, int accent, String slug) {
        return new Entry(G_MEDIASET_RADIO, id, name, INFINITY,
                "https://mediasetinfinity.mediaset.it/diretta/" + slug, accent, true);
    }

    private static Entry rai(String id, String name, int accent) {
        return new Entry(G_RAI, id, name, RAIPLAY, "https://www.raiplay.it/dirette/" + id, accent);
    }

    private static final int COLUMNS = 5;
    private static final int MARGIN_DP = 48;
    private static final int GAP_DP = 18;

    private ChannelsPanel() { }

    /**
     * The tile's picture: a television with a play triangle on its screen and a small antenna
     * on top, drawn in white lines so it reads at a glance and follows no logo.
     */
    static Bitmap tileIcon(int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        float s = px;
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(s * 0.065f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);

        // The set.
        RectF body = new RectF(s * 0.08f, s * 0.28f, s * 0.92f, s * 0.80f);
        cv.drawRoundRect(body, s * 0.10f, s * 0.10f, line);
        // The antenna: two rods from the middle of the top edge.
        cv.drawLine(s * 0.50f, s * 0.28f, s * 0.30f, s * 0.08f, line);
        cv.drawLine(s * 0.50f, s * 0.28f, s * 0.70f, s * 0.08f, line);
        // The stand.
        cv.drawLine(s * 0.32f, s * 0.90f, s * 0.68f, s * 0.90f, line);
        // Play.
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(s * 0.43f, s * 0.42f);
        p.lineTo(s * 0.43f, s * 0.66f);
        p.lineTo(s * 0.65f, s * 0.54f);
        p.close();
        cv.drawPath(p, fill);
        return b;
    }

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

    /**
     * For the search: {id, name, group} of every channel and app the page lists. Apps whose
     * channels are chosen inside them (RaiPlay, Infinity) are in it too, and open the app.
     */
    static List<String[]> searchable(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        List<Entry> list = available(c);
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            out.add(new String[]{ e.id, e.name, tabName(e.group) });
        }
        return out;
    }

    /** Opens one of the entries searchable() lists. Main thread. */
    static boolean openById(Context c, String id) {
        List<Entry> list = available(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                open(c, list.get(i));
                return true;
            }
        }
        return false;
    }

    /** Names of what is listed, for the model's change detection. */
    static String signature(Context c) {
        StringBuilder sb = new StringBuilder();
        List<Entry> list = available(c);
        for (int i = 0; i < list.size(); i++) sb.append(list.get(i).name).append(',');
        return sb.toString();
    }

    // ---------------------------------------------------------------- the page

    static View build(final Context c, final SettingsPanel.Host host) {
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int margin = AppModel.dp(c, MARGIN_DP);
        int gap = AppModel.dp(c, GAP_DP);
        // The scroll view's own side padding (12 dp) comes off the width the cards share.
        int cardW = (screenW - 2 * (margin + AppModel.dp(c, 12)) - (COLUMNS - 1) * gap) / COLUMNS;
        int cardH = cardW * 9 / 16;

        FrameLayout root = new FrameLayout(c);
        // Clickable so it swallows touches, but not focusable: a clickable view takes focus
        // itself, and then no card ever gets it.
        root.setClickable(true);
        root.setFocusable(false);
        // The background chosen for the home screen, under a dark veil so the cards read.
        root.setBackground(pageBackground(c));
        View veil = new View(c);
        veil.setBackgroundColor(0x8C000000);
        root.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(margin, AppModel.dp(c, 34), margin, 0);

        TextView title = new TextView(c);
        title.setText("Canali TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Fonts.get(c, Fonts.MEDIUM));
        page.addView(title);
        TextView sub = new TextView(c);
        sub.setText("Le dirette delle app installate. Premi OK su un canale per guardarlo.");
        sub.setTextColor(0xFF9BB0C6);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = AppModel.dp(c, 6);
        page.addView(sub, slp);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        // Room for a focused card's zoom, top and bottom.
        sv.setPadding(AppModel.dp(c, 12), AppModel.dp(c, 10), AppModel.dp(c, 12), AppModel.dp(c, 40));
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        sv.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // One tab per group (Rai, Mediaset, Radio Mediaset, other apps): Left and Right on the
        // strip change tab, Down goes into that tab's cards and Up comes back to the strip.
        List<Entry> list = available(c);
        List<String> groups = new ArrayList<String>();
        for (int i = 0; i < list.size(); i++) {
            if (!groups.contains(list.get(i).group)) groups.add(list.get(i).group);
        }
        Pager pager = new Pager(c, list, groups, column, sv, cardW, cardH, gap);
        LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setClipChildren(false);
        for (int i = 0; i < groups.size(); i++) {
            View chip = pager.makeChip(i);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.leftMargin = AppModel.dp(c, 10);
            tabs.addView(chip, clp);
        }
        if (!groups.isEmpty()) {
            // Nothing beyond the ends of the strip to move to.
            View first = pager.chips.get(0), last = pager.chips.get(groups.size() - 1);
            first.setNextFocusLeftId(first.getId());
            last.setNextFocusRightId(last.getId());
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = AppModel.dp(c, 14);
        tlp.bottomMargin = AppModel.dp(c, 4);
        page.addView(tabs, page.indexOfChild(sv), tlp);
        if (!groups.isEmpty()) pager.select(0);

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** What a tab strip entry says for a group. */
    private static String tabName(String group) {
        return G_OTHER.equals(group) ? "Altre app" : group;
    }

    /** The tab strip and the cards of the tab showing; switching rebuilds only the cards. */
    private static final class Pager {
        final Context c;
        final List<Entry> list;
        final List<String> groups;
        final LinearLayout column;
        final ScrollView sv;
        final int cardW, cardH, gap;
        final List<View> chips = new ArrayList<View>();
        /** Logos drawn once: going back and forth between tabs must not redraw them. */
        final java.util.Map<String, Bitmap> logos = new java.util.HashMap<String, Bitmap>();
        int current = -1, focused = -1;

        Pager(Context c, List<Entry> list, List<String> groups, LinearLayout column,
                ScrollView sv, int cardW, int cardH, int gap) {
            this.c = c; this.list = list; this.groups = groups; this.column = column;
            this.sv = sv; this.cardW = cardW; this.cardH = cardH; this.gap = gap;
        }

        View makeChip(final int idx) {
            final FrameLayout chip = new FrameLayout(c);
            chip.setId(View.generateViewId());
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            TextView t = new TextView(c);
            t.setText(tabName(groups.get(idx)));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTypeface(Fonts.get(c, Fonts.MEDIUM));
            t.setPadding(AppModel.dp(c, 18), AppModel.dp(c, 8), AppModel.dp(c, 18), AppModel.dp(c, 8));
            chip.addView(t);
            chip.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) {
                    if (has) {
                        focused = idx;
                        select(idx);
                    } else if (focused == idx) {
                        focused = -1;
                    }
                    paint(idx);
                }
            });
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    // OK drops into the tab's first card.
                    if (column.getChildCount() > 0 && column.getChildAt(0) instanceof ViewGroup) {
                        ViewGroup row = (ViewGroup) column.getChildAt(0);
                        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
                    }
                }
            });
            chips.add(chip);
            paint(idx);
            return chip;
        }

        void paint(int i) {
            FrameLayout chip = (FrameLayout) chips.get(i);
            TextView t = (TextView) chip.getChildAt(0);
            boolean sel = i == current, foc = i == focused;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AppModel.dp(c, 6));
            bg.setColor(foc ? 0x59FFFFFF : sel ? 0x2EFFFFFF : 0x00000000);
            if (foc) bg.setStroke(AppModel.dp(c, 2), Color.WHITE);
            chip.setBackground(bg);
            t.setTextColor(sel || foc ? Color.WHITE : 0xB3FFFFFF);
        }

        void select(int idx) {
            if (idx == current) return;
            int old = current;
            current = idx;
            if (old >= 0) paint(old);
            paint(idx);

            column.removeAllViews();
            String group = groups.get(idx);
            View chip = chips.get(idx);
            LinearLayout row = null;
            int inRow = 0, rows = 0;
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (!e.group.equals(group)) continue;
                if (row == null || inRow == COLUMNS) {
                    row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setClipChildren(false);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (rows > 0) rlp.topMargin = gap;
                    column.addView(row, rlp);
                    inRow = 0;
                    rows++;
                }
                View card = card(c, e, cardW, cardH, logos);
                // Up from the first row goes back to this tab's chip.
                if (rows == 1) card.setNextFocusUpId(chip.getId());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
                if (inRow > 0) lp.leftMargin = gap;
                row.addView(card, lp);
                inRow++;
            }

            TextView note = new TextView(c);
            note.setText("Logo ufficiali: copia i PNG (rai1.png, raimovie.png...) in "
                    + "Android/data/com.homestead.launcher/files/logos/. I canali Mediaset si "
                    + "aprono direttamente in Mediaset Infinity.");
            note.setTextColor(0xFF74889C);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = AppModel.dp(c, 30);
            column.addView(note, nlp);
            sv.scrollTo(0, 0);
        }
    }

    /** The look the home screen has now: its preset, or the photo when it is chosen and loaded. */
    static Drawable pageBackground(Context c) {
        try {
            if (Backgrounds.photoChosen(c)) {
                Bitmap photo = Backgrounds.photoFor(Backgrounds.keyOf(Backgrounds.photoFile(c)));
                if (photo != null) return Backgrounds.photoDrawable(c, photo);
            }
            return Backgrounds.drawable(Backgrounds.current(c));
        } catch (Throwable t) {
            return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{ 0xFF0A1824, 0xFF050B12 });
        }
    }

    /** One focusable card with the channel's logo; it lifts and gets a white rim on focus. */
    private static View card(final Context c, final Entry e, int w, int h,
            java.util.Map<String, Bitmap> logos) {
        final FrameLayout cell = new FrameLayout(c);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(true);
        final float radius = AppModel.dp(c, 6);

        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius);
        base.setColor(0xFF111B26);
        cell.setBackground(base);

        ImageView logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap lb = logos.get(e.id);
        if (lb == null) {
            lb = logoBitmap(c, e, w, h);
            logos.put(e.id, lb);
        }
        logo.setImageBitmap(lb);
        cell.addView(logo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // The image is square-cornered; the cell clips it to the card's rounded shape.
        cell.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cell.setClipToOutline(true);

        final GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(radius);
        rim.setStroke(AppModel.dp(c, 3), Color.WHITE);
        rim.setColor(Color.TRANSPARENT);
        final View rimView = new View(c);
        rimView.setVisibility(View.INVISIBLE);
        rimView.setBackground(rim);
        cell.addView(rimView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                rimView.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
                v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f)
                        .translationZ(has ? AppModel.dp(c, 10) : 0f).setDuration(140L).start();
            }
        });
        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(c, e); }
        });
        return cell;
    }

    // ---------------------------------------------------------------- logos

    /** The logo to show: the user's PNG, the app's banner, or a wordmark drawn here. */
    private static Bitmap logoBitmap(Context c, Entry e, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The channel's own colour, a little deepened towards the bottom - not the dark veil it
        // used to sit under, which turned every card a muddy navy.
        int top = blend(e.accent, 0xFFFFFFFF, 0.06f);
        int bottom = blend(e.accent, 0xFF050A10, 0.36f);
        // Straight down, not corner to corner: a calmer, flatter look, as a TV's tiles have.
        bg.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);

        Bitmap own = userLogo(c, e, w, h);
        if (own != null) {
            float s = Math.min(w * 0.82f / own.getWidth(), h * 0.62f / own.getHeight());
            float lw = own.getWidth() * s, lh = own.getHeight() * s;
            cv.drawBitmap(own, null, new RectF((w - lw) / 2f, (h - lh) / 2f,
                    (w + lw) / 2f, (h + lh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        }
        if (e.uri == null && !e.wordmark) {
            // An app with no channel of its own: its banner fills the card.
            Drawable banner = appBanner(c, e.pkg);
            if (banner != null) {
                banner.setBounds(0, 0, w, h);
                banner.draw(cv);
                return out;
            }
        }
        wordmark(c, cv, e, w, h);
        return out;
    }

    private static Bitmap userLogo(Context c, Entry e, int w, int h) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            File f = new File(new File(dir, "logos"), e.id + ".png");
            if (!f.isFile() || f.length() == 0 || f.length() > 4L * 1024L * 1024L) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The way a TV channel list draws a channel with no logo to hand: a small, spaced label
     * over one big word or number, white on the card's colour and nothing else. "Rai 1" is RAI
     * over 1, "Canale 5" is CANALE over 5, "Top Crime" is TOP over CRIME, "Iris" is IRIS alone.
     */
    private static void wordmark(Context c, Canvas cv, Entry e, int w, int h) {
        String name = e.name;
        String label = null, main = name;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*(\\d+)$").matcher(name);
        if (m.matches()) {
            main = m.group(2);
            if (m.group(1).length() > 0) label = m.group(1);
        } else {
            int sp = name.indexOf(' ');
            if (sp > 0) {
                label = name.substring(0, sp);
                main = name.substring(sp + 1);
            }
        }
        java.util.Locale it = java.util.Locale.ITALY;
        boolean number = main.matches("\\d+");
        String mainText = number ? main : main.toUpperCase(it);

        Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
        big.setTypeface(Fonts.get(c, Fonts.BLACK));
        big.setColor(Color.WHITE);
        big.setTextAlign(Paint.Align.CENTER);
        float bigSize = h * (number ? 0.52f : 0.30f);
        big.setTextSize(bigSize);
        if (!number) {
            float tw = big.measureText(mainText), max = w * 0.80f;
            if (tw > max) big.setTextSize(bigSize * max / tw);
        }
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setTypeface(Fonts.get(c, Fonts.BOLD));
        small.setColor(0xD9FFFFFF);
        small.setTextAlign(Paint.Align.CENTER);
        small.setLetterSpacing(0.26f);
        small.setTextSize(h * 0.115f);

        float capBig = big.getTextSize() * 0.72f;
        float labelBlock = label == null ? 0 : small.getTextSize() * 2.1f;
        float top = (h - (labelBlock + capBig)) / 2f;
        if (label != null) {
            cv.drawText(label.toUpperCase(it), w / 2f, top + small.getTextSize() * 0.9f, small);
        }
        cv.drawText(mainText, w / 2f, top + labelBlock + capBig, big);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    private static Drawable appBanner(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationBanner(pkg);
            return d == null ? null : d.mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable appIcon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            return d == null ? null : d.mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- opening

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
