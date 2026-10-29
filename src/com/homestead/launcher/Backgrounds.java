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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;

/**
 * The home screen's background: a preset, or a photo copied into the app's own folder on
 * external storage, which needs no permission. Each look also sets the header and
 * focus-ring colours, since a dark ring vanishes on a dark background and white text on a
 * light one.
 */
final class Backgrounds {

    private static final String TAG = "Homestead";

    static final class Preset {
        /** What the setting saves. Never change or reuse one: users' choices are stored as it. */
        final String id;
        final String name;
        final int from, to;      // bottom-left to top-right; equal for a solid colour
        final int text;          // section headers and the clock
        final int ring;          // the focus ring around a tile
        /** A faint edge on every tile, so dark banners do not merge into a dark background. 0 for none. */
        final int outline;
        /** A soft glow in the middle fading to the edges, instead of the diagonal gradient. */
        final boolean radial;
        /**
         * Coloured glows over the base, for the graphic looks: each is {x, y, radius, colour},
         * the first three as fractions of the screen's width, height and longer side. Null for none.
         */
        final float[][] glows;
        /** Two soft diagonal light bands across the screen, on top of the glows. */
        final boolean bands;
        Preset(String id, String name, int from, int to, int text, int ring, int outline) {
            this(id, name, from, to, text, ring, outline, false);
        }
        Preset(String id, String name, int from, int to, int text, int ring, int outline,
                boolean radial) {
            this(id, name, from, to, text, ring, outline, radial, null, false);
        }
        Preset(String id, String name, int from, int to, int text, int ring, int outline,
                boolean radial, float[][] glows, boolean bands) {
            this.id = id; this.name = name; this.from = from; this.to = to; this.text = text; this.ring = ring;
            this.outline = outline;
            this.radial = radial;
            this.glows = glows;
            this.bands = bands;
        }

        /**
         * The look's own colour, opaque: the main glow of a graphic one, else the lit end of the
         * gradient. The home screen tints its cards and buttons with it, so they belong to it.
         */
        int tint() {
            if (glows != null) return 0xFF000000 | ((int) glows[0][3] & 0x00FFFFFF);
            return 0xFF000000 | ((radial ? from : to) & 0x00FFFFFF);
        }
    }

    private static final int EDGE = 0x3DFFFFFF;

    /**
     * In the order the Settings page lists them. Index 0 is the original look and the default.
     * The setting is saved by id, so presets can be added or moved without changing anyone's.
     */
    static final Preset[] PRESETS = {
            new Preset("sky",      "Sky",      0xFF6991C7, 0xFFA3BDED, 0xFFFFFFFF, 0xFF2C2C2C, 0),
            new Preset("midnight", "Midnight", 0xFF0B1F38, 0xFF1D4E86, 0xFFFFFFFF, 0xFFE6EEF8, EDGE),
            new Preset("charcoal", "Charcoal", 0xFF1A1B1F, 0xFF3A3C44, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
            new Preset("dusk",     "Dusk",     0xFF2A1B3D, 0xFF6B3E7C, 0xFFFFFFFF, 0xFFF0E6F5, EDGE),
            new Preset("black",    "Black",    0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE),
            // Dark, with a faint glow in the middle rather than a flat black.
            new Preset("ember",    "Ember",    0xFF35101F, 0xFF000000, 0xFFFFFFFF, 0xFFF0E6EA, EDGE, true),
            new Preset("deepsea",  "Deep sea", 0xFF08303A, 0xFF000000, 0xFFFFFFFF, 0xFFE6F0F2, EDGE, true),
            // A flat dark navy, softer than black.
            new Preset("night",    "Night",    0xFF0E1726, 0xFF0E1726, 0xFFE8EEF6, 0xFFFFFFFF, EDGE),
            // The graphic ones: coloured light pooling on a dark base, with soft diagonal bands.
            new Preset("aurora",   "Aurora",   0xFF04121C, 0xFF0A0620, 0xFFFFFFFF, 0xFFE9F7F4, EDGE, false,
                    new float[][]{
                            { 0.10f, 0.95f, 0.62f, 0xCC10B5A6 },
                            { 0.85f, 0.10f, 0.60f, 0xCC6A3DE0 },
                            { 0.55f, 0.60f, 0.38f, 0x6628C76F } }, true),
            new Preset("synth",    "Synthwave", 0xFF12061F, 0xFF05030F, 0xFFFFFFFF, 0xFFFBEAF6, EDGE, false,
                    new float[][]{
                            { 0.90f, 0.85f, 0.62f, 0xCCE0309F },
                            { 0.05f, 0.15f, 0.55f, 0xCC2B6BFF },
                            { 0.50f, 1.05f, 0.45f, 0x99FF7A2F } }, true),
            new Preset("lava",     "Lava",     0xFF160503, 0xFF050202, 0xFFFFFFFF, 0xFFFFF0E4, EDGE, false,
                    new float[][]{
                            { 0.12f, 0.90f, 0.62f, 0xCCE8451B },
                            { 0.80f, 0.30f, 0.50f, 0x99B3122E },
                            { 0.50f, 0.00f, 0.40f, 0x55FF9A3C } }, true),
            new Preset("ocean",    "Ocean",    0xFF03101F, 0xFF020812, 0xFFFFFFFF, 0xFFE6F2FA, EDGE, false,
                    new float[][]{
                            { 0.12f, 0.90f, 0.62f, 0xCC1E6FD9 },
                            { 0.88f, 0.15f, 0.55f, 0xB300B8D4 },
                            { 0.50f, 0.55f, 0.35f, 0x5518A99B } }, true),
            new Preset("forest",   "Forest",   0xFF04140D, 0xFF020A06, 0xFFFFFFFF, 0xFFE8F6EC, EDGE, false,
                    new float[][]{
                            { 0.10f, 0.92f, 0.60f, 0xCC12A05A },
                            { 0.85f, 0.20f, 0.45f, 0x8099C93A },
                            { 0.55f, 0.60f, 0.35f, 0x5510867A } }, true),
            new Preset("sunrise",  "Sunrise",  0xFF1A0A14, 0xFF08040A, 0xFFFFFFFF, 0xFFFFF0E6, EDGE, false,
                    new float[][]{
                            { 0.90f, 0.92f, 0.62f, 0xCCF5762C },
                            { 0.10f, 0.20f, 0.55f, 0xCCE63E86 },
                            { 0.50f, 1.05f, 0.40f, 0x66FFC24A } }, true),
            new Preset("orchid",   "Orchid",   0xFF14061C, 0xFF07030F, 0xFFFFFFFF, 0xFFF7E9FA, EDGE, false,
                    new float[][]{
                            { 0.15f, 0.90f, 0.60f, 0xCCC02AD6 },
                            { 0.85f, 0.12f, 0.60f, 0xCC5B3AF0 },
                            { 0.55f, 0.55f, 0.35f, 0x66FF6FB5 } }, true),
            new Preset("glacier",  "Glacier",  0xFF061523, 0xFF030A12, 0xFFFFFFFF, 0xFFEAF6FB, EDGE, false,
                    new float[][]{
                            { 0.10f, 0.20f, 0.55f, 0xB38FD8F5 },
                            { 0.88f, 0.90f, 0.60f, 0xCC3B7DE0 },
                            { 0.50f, 0.60f, 0.35f, 0x6650E0C8 } }, true),
            new Preset("crimson",  "Crimson",  0xFF14040A, 0xFF060205, 0xFFFFFFFF, 0xFFFBE8EA, EDGE, false,
                    new float[][]{
                            { 0.12f, 0.92f, 0.60f, 0xCCD62839 },
                            { 0.85f, 0.18f, 0.55f, 0xCC7A1F5C },
                            { 0.50f, 0.00f, 0.40f, 0x55FF8A3C } }, true),
    };

    /** The setting value for the photo. */
    static final String PHOTO_ID = "photo";
    /** The photo's text and ring colours, and its look until the photo has loaded. */
    static final Preset PHOTO_LOOK =
            new Preset(PHOTO_ID, "Photo", 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFE6E6E6, EDGE);

    private static final String[] FILES = { "background.jpg", "background.jpeg", "background.png" };
    /** Bigger files are ignored rather than decoded. */
    private static final long MAX_FILE = 25L * 1024L * 1024L;

    /** Alpha of the black layer over the photo, for Off, Light and Dark. */
    private static final int[] DIM = { 0x00, 0x59, 0x99 };
    static final String[] DIM_NAMES = { "Off", "Light", "Dark" };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Main thread only. One copy, shared by the activity's grid and the overlay's.
    private static Bitmap photo;
    private static String photoKey;
    private static boolean loading;
    /** Grids that asked for the photo while it was already decoding; main thread only. */
    private static final java.util.List<Runnable> WAITING = new java.util.ArrayList<Runnable>();

    private Backgrounds() { }

    /**
     * Up to 1.6.4 the setting was a position in this list, the presets then the photo.
     * Fixed for good: it is how a value saved then is read, whatever PRESETS holds now.
     */
    private static final String[] BY_OLD_POSITION =
            { "sky", "midnight", "charcoal", "dusk", "black", PHOTO_ID };

    /** The chosen look's id. A choice from before ids is read through its old position. */
    static String currentId(Context c) {
        String id = Prefs.backgroundId(c);
        if (id != null) return id;
        int i = Prefs.backgroundPosition(c);
        // Out of range showed the default then too.
        return (i >= 0 && i < BY_OLD_POSITION.length) ? BY_OLD_POSITION[i] : PRESETS[0].id;
    }

    static Preset current(Context c) {
        String id = currentId(c);
        if (PHOTO_ID.equals(id) || artFor(id) != null) return PHOTO_LOOK;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].id.equals(id)) return PRESETS[i];
        }
        return PRESETS[0];
    }

    /** A public-domain painting bundled in res/raw, offered beside the presets. */
    static final class Art {
        final String id, name;
        final int res;
        Art(String id, String name, int res) { this.id = id; this.name = name; this.res = res; }
    }

    /** Shown as photos: the home screen decodes, dims and scrims them the same way. */
    static final Art[] ARTS = {
            new Art("art_starry",  "The Starry Night, Van Gogh",   R.raw.art_starry),
            new Art("art_wave",    "The Great Wave, Hokusai",      R.raw.art_wave),
            new Art("art_kiss",    "The Kiss, Klimt",              R.raw.art_kiss),
            new Art("art_lilies",  "Water Lilies, Monet",          R.raw.art_lilies),
    };

    static Art artFor(String id) {
        for (int i = 0; i < ARTS.length; i++) {
            if (ARTS[i].id.equals(id)) return ARTS[i];
        }
        return null;
    }

    /** The painting's JPEG copied out of the APK once, so it loads like any photo file. */
    private static File artFile(Context c, Art a) {
        File f = new File(c.getFilesDir(), a.id + ".jpg");
        if (f.isFile() && f.length() > 0) return f;
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = c.getResources().openRawResource(a.res);
            out = new java.io.FileOutputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return f;
        } catch (Throwable t) {
            Log.w(TAG, "painting could not be copied: " + t);
            f.delete();
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** A small decode of a painting, for its row in the Background page. */
    static Bitmap artThumb(Context c, Art a) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 16;
            return BitmapFactory.decodeResource(c.getResources(), a.res, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True for the user's photo and for a bundled painting: both are shown as a picture. */
    static boolean photoChosen(Context c) {
        String id = currentId(c);
        return PHOTO_ID.equals(id) || artFor(id) != null;
    }

    /**
     * Save a choice. Its old position is saved too where it had one, so going back to a
     * version from before ids keeps it.
     */
    static void choose(Context c, String id) {
        int old = -1;
        for (int i = 0; i < BY_OLD_POSITION.length; i++) {
            if (BY_OLD_POSITION[i].equals(id)) old = i;
        }
        Prefs.setBackground(c, id, old);
    }

    /** The value shown on the Settings row. */
    static String label(Context c) {
        Art art = artFor(currentId(c));
        if (art != null) return art.name;
        if (!photoChosen(c)) return current(c).name;
        return photoFile(c) != null ? "Photo" : "Photo (no file yet)";
    }

    static int dimAlpha(Context c) {
        int d = Prefs.photoDim(c);
        return DIM[(d < 0 || d >= DIM.length) ? 1 : d];
    }

    static String dimLabel(Context c) {
        int d = Prefs.photoDim(c);
        return DIM_NAMES[(d < 0 || d >= DIM.length) ? 1 : d];
    }

    /**
     * The photo, or null: one received through BackgroundProvider (any Fire OS), else one
     * pushed into the app's folder on external storage (Fire OS 7 only).
     */
    static File photoFile(Context c) {
        try {
            Art art = artFor(currentId(c));
            if (art != null) return artFile(c, art);
            File own = BackgroundProvider.photoFile(c);
            if (own.isFile() && own.length() > 0) return own;
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return null;
            for (int i = 0; i < FILES.length; i++) {
                File f = new File(dir, FILES[i]);
                if (f.isFile() && f.length() > 0) return f;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Identifies one version of the file, so a replaced photo is noticed. */
    static String keyOf(File f) {
        return (f == null) ? null : f.getName() + "|" + f.length() + "|" + f.lastModified();
    }

    /** The decoded photo if it matches this file version, else null. Main thread. */
    static Bitmap photoFor(String key) {
        return (key != null && key.equals(photoKey)) ? photo : null;
    }

    /** Whatever photo was last decoded, shown while a replaced file loads. Main thread. */
    static Bitmap lastPhoto() { return photo; }

    /**
     * Let go of the decoded photo (about 8 MB at 1080p) once it is not shown: another look
     * was chosen or the file was removed. Not recycled, since the other grid may still be
     * drawing it until its next repaint. Main thread.
     */
    static void drop() {
        photo = null;
        photoKey = null;
    }

    /**
     * Decode the photo on a worker and run done on the main thread when it lands. Does
     * nothing if that version is already decoded or a decode is running. Main thread.
     */
    static void load(Context c, final File f, final Runnable done) {
        final String key = keyOf(f);
        if (key == null || key.equals(photoKey)) return;
        // The activity's grid and the overlay's both ask when the app starts. The second one
        // must not be dropped: it waits for the decode already running and is told when it
        // lands, or it stays on the plain backdrop (black) until something repaints it.
        if (loading) {
            if (done != null) WAITING.add(done);
            return;
        }
        loading = true;
        final Context app = c.getApplicationContext();
        final DisplayMetrics dm = c.getResources().getDisplayMetrics();
        new Thread(new Runnable() {
            public void run() {
                final Bitmap b = decode(f, dm.widthPixels, dm.heightPixels);
                MAIN.post(new Runnable() {
                    public void run() {
                        loading = false;
                        java.util.List<Runnable> also = new java.util.ArrayList<Runnable>(WAITING);
                        WAITING.clear();
                        // The user moved on while it decoded: do not keep it.
                        if (!photoChosen(app)) return;
                        photo = b;
                        photoKey = key;
                        Log.i(TAG, "background photo " + (b == null ? "could not be read"
                                : "loaded at " + b.getWidth() + "x" + b.getHeight()));
                        if (done != null) done.run();
                        for (int i = 0; i < also.size(); i++) also.get(i).run();
                    }
                });
            }
        }, "homestead-photo").start();
    }

    /** Scaled and centre-cropped to the screen, so nothing larger stays in memory. */
    private static Bitmap decode(File f, int dw, int dh) {
        try {
            if (f.length() > MAX_FILE) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dw && o.outHeight / (sample * 2) >= dh) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            float s = Math.max((float) dw / src.getWidth(), (float) dh / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((dw - src.getWidth() * s) / 2f, (dh - src.getHeight() * s) / 2f);
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "background photo failed: " + t);
            return null;
        }
    }

    /**
     * A picture that always fills the bounds it is given. A BitmapDrawable inside a
     * LayerDrawable is instead placed at its own size, aligned to the start edge, unless told
     * otherwise - so any mismatch (the bitmap's density, the layout direction, the bounds the
     * layer is given) leaves it shifted and short of the screen. This has no size of its own,
     * so the layer fills the area and the picture is stretched to it.
     */
    static Drawable fill(Bitmap b) {
        return new FillDrawable(b);
    }

    private static final class FillDrawable extends Drawable {
        private final Bitmap bmp;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        FillDrawable(Bitmap b) { bmp = b; }
        @Override public void draw(Canvas canvas) {
            if (bmp == null || bmp.isRecycled()) return;
            canvas.drawBitmap(bmp, null, getBounds(), paint);
        }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    // The wallpaper, drawn once into one opaque screen-sized bitmap. A preset or a photo with
    // its dim and its fade is two to six full-screen layers, redrawn on every frame the
    // screen moves; on a TV with a modest GPU (the TCL: about 15 ms of GPU a frame) that is
    // most of the budget. One opaque picture costs one pass. One slot, shared by the
    // activity's grid and the overlay's: main thread only.
    private static Bitmap bakedBmp;
    private static String bakedKey;

    private static String bakedKeyFor(Context c, String key) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return key + "|" + dm.widthPixels + "x" + dm.heightPixels;
    }

    /** The baked wallpaper for this key if it is the one held now, else null. */
    static Drawable bakedOrNull(Context c, String key) {
        if (bakedBmp == null || bakedBmp.isRecycled()
                || !bakedKeyFor(c, key).equals(bakedKey)) return null;
        return new FillDrawable(bakedBmp);
    }

    /** Draws src into the baked bitmap under this key and returns a drawable showing it. */
    static Drawable bake(Context c, String key, Drawable src) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        try {
            Bitmap b = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            cv.drawColor(0xFF000000);
            src.setBounds(0, 0, b.getWidth(), b.getHeight());
            src.draw(cv);
            b.setHasAlpha(false);
            bakedBmp = b;
            bakedKey = bakedKeyFor(c, key);
            return new FillDrawable(b);
        } catch (Throwable t) {
            Log.w(TAG, "wallpaper could not be baked: " + t);
            return src;
        }
    }

    /** The photo under a black layer at the chosen dim. */
    static Drawable photoDrawable(Context c, Bitmap b) {
        Drawable img = fill(b);
        int a = dimAlpha(c);
        if (a == 0) return img;
        return new LayerDrawable(new Drawable[]{ img, new ColorDrawable(a << 24) });
    }

    /**
     * A dark gradient over the lower part of the screen, clear at the top and almost opaque
     * by the bottom, so the home screen's tiles keep contrast whatever the look underneath -
     * a preset's own base colour, or (for a photo) one sampled from it. Tizen and VIDAA both
     * do this over their own hero art rather than dimming the whole screen evenly.
     */
    static Drawable withScrim(Context c, Drawable base, int darkColor) {
        return new LayerDrawable(new Drawable[]{ base, new Scrim(darkColor & 0x00FFFFFF) });
    }

    /**
     * The dark fade under the dock. Stops at fixed heights (a GradientDrawable only spaces
     * them evenly before API 29): the picture is untouched down to just before halfway, then
     * goes quickly to dark over the dock's own height and stays almost solid below it.
     */
    private static final class Scrim extends Drawable {
        private static final float[] AT = { 0f, 0.48f, 0.60f, 0.78f, 1f };
        private static final int[] ALPHA = { 0x00, 0x00, 0xC8, 0xF2, 0xFC };
        private final int rgb;
        private final Paint paint = new Paint();
        Scrim(int rgb) { this.rgb = rgb; }

        @Override protected void onBoundsChange(android.graphics.Rect r) {
            if (r.isEmpty()) return;
            int[] colors = new int[ALPHA.length];
            for (int i = 0; i < colors.length; i++) colors[i] = rgb | (ALPHA[i] << 24);
            paint.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.bottom, colors, AT,
                    android.graphics.Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas canvas) {
            if (paint.getShader() != null) canvas.drawRect(getBounds(), paint);
        }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * The background's main colour taken down to a dark shade: a photo's own dark tone, or a
     * preset's tint at 35%. What the side rail and the settings pages take their hue from.
     */
    static int tone(Context c) {
        if (photoChosen(c)) {
            Bitmap b = lastPhoto();
            return b != null ? scrimColorFor(b) : 0xFF000000;
        }
        int t = current(c).tint();
        int r = ((t >> 16) & 0xFF) * 35 / 100, g = ((t >> 8) & 0xFF) * 35 / 100,
                b = (t & 0xFF) * 35 / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * A soft, small copy of the picture for the idle clock's backdrop: shrunk to a tenth and
     * box-blurred, which is all a blur needs here (a real one needs API 31). Left small -
     * a BitmapDrawable stretches it over the screen, so it costs almost no memory. Null if
     * anything goes wrong; the caller then keeps its plain backdrop.
     */
    static Bitmap blurred(Bitmap src) {
        try {
            int w = Math.max(8, src.getWidth() / 10), h = Math.max(8, src.getHeight() / 10);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] a = new int[w * h];
            int[] b = new int[w * h];
            small.getPixels(a, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 2; pass++) {
                boxBlur(a, b, w, h, 5, true);
                boxBlur(b, a, w, h, 5, false);
            }
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            out.setPixels(a, 0, w, 0, 0, w, h);
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "idle backdrop blur failed: " + t);
            return null;
        }
    }

    /** One box-blur pass along rows (horiz) or columns, edges repeated. */
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w, div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[horiz ? l * w + Math.min(len - 1, Math.max(0, i))
                        : Math.min(len - 1, Math.max(0, i)) * w + l];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                int at = horiz ? l * w + i : i * w + l;
                out[at] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int ia = Math.min(len - 1, i + r + 1), is = Math.max(0, i - r);
                int add = in[horiz ? l * w + ia : ia * w + l];
                int sub = in[horiz ? l * w + is : is * w + l];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    /**
     * A dark tone sampled from the lower part of a photo, for withScrim() - coherent with
     * that photo rather than one fixed colour for every picture someone might choose.
     */
    static int scrimColorFor(Bitmap b) {
        if (b == null) return 0xFF14100C;
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return 0xFF14100C;
        int y0 = Math.max(0, (int) (h * 0.75f));
        long r = 0, g = 0, bl = 0;
        int n = 0;
        int stepX = Math.max(1, w / 24), stepY = Math.max(1, (h - y0) / 10);
        for (int y = y0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int px = b.getPixel(x, y);
                r += (px >> 16) & 0xFF;
                g += (px >> 8) & 0xFF;
                bl += px & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFF14100C;
        // Darkened well below the sampled average: a scrim reads as a scrim, not a filter.
        int rr = (int) (r / n * 0.38f), gg = (int) (g / n * 0.38f), bb = (int) (bl / n * 0.38f);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    /**
     * Create the app's folder on external storage, so a custom background can be copied
     * there with adb push. Android only creates it once something asks for it.
     */
    static void ensureFolder(Context c) {
        try { c.getExternalFilesDir(null); } catch (Throwable ignored) { }
    }

    static GradientDrawable drawable(Preset p) {
        if (p.glows != null) return new GlowDrawable(p);
        if (p.radial) {
            // The glow sits left of centre, behind the first tile; its reach follows the screen.
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{ p.from, p.to }) {
                @Override protected void onBoundsChange(android.graphics.Rect r) {
                    super.onBoundsChange(r);
                    setGradientRadius(Math.max(r.width(), r.height()) * 0.78f);
                }
            };
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientCenter(0.36f, 0.42f);
            return g;
        }
        return new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{ p.from, p.to });
    }

    /**
     * The base gradient with coloured glows and diagonal light bands drawn over it. The
     * shaders are made once when the size is known, so drawing it again costs a few fills.
     * It is a GradientDrawable so the Settings swatch can round its corners like the others.
     */
    private static final class GlowDrawable extends GradientDrawable {
        private final Preset p;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.RadialGradient[] glowShaders;
        private android.graphics.LinearGradient bandShader;

        GlowDrawable(Preset p) {
            super(GradientDrawable.Orientation.TL_BR, new int[]{ p.from, p.to });
            this.p = p;
        }

        @Override protected void onBoundsChange(android.graphics.Rect r) {
            super.onBoundsChange(r);
            if (r.isEmpty()) return;
            int n = p.glows.length;
            glowShaders = new android.graphics.RadialGradient[n];
            float side = Math.max(r.width(), r.height());
            for (int i = 0; i < n; i++) {
                float[] g = p.glows[i];
                int col = (int) g[3];
                glowShaders[i] = new android.graphics.RadialGradient(
                        r.left + g[0] * r.width(), r.top + g[1] * r.height(), g[2] * side,
                        col, col & 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
            }
            // A band runs from the lower left to the upper right; light in the middle, none at the edges.
            bandShader = new android.graphics.LinearGradient(
                    r.left, r.bottom, r.right, r.top,
                    new int[]{ 0x00FFFFFF, 0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF, 0x00FFFFFF,
                            0x0DFFFFFF, 0x00FFFFFF },
                    new float[]{ 0f, 0.22f, 0.30f, 0.40f, 0.60f, 0.68f, 0.80f },
                    android.graphics.Shader.TileMode.CLAMP);
        }

        @Override public void draw(Canvas canvas) {
            super.draw(canvas);
            if (glowShaders == null) return;
            android.graphics.Rect r = getBounds();
            for (int i = 0; i < glowShaders.length; i++) {
                paint.setShader(glowShaders[i]);
                canvas.drawRect(r, paint);
            }
            if (p.bands && bandShader != null) {
                paint.setShader(bandShader);
                canvas.drawRect(r, paint);
            }
            paint.setShader(null);
        }
    }
}
