package com.runnerbot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Сохраняет файлы так, чтобы их было видно в «Галерее» (картинки) и в «Загрузках» (логи). */
final class Saver {
    private Saver() {}

    static boolean savePng(Context c, Bitmap bmp, String name) {
        try {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RunnerBot");
            Uri u = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            try (OutputStream os = r.openOutputStream(u)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean saveText(Context c, String text, String name) {
        try {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RunnerBot");
            Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            try (OutputStream os = r.openOutputStream(u)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
