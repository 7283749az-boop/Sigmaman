package com.runnerbot;

import android.media.Image;

import com.runnerbot.core.Frame;

import java.nio.ByteBuffer;

/** Обёртка над кадром ImageReader (RGBA_8888) для ядра. */
final class ImageFrame implements Frame {
    private final ByteBuffer buf;
    private final int w, h, rowStride, pixelStride;

    ImageFrame(Image img) {
        Image.Plane p = img.getPlanes()[0];
        buf = p.getBuffer();
        rowStride = p.getRowStride();
        pixelStride = p.getPixelStride();
        w = img.getWidth();
        h = img.getHeight();
    }

    @Override public int width() { return w; }
    @Override public int height() { return h; }

    @Override public int rgb(int x, int y) {
        int i = y * rowStride + x * pixelStride;
        return ((buf.get(i) & 255) << 16) | ((buf.get(i + 1) & 255) << 8) | (buf.get(i + 2) & 255);
    }
}
