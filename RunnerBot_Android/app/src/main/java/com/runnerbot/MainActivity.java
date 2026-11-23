package com.runnerbot;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;

    private TextView status;
    private EditText eShift, eLat, eGlat;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(CaptureService.PREFS, MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Runner Bot");
        title.setTextSize(24);
        root.addView(title);

        TextView how = new TextView(this);
        how.setTextSize(15);
        how.setText("Как пользоваться:\n"
                + "1) Нажми кнопку 1 и включи «Runner Bot» в списке (разрешение на «нажатия»).\n"
                + "2) Нажми кнопку 2 и разреши запись экрана («Начать»).\n"
                + "3) Открой игру в Telegram на стартовом экране.\n"
                + "4) Нажми круглую зелёную кнопку ▶ поверх экрана — бот сам найдёт игру и побежит. "
                + "Красная ■ — стоп (её можно перетащить, если мешает).\n");
        how.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(how);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 0, 0, pad / 2);
        root.addView(status);

        root.addView(button("1. Включить «нажималку» (спецвозможности)", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("2. Разрешить захват экрана", v -> {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }));
        root.addView(button("Остановить захват экрана", v -> {
            stopService(new Intent(this, CaptureService.class));
            refresh();
        }));

        TextView st = new TextView(this);
        st.setText("\nТонкая настройка (менять только если советуют):");
        root.addView(st);
        eShift = field(root, "shift — сдвиг прыжка, кан. px (+ раньше, − позже)", prefs.getFloat("shift", 0f));
        eLat = field(root, "lat — задержка до отрыва, сек", prefs.getFloat("lat", 0.05f));
        eGlat = field(root, "glat — задержка доставки нажатия, сек", prefs.getFloat("glat", 0.03f));
        root.addView(button("Сохранить настройки", v -> {
            prefs.edit()
                    .putFloat("shift", num(eShift, 0f))
                    .putFloat("lat", num(eLat, 0.05f))
                    .putFloat("glat", num(eGlat, 0.03f))
                    .apply();
            Toast.makeText(this, "Сохранено (действует со следующего запуска бота)", Toast.LENGTH_SHORT).show();
        }));

        TextView foot = new TextView(this);
        foot.setTextSize(13);
        foot.setPadding(0, pad, 0, 0);
        foot.setText("После каждого запуска бот сохраняет лог в «Загрузки/RunnerBot», а снимок экрана с найденной "
                + "линией земли — в «Галерею/RunnerBot». Эти файлы можно прислать мне для настройки.");
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private Button button(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setOnClickListener(l);
        return bt;
    }

    private EditText field(LinearLayout root, String label, float val) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        root.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setText(String.valueOf(val));
        root.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static float num(EditText e, float def) {
        try { return Float.parseFloat(e.getText().toString().trim().replace(',', '.')); }
        catch (Exception ex) { return def; }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CAPTURE) {
            if (res == RESULT_OK && data != null) {
                Intent i = new Intent(this, CaptureService.class);
                i.putExtra(CaptureService.EXTRA_CODE, res);
                i.putExtra(CaptureService.EXTRA_DATA, data);
                startForegroundService(i);
                Toast.makeText(this, "Захват включён. Открой игру и нажми зелёную кнопку ▶", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Запись экрана не разрешена", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        status.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isDestroyed()) { refresh(); status.postDelayed(this, 1000); }
            }
        }, 1000);
    }

    private boolean accessibilityOn() {
        return BotAccessibilityService.instance != null;
    }

    private void refresh() {
        boolean acc = accessibilityOn();
        boolean cap = CaptureService.instance != null;
        status.setText("Нажималка: " + (acc ? "включена ✔" : "ВЫКЛЮЧЕНА ✘ (кнопка 1)")
                + "\nЗахват экрана: " + (cap ? "включён ✔" : "выключен ✘ (кнопка 2)"));
    }
}
