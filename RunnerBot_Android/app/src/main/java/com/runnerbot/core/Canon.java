package com.runnerbot.core;

/**
 * Превращает кадр экрана в «канонический» набор масок 345x285 (как to_canonical + HSV на ПК).
 * Считаем только нужные строки: луч дрона (4..153) и полоса препятствий (232..280).
 */
public final class Canon {
    public static final int W = 345, H = 285;
    public static final int F_ACC = 1;   // яркий фиолетовый (ленты, кончики шипов)
    public static final int F_SOFT = 2;  // мягкий фиолетовый (луч дрона)

    public final byte[] flags = new byte[W * H];

    private static boolean inRows(int cy) {
        return (cy >= Brain.BEAM_Y0 && cy < Brain.BEAM_Y1) || (cy >= Brain.TIP_Y0 && cy < Brain.BAND_Y1);
    }

    public void fill(Frame f, Geometry geo) {
        final double s = geo.scale;
        final double step = 2.0 * s;                  // размер канонического пикселя в пикселях кадра
        final double y0 = Math.round(geo.gy - Brain.CROP_UP * s);
        final int n = Math.max(2, Math.min(3, (int) Math.ceil(step)));
        final int fw = f.width(), fh = f.height();
        for (int cy = 0; cy < H; cy++) {
            if (!inRows(cy)) continue;
            for (int cx = 0; cx < W; cx++) {
                int sr = 0, sg = 0, sb = 0, cnt = 0;
                for (int j = 0; j < n; j++) {
                    int py = (int) Math.floor(y0 + (cy + (j + 0.5) / n) * step);
                    if (py < 0 || py >= fh) continue;
                    for (int i = 0; i < n; i++) {
                        int px = (int) Math.floor(geo.xl + (cx + (i + 0.5) / n) * step);
                        if (px < 0 || px >= fw) continue;
                        int c = f.rgb(px, py);
                        sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; cnt++;
                    }
                }
                byte fl = 0;
                if (cnt > 0) {
                    fl = classify(sr / cnt, sg / cnt, sb / cnt);
                }
                flags[cy * W + cx] = fl;
            }
        }
    }

    /** RGB -> флаги по порогам OpenCV-HSV (H 0..179, S,V 0..255). */
    static byte classify(int r, int g, int b) {
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        int v = mx;
        if (v <= 70) return 0;
        int d = mx - mn;
        if (d == 0) return 0;
        int s = (int) Math.round(255.0 * d / mx);
        if (s <= 70) return 0;
        double h;
        if (mx == r) h = 60.0 * (g - b) / d;
        else if (mx == g) h = 120.0 + 60.0 * (b - r) / d;
        else h = 240.0 + 60.0 * (r - g) / d;
        if (h < 0) h += 360.0;
        int hc = (int) Math.round(h / 2.0);
        if (!(hc > 120 && hc < 150)) return 0;
        byte fl = F_SOFT;
        if (s > 90 && v > 150) fl |= F_ACC;
        return fl;
    }
}
