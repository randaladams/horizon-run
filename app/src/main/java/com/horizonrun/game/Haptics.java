package com.horizonrun.game;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Short vibrations. Kept in its own class so that the Android 8+ VibrationEffect API is
 * only touched on devices that have it (Fire OS 6 is Android 7.1, and Fire TV has no
 * vibrator at all).
 */
final class Haptics {

    private Haptics() { }

    @SuppressWarnings("deprecation")
    static void buzz(Context ctx, int ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Exception ignored) { }
    }
}
