package com.runnerbot.core;

/** Положение игры на экране: левый/правый край линии земли и её строка (в пикселях кадра). */
public final class Geometry {
    public final int xl, xr, gy;
    public final double scale; // пикселей кадра на 1 «исходный» px игры (ширина поля = REF_W)

    public Geometry(int xl, int xr, int gy) {
        this.xl = xl; this.xr = xr; this.gy = gy;
        this.scale = (xr - xl) / Brain.REF_W;
    }

    @Override public String toString() {
        return "xl=" + xl + " xr=" + xr + " ground=" + gy + " scale=" + String.format(java.util.Locale.US, "%.3f", scale);
    }

    // ------------------------------------------------------------------
    // Поиск линии земли (порт auto_region из версии для ПК)
    // ------------------------------------------------------------------
    private static int gray(int c) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int[][] cache;
    private static int cacheW;

    /** Один кадр -> геометрия или null. Работает с шагом по x = 1 (нужны точные края). */
    public static Geometry detect(Frame f) {
        final int H = f.height(), W = f.width();
        // серый кадр целиком
        if (cache == null || cache.length != H || cacheW != W) { cache = new int[H][]; cacheW = W; }
        int[][] g = cache;
        int y0 = (int) (H * 0.3) - 6;
        if (y0 < 0) y0 = 0;
        for (int y = y0; y < H; y++) {
            int[] row = g[y] != null ? g[y] : (g[y] = new int[W]);
            for (int x = 0; x < W; x++) row[x] = gray(f.rgb(x, y));
            g[y] = row;
        }
        int minSpan = Math.max(200, (int) (W * 0.1));
        double bestScore = -1; int bY = 0, bL = 0, bR = 0;
        boolean[] m = new boolean[W];
        for (int y = (int) (H * 0.3); y < H - 12; y++) {
            if (y - 25 < y0 || y + 5 >= H) continue;
            int[] row = g[y], upr = g[y - 5], dnr = g[y + 5];
            // подсчёт маски
            int cnt = 0;
            java.util.Arrays.fill(m, false);
            for (int x = 1; x < W; x++) {
                int v = row[x];
                boolean ok = (v - upr[x] > 15) && (v - dnr[x] > 8) && v > 30 && v < 200
                        && Math.abs(v - row[x - 1]) < 8;
                m[x] = ok;
                if (ok) cnt++;
            }
            if (cnt < minSpan * 0.5) continue;
            // группы с разрывом > 160
            int gs = -1, last = -1, gc = 0;
            for (int x = 1; x <= W; x++) {
                boolean on = x < W && m[x];
                if (on) {
                    if (gs >= 0 && x - last > 160) {
                        // закрыть группу
                        double sc = eval(g, y, gs, last, gc, W, minSpan);
                        if (sc > bestScore) { bestScore = sc; bY = y; bL = gs; bR = last; }
                        gs = -1; gc = 0;
                    }
                    if (gs < 0) gs = x;
                    last = x; gc++;
                }
            }
            if (gs >= 0) {
                double sc = eval(g, y, gs, last, gc, W, minSpan);
                if (sc > bestScore) { bestScore = sc; bY = y; bL = gs; bR = last; }
            }
        }
        if (bestScore < 0) return null;
        return new Geometry(bL, bR, bY);
    }

    private static double eval(int[][] g, int y, int xl, int xr, int count, int W, int minSpan) {
        if (count < 30) return -1;
        int span = xr - xl;
        // на телефоне игра может занимать почти всю ширину экрана
        if (span < minSpan || span > W || (double) count / span < 0.5) return -1;
        double above = 0, below = 0; int na = 0, nb = 0;
        for (int yy = y - 25; yy < y - 8; yy++) for (int x = xl; x < xr; x += 2) { above += g[yy][x]; na++; }
        for (int yy = y + 22; yy < y + 42; yy++) {
            if (yy >= g.length || g[yy] == null) continue;
            for (int x = xl; x < xr; x += 2) { below += g[yy][x]; nb++; }
        }
        if (na == 0) return -1;
        above /= na;
        if (nb > 0) below /= nb; else below = 0;   // под линией нет места — условие пропускаем
        if (above < 70 && below < 55) return count;
        return -1;
    }

    /** Несколько снимков -> устойчивая геометрия (как locate на ПК). */
    public static Geometry consensus(java.util.List<Geometry> found) {
        if (found.size() < 3) return null;
        int[] ys = new int[found.size()];
        for (int i = 0; i < ys.length; i++) ys[i] = found.get(i).gy;
        java.util.Arrays.sort(ys);
        int ym = ys[ys.length / 2];
        int xl = Integer.MAX_VALUE, xr = 0, n = 0;
        for (Geometry r : found) {
            if (Math.abs(r.gy - ym) <= 2) { xl = Math.min(xl, r.xl); xr = Math.max(xr, r.xr); n++; }
        }
        if (n < 3) return null;
        return new Geometry(xl, xr, ym);
    }
}
