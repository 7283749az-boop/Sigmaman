package com.runnerbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;
import android.widget.Toast;

/** «Пальцы» бота + плавающая кнопка старт/стоп поверх экрана. */
public class BotAccessibilityService extends AccessibilityService {
    static volatile BotAccessibilityService instance;

    private WindowManager wm;
    private TextView btn;
    private WindowManager.LayoutParams lp;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addOverlay();
        refreshOverlay();
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        removeOverlay();
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- касания
    /** Нажать и держать holdMs миллисекунд в точке (x, y) экрана. */
    void tap(float x, float y, int holdMs) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription sd =
                new GestureDescription.StrokeDescription(p, 0, Math.max(1, holdMs));
        dispatchGesture(new GestureDescription.Builder().addStroke(sd).build(), null, null);
    }

    // ---------------------------------------------------------------- оверлей
    private void addOverlay() {
        float d = getResources().getDisplayMetrics().density;
        btn = new TextView(this);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(22);
        btn.setText("▶");
        setBg(0xFF2E7D32);
        int size = (int) (56 * d);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (6 * d);
        lp.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.78);
        btn.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > 12 || Math.abs(e.getRawY() - sy) > 12) moved = true;
                        if (moved) {
                            lp.x = ox + (int) (e.getRawX() - sx);
                            lp.y = oy + (int) (e.getRawY() - sy);
                            wm.updateViewLayout(btn, lp);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onButton();
                        return true;
                }
                return false;
            }
        });
        wm.addView(btn, lp);
    }

    private void removeOverlay() {
        try { if (btn != null) wm.removeView(btn); } catch (Exception ignored) {}
        btn = null;
    }

    private void setBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(3, Color.WHITE);
        btn.setBackground(g);
    }

    private void onButton() {
        CaptureService cs = CaptureService.instance;
        if (cs == null) {
            Toast.makeText(this, "Сначала в приложении Runner Bot нажми «2. Разрешить захват экрана»", Toast.LENGTH_LONG).show();
            return;
        }
        cs.toggle();
        // цвет обновит setStatus
    }

    /** Перерисовать кнопку по состоянию бота. */
    static void refreshOverlay() {
        final BotAccessibilityService s = instance;
        if (s == null || s.btn == null) return;
        CaptureService cs = CaptureService.instance;
        s.btn.post(() -> {
            if (s.btn == null) return;
            CaptureService c = CaptureService.instance;
            boolean on = c != null && c.isRunningOrLocating();
            s.btn.setText(on ? "■" : "▶");
            s.setBg(c == null ? 0xFF757575 : on ? 0xFFC62828 : 0xFF2E7D32);
        });
    }

    static void setStatus(String text) {
        BotAccessibilityService s = instance;
        if (s != null) Toast.makeText(s, text, Toast.LENGTH_SHORT).show();
        refreshOverlay();
    }
}
