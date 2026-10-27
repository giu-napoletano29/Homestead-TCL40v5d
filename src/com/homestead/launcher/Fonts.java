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
import android.graphics.Typeface;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Manrope, the one custom typeface this app uses, in place of the system's sans-serif-thin/
 * light/medium/black - a single variable file (weight 200-800) kept as res/raw, not an
 * asset, so build.sh needs no change to package it.
 *
 * A raw resource cannot be handed to Typeface directly below Android 8.0 (API 26), and even
 * Typeface.Builder's font-variation weight axis is an API 26 feature - so the first call
 * copies the file once to this app's own files directory and loads a plain Typeface from
 * there on older Android (always at the font's default weight), or a Typeface.Builder at
 * the exact weight asked for from 26 up. Both the file copy and every weight built from it
 * are cached for the life of the process.
 */
final class Fonts {

    static final int THIN = 200, LIGHT = 300, REGULAR = 400, MEDIUM = 500, BOLD = 700, BLACK = 800;

    private static File file;
    private static Typeface base;
    private static final Map<Integer, Typeface> cache = new HashMap<Integer, Typeface>();

    private Fonts() { }

    /** Manrope at the given weight (THIN..BLACK); always the default weight before Android 8.0. */
    static synchronized Typeface get(Context c, int weight) {
        Typeface cached = cache.get(weight);
        if (cached != null) return cached;
        Typeface t = build(c, weight);
        cache.put(weight, t);
        return t;
    }

    private static Typeface build(Context c, int weight) {
        File f = fontFile(c);
        if (f == null) return Typeface.DEFAULT;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return new Typeface.Builder(f).setFontVariationSettings("'wght' " + weight).build();
            } catch (Throwable ignored) { }
        }
        return baseTypeface(f);
    }

    private static Typeface baseTypeface(File f) {
        if (base != null) return base;
        try { base = Typeface.createFromFile(f); } catch (Throwable t) { base = Typeface.DEFAULT; }
        return base;
    }

    /** Copies res/raw/manrope.ttf to this app's files dir once; null if that ever fails. */
    private static File fontFile(Context c) {
        if (file != null) return file;
        File out = new File(c.getFilesDir(), "manrope.ttf");
        if (!out.exists() || out.length() == 0) {
            InputStream in = null;
            OutputStream os = null;
            try {
                in = c.getResources().openRawResource(R.raw.manrope);
                os = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } catch (Throwable t) {
                return null;
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
                try { if (os != null) os.close(); } catch (Throwable ignored) { }
            }
        }
        file = out;
        return file;
    }
}
