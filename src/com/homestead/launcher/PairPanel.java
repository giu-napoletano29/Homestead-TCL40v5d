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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The screen that pairs a phone with the remote: a QR code that holds the TV's address and a
 * code, and the same code in digits for a phone that cannot scan. The code changes every
 * minute and works once; pairing is open only while this screen is, so nobody can pair
 * without being at the TV (RemoteServer). The screen closes itself when a phone has paired.
 *
 * It does work only while it is on screen: a one-second timer for the countdown, stopped when
 * the screen goes.
 */
final class PairPanel {

    private static final long TICK_MS = 1000L;
    private static final long CODE_S = 60L;

    private PairPanel() { }

    static View build(final Context c, final SettingsPanel.Host host) {
        final Handler handler = new Handler(Looper.getMainLooper());

        FrameLayout root = new FrameLayout(c);
        root.setClickable(true);
        root.setFocusable(false);
        root.setBackground(ChannelsPanel.pageBackground(c));
        View veil = new View(c);
        veil.setBackgroundColor(0xB3000000);
        root.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        int margin = AppModel.dp(c, 72);
        page.setPadding(margin, AppModel.dp(c, 24), margin, AppModel.dp(c, 24));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final QrView qr = new QrView(c);
        int side = AppModel.dp(c, 300);
        page.addView(qr, new LinearLayout.LayoutParams(side, side));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = AppModel.dp(c, 56);
        page.addView(right, rlp);

        right.addView(text(c, c.getString(R.string.pair_title), 30, Color.WHITE, true));
        TextView scan = text(c, c.getString(R.string.pair_scan), 17, 0xFFD5DBE3, false);
        right.addView(scan, margins(c, 14, 0));

        final String base = RemoteServer.address();
        TextView or = text(c, base == null ? c.getString(R.string.pair_no_network)
                : c.getString(R.string.pair_or, base), 15, 0xFF9BB0C6, false);
        right.addView(or, margins(c, 10, 0));

        final TextView code = text(c, "", 52, Color.WHITE, true);
        code.setLetterSpacing(0.12f);
        right.addView(code, margins(c, 14, 0));

        final TextView countdown = text(c, "", 15, 0xFF9BB0C6, false);
        right.addView(countdown);

        TextView note = text(c, c.getString(R.string.pair_note), 14, 0xFF9BB0C6, false);
        note.setLineSpacing(AppModel.dp(c, 2), 1f);
        right.addView(note, margins(c, 18, 0));

        final TextView close = text(c, c.getString(R.string.pair_close), 17, Color.WHITE, true);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setFocusableInTouchMode(true);
        close.setClickable(true);
        close.setPadding(AppModel.dp(c, 28), AppModel.dp(c, 12), AppModel.dp(c, 28), AppModel.dp(c, 12));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AppModel.dp(c, 8));
        bg.setColor(0x66FFFFFF);
        close.setBackground(bg);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { host.closePanel(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = AppModel.dp(c, 24);
        right.addView(close, clp);

        // The state of the screen: the code on show, and when it changes.
        final long[] changeAt = new long[1];
        final Runnable show = new Runnable() {
            public void run() {
                String url = RemoteServer.pairUrl();
                qr.setText(url);
                String digits = RemoteServer.pairCode();
                code.setText(digits == null ? "" : digits.substring(0, 3) + " " + digits.substring(3));
            }
        };
        final Runnable tick = new Runnable() {
            public void run() {
                long left = (changeAt[0] - SystemClock.elapsedRealtime() + 999L) / 1000L;
                if (left <= 0) {
                    RemoteServer.pairRotate();
                    changeAt[0] = SystemClock.elapsedRealtime() + CODE_S * 1000L;
                    left = CODE_S;
                    show.run();
                }
                countdown.setText(c.getString(R.string.pair_changes, (int) left));
                handler.postDelayed(this, TICK_MS);
            }
        };
        final Runnable paired = new Runnable() {
            public void run() {
                handler.removeCallbacks(tick);
                code.setText("");
                qr.setText(null);
                countdown.setText(c.getString(R.string.pair_done));
                handler.postDelayed(new Runnable() {
                    public void run() { host.closePanel(); }
                }, 1500L);
            }
        };

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {
                RemoteServer.pairStart();
                RemoteServer.setPairedListener(paired);
                changeAt[0] = SystemClock.elapsedRealtime() + CODE_S * 1000L;
                show.run();
                handler.removeCallbacks(tick);
                handler.post(tick);
            }
            public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacksAndMessages(null);
                RemoteServer.pairStop();
            }
        });
        return root;
    }

    private static LinearLayout.LayoutParams margins(Context c, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AppModel.dp(c, top);
        lp.bottomMargin = AppModel.dp(c, bottom);
        return lp;
    }

    private static TextView text(Context c, String s, int sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Fonts.get(c, medium ? Fonts.MEDIUM : Fonts.REGULAR));
        return t;
    }

    /** A QR code on a white card, with the quiet zone round it that scanners need. */
    private static final class QrView extends View {
        private boolean[][] modules;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        QrView(Context c) { super(c); }

        void setText(String text) {
            modules = null;
            if (text != null) {
                try { modules = QrCode.encode(text); } catch (IllegalArgumentException ignored) { }
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            paint.setColor(modules == null ? 0x33FFFFFF : Color.WHITE);
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, w * 0.04f, w * 0.04f, paint);
            if (modules == null) return;
            int n = modules.length;
            int quiet = 4;
            // Whole pixels per module, so the edges stay sharp.
            int cell = (int) Math.floor(Math.min(w, h) / (n + 2 * quiet));
            float x0 = (w - cell * n) / 2f, y0 = (h - cell * n) / 2f;
            paint.setColor(Color.BLACK);
            paint.setAntiAlias(false);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (modules[y][x]) {
                        cv.drawRect(x0 + x * cell, y0 + y * cell, x0 + (x + 1) * cell,
                                y0 + (y + 1) * cell, paint);
                    }
                }
            }
            paint.setAntiAlias(true);
        }
    }
}
