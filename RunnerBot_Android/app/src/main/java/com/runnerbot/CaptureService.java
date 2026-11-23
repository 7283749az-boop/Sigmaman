package com.runnerbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import com.runnerbot.core.Brain;
import com.runnerbot.core.Canon;
import com.runnerbot.core.Geometry;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;

/**
 * Захватывает экран и крутит «мозг» бота. Режимы:
 *  IDLE (захват идёт, бот выключен) -> LOCATING (ищем землю) -> RUNNING (играем).
 */
public class CaptureService extends Service {
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    static final String PREFS = "runnerbot";

    static volatile CaptureService instance;

    private static final int IDLE = 0, LOCATING = 1, RUNNING = 2;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService firer;

    private int capW, capH;            // размер захвата
    private double toScreen = 1.0;     // экранные координаты = координаты кадра * toScreen

    private volatile int mode = IDLE;
    private final Brain brain = new Brain();
    private final Canon canon = new Canon();
    private Geometry geo;
    private final List<Geometry> found = new ArrayList<>();
    private int attempts;
    private long lastAttemptNs;
    private double glat = 0.03;        // задержка доставки жеста, сек
    private StringBuilder log = new StringBuilder();
    private long armedNs;
    private int jumps;
    private long frames;
    private double procEma;

    // ------------------------------------------------------------------ жизненный цикл
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra(EXTRA_CODE)) { stopSelf(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (projection != null) return START_NOT_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            toast("Не удалось начать захват экрана: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("rb-capture", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        handler = new Handler(thread.getLooper());
        firer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rb-fire");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(CaptureService.this::stopSelf); }
        }, handler);

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int rw = dm.widthPixels, rh = dm.heightPixels;
        double k = Math.min(1.0, 1080.0 / Math.min(rw, rh));   // короткая сторона не больше 1080
        capW = Math.max(2, (int) Math.round(rw * k));
        capH = Math.max(2, (int) Math.round(rh * k));
        toScreen = 1.0 / k;

        reader = ImageReader.newInstance(capW, capH, android.graphics.PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> onFrame(r), handler);
        display = projection.createVirtualDisplay("runnerbot", capW, capH, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        instance = this;
        BotAccessibilityService.refreshOverlay();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        mode = IDLE;
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        if (thread != null) thread.quitSafely();
        if (firer != null) firer.shutdownNow();
        BotAccessibilityService.refreshOverlay();
        super.onDestroy();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("rb", "Runner Bot", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "rb")
                .setContentTitle("Runner Bot")
                .setContentText("Захват экрана включён")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    // ------------------------------------------------------------------ управление
    boolean isRunningOrLocating() { return mode != IDLE; }

    /** Вызывается с главного потока (кнопка-оверлей). */
    void toggle() {
        handler.post(() -> {
            if (mode == IDLE) arm(); else disarm("остановлено кнопкой");
        });
    }

    private void arm() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        brain.reset();
        brain.shift = p.getFloat("shift", 0f);
        brain.lat = p.getFloat("lat", 0.05f);
        glat = p.getFloat("glat", 0.03f);
        found.clear();
        attempts = 0;
        lastAttemptNs = 0;
        geo = null;
        jumps = 0;
        frames = 0;
        procEma = 0;
        log = new StringBuilder();
        log.append(String.format(Locale.US, "Runner Bot лог. screen=%dx%d capture=%dx%d shift=%.1f lat=%.3f glat=%.3f%n",
                (int) Math.round(capW * toScreen), (int) Math.round(capH * toScreen), capW, capH, brain.shift, brain.lat, glat));
        armedNs = System.nanoTime();
        mode = LOCATING;
        status("Ищу игру на экране…");
    }

    private void disarm(String why) {
        if (mode == IDLE) return;
        mode = IDLE;
        log.append(String.format(Locale.US, "СТОП: %s. прыжков=%d, кадров=%d, средняя обработка кадра=%.1f мс%n",
                why, jumps, frames, procEma * 1000));
        final String text = log.toString();
        new Thread(() -> {
            boolean ok = Saver.saveText(this, text, "runnerbot_log.txt");
            toast(ok ? "Лог сохранён: Загрузки/RunnerBot" : "Не удалось сохранить лог");
        }).start();
        status("Бот выключен (" + why + ")");
    }

    // ------------------------------------------------------------------ кадры
    private void onFrame(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            int m = mode;
            if (m == IDLE) return;
            final double t = System.nanoTime() / 1e9;
            ImageFrame fr = new ImageFrame(img);
            if (m == LOCATING) { locate(img, fr, t); return; }
            long s0 = System.nanoTime();
            canon.fill(fr, geo);
            Brain.Action a = brain.step(canon.flags, t);
            double dt = (System.nanoTime() - s0) / 1e9;
            procEma = procEma == 0 ? dt : 0.95 * procEma + 0.05 * dt;
            frames++;
            if (a != null) {
                jumps++;
                log.append(String.format(Locale.US, "t=%.2f прыжок %s x0=%d v=%.0f через=%.3f%n",
                        t - armedNs / 1e9, brain.lastKind, brain.lastX0, brain.v, Math.max(0, a.tFire - t)));
                scheduleTap(a.tFire, (int) Math.round(a.hold * 1000));
            } else if (brain.lastBlocked && t - lastBlockLog > 0.3) {
                lastBlockLog = t;
                log.append(String.format(Locale.US, "t=%.2f прыжок заблокирован дроном%n", t - armedNs / 1e9));
            }
        } catch (Exception e) {
            log.append("ОШИБКА: ").append(e).append('\n');
        } finally {
            if (img != null) img.close();
        }
    }

    private void locate(Image img, ImageFrame fr, double t) {
        long now = System.nanoTime();
        if (now - lastAttemptNs < 60_000_000L) return;
        lastAttemptNs = now;
        attempts++;
        Geometry g = Geometry.detect(fr);
        if (g != null) found.add(g);
        if (attempts < 14) return;
        Geometry res = Geometry.consensus(found);
        if (res == null) {
            log.append("Игра не найдена: удачных кадров ").append(found.size()).append(" из ").append(attempts).append('\n');
            snapshot(img, null, "runnerbot_not_found.png");
            disarm("не нашёл игру на экране");
            toast("Не нашёл игру. Открой игру на стартовом экране и нажми снова. Снимок в Галерее/RunnerBot");
            return;
        }
        geo = res;
        log.append("Геометрия: ").append(geo).append('\n');
        snapshot(img, geo, "runnerbot_found.png");
        brain.reset();
        mode = RUNNING;
        status("Играю");
        // «тап для старта» в центре игрового поля
        double s = geo.scale;
        double tx = (geo.xl + geo.xr) / 2.0;
        double ty = Math.max(20, geo.gy - 312 * s);
        BotAccessibilityService svc = BotAccessibilityService.instance;
        if (svc != null) svc.tap((float) (tx * toScreen), (float) (ty * toScreen), 60);
        tapX = (float) (tx * toScreen);
        tapY = (float) (ty * toScreen);
        log.append(String.format(Locale.US, "точка нажатия: %.0f,%.0f%n", tapX, tapY));
    }

    private volatile float tapX, tapY;
    private double lastBlockLog;

    private void scheduleTap(final double tFire, final int holdMs) {
        final long target = (long) (tFire * 1e9) - (long) (glat * 1e9);
        firer.execute(() -> {
            while (true) {
                long d = target - System.nanoTime();
                if (d <= 0) break;
                if (d > 2_000_000L) LockSupport.parkNanos(d - 1_500_000L);
            }
            BotAccessibilityService svc = BotAccessibilityService.instance;
            if (svc != null) svc.tap(tapX, tapY, holdMs);
        });
    }

    // ------------------------------------------------------------------ снимок для отладки
    private void snapshot(Image img, Geometry g, String name) {
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer b = p.getBuffer();
            int rs = p.getRowStride();
            Bitmap full = Bitmap.createBitmap(rs / 4, img.getHeight(), Bitmap.Config.ARGB_8888);
            b.rewind();
            full.copyPixelsFromBuffer(b);
            final Bitmap bmp = Bitmap.createBitmap(full, 0, 0, img.getWidth(), img.getHeight());
            full.recycle();
            if (g != null) {
                Canvas c = new Canvas(bmp);
                Paint pt = new Paint();
                pt.setStyle(Paint.Style.STROKE);
                pt.setStrokeWidth(3);
                pt.setColor(0xFFFF0000);
                c.drawLine(g.xl, g.gy, g.xr, g.gy, pt);
                pt.setColor(0xFF00FF00);
                c.drawRect(g.xl, (float) (g.gy - Brain.CROP_UP * g.scale), g.xr, (float) (g.gy + Brain.CROP_DOWN * g.scale), pt);
            }
            new Thread(() -> {
                Saver.savePng(this, bmp, name);
                bmp.recycle();
            }).start();
        } catch (Exception e) {
            log.append("снимок не удался: ").append(e).append('\n');
        }
    }

    // ------------------------------------------------------------------ вспомогательное
    private void toast(final String s) {
        main.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    private void status(final String s) {
        main.post(() -> BotAccessibilityService.setStatus(s));
    }
}
