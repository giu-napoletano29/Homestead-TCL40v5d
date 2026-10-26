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
        if (PHOTO_ID.equals(id)) return PHOTO_LOOK;
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].id.equals(id)) return PRESETS[i];
        }
        return PRESETS[0];
    }

    static boolean photoChosen(Context c) { return PHOTO_ID.equals(currentId(c)); }

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
        if (key == null || key.equals(photoKey) || loading) return;
        loading = true;
        final Context app = c.getApplicationContext();
        final DisplayMetrics dm = c.getResources().getDisplayMetrics();
        new Thread(new Runnable() {
            public void run() {
                final Bitmap b = decode(f, dm.widthPixels, dm.heightPixels);
                MAIN.post(new Runnable() {
                    public void run() {
                        loading = false;
                        // The user moved on while it decoded: do not keep it.
                        if (!photoChosen(app)) return;
                        photo = b;
                        photoKey = key;
                        Log.i(TAG, "background photo " + (b == null ? "could not be read"
                                : "loaded at " + b.getWidth() + "x" + b.getHeight()));
                        if (done != null) done.run();
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

    /** The photo under a black layer at the chosen dim. */
    static Drawable photoDrawable(Context c, Bitmap b) {
        Drawable img = new BitmapDrawable(c.getResources(), b);
        int a = dimAlpha(c);
        if (a == 0) return img;
        return new LayerDrawable(new Drawable[]{ img, new ColorDrawable(a << 24) });
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
