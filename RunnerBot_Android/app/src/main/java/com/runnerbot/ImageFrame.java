package com.runnerbot;

import android.media.Image;

import com.runnerbot.core.Frame;

import java.nio.ByteBuffer;

/** Кадр ImageReader (RGBA_8888), скопированный в обычный массив (чтение из него в разы быстрее). */
final class ImageFrame implements Frame {
    private byte[] data = new byte[0];
    private int w, h, rowStride, pixelStride;

    /** Заполнить из нового кадра (массив переиспользуется). */
    void load(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        rowStride = p.getRowStride();
        pixelStride = p.getPixelStride();
        w = img.getWidth();
        h = img.getHeight();
        buf.rewind();
        int n = buf.remaining();
        if (data.length < n) data = new byte[n];
        buf.get(data, 0, n);
    }

    @Override public int width() { return w; }
    @Override public int height() { return h; }

    @Override public int rgb(int x, int y) {
        int i = y * rowStride + x * pixelStride;
        return ((data[i] & 255) << 16) | ((data[i + 1] & 255) << 8) | (data[i + 2] & 255);
    }
}
