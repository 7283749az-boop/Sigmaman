package com.runnerbot.core;

import java.util.ArrayList;
import java.util.List;

/** «Мозг» бота: точный порт Brain из bot.py (версия для ПК). */
public final class Brain {
    // ---- геометрия в каноническом масштабе ----
    public static final double REF_W = 690.0;
    public static final double CROP_UP = 560.0, CROP_DOWN = 10.0;
    public static final int BAND_Y0 = 257, BAND_Y1 = 281;
    public static final int TIP_Y0 = 232, TIP_Y1 = 257;
    public static final int BEAM_Y0 = 4, BEAM_Y1 = 154;
    public static final int X_MIN = 118;
    public static final double PLAYER_FRONT = 115;

    // ---- настройки ----
    public double shift = 0.0;
    public double tauNarrow = 0.510, tauWide = 0.505, tauSpike = 0.530;
    public double pairEarly = 0.025;
    public double lat = 0.050;
    public double hold = 0.24;
    public double air = 0.52;
    public double podRatio = 1.14;
    public double podHalf = 26 + 8;

    // ---- состояние ----
    public double v = 330.0;
    double airUntil = 0.0;
    double releaseAt = Double.NaN;
    final List<double[]> hist = new ArrayList<>();     // (t, x)
    List<double[]> podsPrev = new ArrayList<>();       // (t, cx, v or NaN)
    double tPrev = Double.NaN, dtEma = Double.NaN;
    final List<int[]> spikeSet = new ArrayList<>();

    // ---- результат последнего шага (для логов) ----
    public String lastKind = "";
    public int lastX0 = -1;
    public boolean lastBlocked = false;
    public List<int[]> lastObst = new ArrayList<>();
    public List<Double> lastPods = new ArrayList<>();

    /** Что делать. */
    public static final class Action {
        public final boolean pressAt;   // true: нажать в момент tFire
        public final double tFire;
        public final double hold;
        Action(boolean pressAt, double tFire, double hold) { this.pressAt = pressAt; this.tFire = tFire; this.hold = hold; }
    }

    public void reset() {
        v = 330.0; airUntil = 0; releaseAt = Double.NaN; hist.clear();
        podsPrev = new ArrayList<>(); tPrev = Double.NaN; dtEma = Double.NaN;
    }

    private static List<int[]> clusters(List<Integer> cols, int gap, int minw) {
        List<int[]> out = new ArrayList<>();
        for (int c : cols) {
            if (!out.isEmpty() && c - out.get(out.size() - 1)[1] <= gap) out.get(out.size() - 1)[1] = c;
            else out.add(new int[]{c, c});
        }
        List<int[]> r = new ArrayList<>();
        for (int[] o : out) if (o[1] - o[0] + 1 >= minw) r.add(o);
        return r;
    }

    private static List<Integer> colsOver(byte[] fl, int mask, int y0, int y1, int x0, int x1, int minSum) {
        List<Integer> cols = new ArrayList<>();
        for (int x = x0; x < x1; x++) {
            int s = 0;
            for (int y = y0; y < y1; y++) if ((fl[y * Canon.W + x] & mask) != 0) s++;
            if (s >= minSum) cols.add(x);
        }
        return cols;
    }

    private boolean inSpikeSet(int[] c) {
        for (int[] s : spikeSet) if (s[0] == c[0] && s[1] == c[1]) return true;
        return false;
    }

    private void analyse(byte[] fl, List<int[]> obst, List<Double> pods) {
        List<int[]> ob = new ArrayList<>();
        for (int[] c : clusters(colsOver(fl, Canon.F_ACC, BAND_Y0, BAND_Y1, X_MIN, Canon.W - 3, 3), 2, 2))
            if (c[0] > X_MIN + 2) ob.add(c);
        List<int[]> tips = new ArrayList<>();
        for (int[] c : clusters(colsOver(fl, Canon.F_ACC, TIP_Y0, TIP_Y1, X_MIN, Canon.W - 3, 2), 1, 2))
            if (c[1] - c[0] + 1 <= 8 && c[0] > X_MIN + 2) tips.add(c);
        spikeSet.clear();
        List<List<int[]>> groups = new ArrayList<>();
        List<int[]> cur = new ArrayList<>();
        for (int[] c : tips) {
            if (!cur.isEmpty() && c[0] - cur.get(cur.size() - 1)[1] <= 22) cur.add(c);
            else {
                if (cur.size() >= 2) groups.add(cur);
                cur = new ArrayList<>();
                cur.add(c);
            }
        }
        if (cur.size() >= 2) groups.add(cur);
        for (List<int[]> g : groups) {
            int a = g.get(0)[0], b = g.get(g.size() - 1)[1];
            List<int[]> keep = new ArrayList<>();
            for (int[] o : ob) if (!(a - 6 <= o[0] && o[0] <= b + 6)) keep.add(o);
            ob = keep;
            int[] sp = new int[]{a, b};
            ob.add(sp);
            spikeSet.add(sp);
        }
        ob.sort((p, q) -> p[0] != q[0] ? Integer.compare(p[0], q[0]) : Integer.compare(p[1], q[1]));
        obst.addAll(ob);
        for (int[] c : clusters(colsOver(fl, Canon.F_SOFT, BEAM_Y0, BEAM_Y1, 0, Canon.W, 45), 3, 2))
            if (c[1] - c[0] + 1 <= 12) pods.add((c[0] + c[1]) / 2.0);
    }

    private void trackSpeed(double t, List<int[]> obst) {
        if (obst.isEmpty()) { hist.clear(); return; }
        double x = obst.get(0)[0];
        if (!hist.isEmpty()) {
            double lx = hist.get(hist.size() - 1)[1];
            if (x > lx + 3 || lx - x > 80) hist.clear();
        }
        hist.add(new double[]{t, x});
        hist.removeIf(p -> t - p[0] > 0.2);
        if (hist.size() >= 2) {
            double[] a = hist.get(0), b = hist.get(hist.size() - 1);
            if (b[0] - a[0] >= 0.06) {
                double inst = (a[1] - b[1]) / (b[0] - a[0]);
                if (inst > 150 && inst < 700) v = 0.8 * v + 0.2 * inst;
            }
        }
    }

    private List<double[]> trackPods(double t, List<Double> pods) {
        List<double[]> nw = new ArrayList<>();
        for (double cx : pods) {
            double vel = Double.NaN;
            for (double[] p : podsPrev) {
                double dt = t - p[0];
                if (dt > 0.003 && p[1] - cx > 0 && p[1] - cx < 60) {
                    vel = (p[1] - cx) / dt;
                    if (!Double.isNaN(p[2]) && p[2] != 0) vel = 0.7 * p[2] + 0.3 * vel;
                    break;
                }
            }
            nw.add(new double[]{t, cx, vel});
        }
        podsPrev = nw;
        return nw;
    }

    private boolean podThreat(List<double[]> pods) {
        double lo = 0.03, hi = air - 0.02;
        double xa = PLAYER_FRONT - 57 - podHalf, xb = PLAYER_FRONT + podHalf;
        for (double[] p : pods) {
            double cx = p[1], vel = p[2];
            double vp = (!Double.isNaN(vel) && vel != 0 && vel > 150 && vel < 1100) ? vel : v * podRatio;
            if (cx - vp * lo >= xa && cx - vp * hi <= xb) return true;
        }
        return false;
    }

    private static final class Obj { int a, b, tips; boolean spike; String kind; double c; }

    /** Один шаг. Возвращает действие или null. (Отпускание кнопки делает жест нужной длины.) */
    public Action step(byte[] flags, double t) {
        List<int[]> obst = new ArrayList<>();
        List<Double> pods = new ArrayList<>();
        analyse(flags, obst, pods);
        trackSpeed(t, obst);
        List<double[]> podState = trackPods(t, pods);
        if (!Double.isNaN(tPrev)) {
            double dt = t - tPrev;
            if (dt > 0.003 && dt < 0.2) dtEma = Double.isNaN(dtEma) ? dt : 0.8 * dtEma + 0.2 * dt;
        }
        tPrev = t;
        lastObst = obst; lastPods = pods; lastBlocked = false; lastKind = ""; lastX0 = -1;

        if (!Double.isNaN(releaseAt) && t >= releaseAt) { releaseAt = Double.NaN; return null; }
        if (t < airUntil || !Double.isNaN(releaseAt)) return null;
        if (obst.isEmpty()) return null;

        List<Obj> objs = new ArrayList<>();
        for (int[] c : obst) {
            int wc = c[1] - c[0] + 1;
            Obj last = objs.isEmpty() ? null : objs.get(objs.size() - 1);
            if (last != null && last.tips > 0 && wc <= 8 && c[0] - last.b <= 22) {
                last.b = c[1]; last.tips++;
            } else {
                Obj o = new Obj();
                o.a = c[0]; o.b = c[1]; o.tips = wc <= 8 ? 1 : 0; o.spike = inSpikeSet(c);
                objs.add(o);
            }
        }
        for (Obj o : objs) {
            int w = o.b - o.a + 1;
            o.kind = (o.spike || o.tips >= 2) ? "spike" : (w >= 18 ? "wide" : "narrow");
            o.c = (o.a + o.b) / 2.0;
        }
        List<Obj> group = new ArrayList<>();
        group.add(objs.get(0));
        for (int i = 1; i < objs.size(); i++) {
            Obj o = objs.get(i);
            if (o.c - group.get(group.size() - 1).c <= Math.min(130.0, 0.30 * v)) group.add(o);
            else break;
        }
        Obj first = group.get(0);
        double gcenter = (first.c + group.get(group.size() - 1).c) / 2.0;
        double x = first.a + (gcenter - first.c);
        lastKind = first.kind + (group.size() > 1 ? "+" + (group.size() - 1) : "");
        lastX0 = first.a;
        double tau = first.kind.equals("spike") ? tauSpike : first.kind.equals("wide") ? tauWide : tauNarrow;
        double target = v * tau + shift;
        if (group.size() > 1) target -= v * pairEarly;
        double remaining = (x - v * lat - target) / v;
        double dtE = Double.isNaN(dtEma) ? 0.03 : dtEma;
        if (remaining <= dtE * 1.2 && first.a <= 258) {
            if (podThreat(podState)) { lastBlocked = true; return null; }
            double tFire = t + Math.max(0.0, remaining);
            airUntil = tFire + air;
            releaseAt = tFire + hold;
            return new Action(remaining > 0.003, tFire, hold);
        }
        return null;
    }
}
