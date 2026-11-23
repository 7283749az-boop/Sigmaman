package com.runnerbot.core;

/** Источник пикселей: полный кадр экрана. */
public interface Frame {
    int width();
    int height();
    /** Цвет пикселя 0xRRGGBB. Координаты гарантированно внутри кадра. */
    int rgb(int x, int y);
}
