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
        Entry(String group, String id, String name, String pkg, String uri, int accent) {
            this.group = group; this.id = id; this.name = name; this.pkg = pkg; this.uri = uri;
            this.accent = accent;
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
     * Stick). Mediaset Infinity takes no link per channel (a made-up one ends in an error
     * box), so it opens on its own home, which has an "on air now" row.
     */
    private static final Entry[] CATALOGUE = {
            rai("rai1", "Rai 1", 0xFF2D6BD9), rai("rai2", "Rai 2", 0xFF1E9BD1),
            rai("rai3", "Rai 3", 0xFF2E9A5E), rai("rai4", "Rai 4", 0xFFD1433A),
            rai("rai5", "Rai 5", 0xFF8A4FD1), rai("raimovie", "Rai Movie", 0xFFB8862A),
            rai("raipremium", "Rai Premium", 0xFFC24C84), rai("raigulp", "Rai Gulp", 0xFF3BAF8C),
            rai("raiyoyo", "Rai YoYo", 0xFFE0842E), rai("raistoria", "Rai Storia", 0xFF7B6A4F),
            rai("raiscuola", "Rai Scuola", 0xFF4F7FB0),
            new Entry(G_RAI, "raiplay", "RaiPlay", RAIPLAY, null, 0xFF1E5BD6),
            new Entry(G_MEDIASET, "infinity", "Mediaset Infinity", INFINITY, null, 0xFF6A2BD6),
            new Entry(G_OTHER, "plex", "Plex", "com.plexapp.android", null, 0xFFE5A00D),
            new Entry(G_OTHER, "pluto", "Pluto TV", "tv.pluto.android", null, 0xFF2B2B2B),
            new Entry(G_OTHER, "discovery", "discovery+", "com.discovery.discoplus", null, 0xFF1F4FD8),
            new Entry(G_OTHER, "now", "NOW", "com.nowtv.it", null, 0xFF0AA39A),
            new Entry(G_OTHER, "dazn", "DAZN", "com.dazn", null, 0xFF1A1A1A),
    };

    private static Entry rai(String id, String name, int accent) {
        return new Entry(G_RAI, id, name, RAIPLAY, "https://www.raiplay.it/dirette/" + id, accent);
    }

    private static final int COLUMNS = 5;
    private static final int MARGIN_DP = 48;
    private static final int GAP_DP = 18;

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

    // ---------------------------------------------------------------- the page

    static View build(final Context c, final SettingsPanel.Host host) {
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int margin = AppModel.dp(c, MARGIN_DP);
        int gap = AppModel.dp(c, GAP_DP);
        int cardW = (screenW - 2 * margin - (COLUMNS - 1) * gap) / COLUMNS;
        int cardH = cardW * 9 / 16;

        FrameLayout root = new FrameLayout(c);
        root.setClickable(true);
        root.setBackgroundColor(0xEB06101A);

        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(margin, AppModel.dp(c, 34), margin, 0);

        TextView title = new TextView(c);
        title.setText("Canali TV");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
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

        List<Entry> list = available(c);
        String group = null;
        LinearLayout row = null;
        int inRow = 0;
        for (int i = 0; i < list.size(); i++) {
            final Entry e = list.get(i);
            if (!e.group.equals(group)) {
                group = e.group;
                TextView h = new TextView(c);
                h.setText(group.toUpperCase());
                h.setTextColor(0xFF8FB0D8);
                h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                h.setLetterSpacing(0.14f);
                h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = AppModel.dp(c, column.getChildCount() == 0 ? 8 : 26);
                hlp.bottomMargin = AppModel.dp(c, 12);
                column.addView(h, hlp);
                row = null;
                inRow = 0;
            }
            if (row == null || inRow == COLUMNS) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (inRow == COLUMNS) rlp.topMargin = gap;
                column.addView(row, rlp);
                inRow = 0;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
            if (inRow > 0) lp.leftMargin = gap;
            row.addView(card(c, e, cardW, cardH), lp);
            inRow++;
        }

        TextView note = new TextView(c);
        note.setText("Logo ufficiali: copia i PNG (rai1.png, raimovie.png...) in "
                + "Android/data/com.homestead.launcher/files/logos/. Mediaset Infinity non "
                + "apre un canale preciso: si apre sulla sua home, dove c'e' \"Ora in onda\".");
        note.setTextColor(0xFF74889C);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = AppModel.dp(c, 30);
        column.addView(note, nlp);

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** One focusable card with the channel's logo; it lifts and gets a white rim on focus. */
    private static View card(final Context c, final Entry e, int w, int h) {
        final FrameLayout cell = new FrameLayout(c);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);
        cell.setClipChildren(true);
        final float radius = AppModel.dp(c, 10);

        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius);
        base.setColor(0xFF111B26);
        cell.setBackground(base);

        ImageView logo = new ImageView(c);
        logo.setScaleType(ImageView.ScaleType.FIT_XY);
        logo.setImageBitmap(logoBitmap(c, e, w, h));
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
                v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
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
        int top = blend(e.accent, 0xFF0B1620, 0.55f);
        int bottom = blend(e.accent, 0xFF050A10, 0.82f);
        bg.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, bg);

        Bitmap own = userLogo(c, e, w, h);
        if (own != null) {
            float s = Math.min(w * 0.82f / own.getWidth(), h * 0.62f / own.getHeight());
            float lw = own.getWidth() * s, lh = own.getHeight() * s;
            cv.drawBitmap(own, null, new RectF((w - lw) / 2f, (h - lh) / 2f,
                    (w + lw) / 2f, (h + lh) / 2f), new Paint(Paint.FILTER_BITMAP_FLAG));
            return out;
        }
        if (e.uri == null) {
            // An app with no channel of its own: its banner fills the card.
            Drawable banner = appBanner(c, e.pkg);
            if (banner != null) {
                banner.setBounds(0, 0, w, h);
                banner.draw(cv);
                return out;
            }
        }
        wordmark(cv, e, w, h);
        Drawable icon = appIcon(c, e.pkg);
        if (icon != null) {
            int s = Math.round(h * 0.26f);
            int m = Math.round(h * 0.07f);
            icon.setBounds(w - s - m, h - s - m, w - m, h - m);
            icon.draw(cv);
        }
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

    /** "Rai" in white with the rest of the name in a rounded block of the channel's colour. */
    private static void wordmark(Canvas cv, Entry e, int w, int h) {
        String name = e.name;
        String lead = name;
        String tail = null;
        if (name.startsWith("Rai ") && name.length() > 4) {
            lead = "Rai";
            tail = name.substring(4);
        }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        tp.setColor(Color.WHITE);
        float size = h * 0.34f;
        tp.setTextSize(size);
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setTypeface(tp.getTypeface());
        pp.setTextSize(size * 0.72f);
        pp.setColor(Color.WHITE);

        float padX = size * 0.34f, padY = size * 0.16f, gapX = size * 0.22f;
        float leadW = tp.measureText(lead);
        float tailW = tail == null ? 0 : pp.measureText(tail) + 2 * padX;
        float total = leadW + (tail == null ? 0 : gapX + tailW);
        float max = w * 0.84f;
        if (total > max) {
            float k = max / total;
            tp.setTextSize(size * k);
            pp.setTextSize(size * 0.72f * k);
            padX *= k; padY *= k; gapX *= k;
            leadW = tp.measureText(lead);
            tailW = tail == null ? 0 : pp.measureText(tail) + 2 * padX;
            total = leadW + (tail == null ? 0 : gapX + tailW);
        }
        float x = (w - total) / 2f;
        float base = h * 0.5f + tp.getTextSize() * 0.34f;
        cv.drawText(lead, x, base, tp);
        if (tail != null) {
            float bx = x + leadW + gapX;
            float bh = pp.getTextSize() + 2 * padY;
            float by = base - tp.getTextSize() * 0.74f + (tp.getTextSize() * 0.74f - bh) / 2f
                    + bh * 0.08f;
            Paint blk = new Paint(Paint.ANTI_ALIAS_FLAG);
            blk.setColor(e.accent);
            cv.drawRoundRect(new RectF(bx, by, bx + tailW, by + bh), bh * 0.22f, bh * 0.22f, blk);
            cv.drawText(tail, bx + padX, by + padY + pp.getTextSize() * 0.82f, pp);
        }
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
