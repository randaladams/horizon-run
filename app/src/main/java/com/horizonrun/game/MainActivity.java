package com.horizonrun.game;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;

public class MainActivity extends Activity {

    private GameView gameView;
    private Billing billing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gameView = new GameView(this);
        setContentView(gameView);
        hideSystemUi();

        Ads ads = new Ads(this, gameView);
        gameView.setAds(ads);
        if (gameView.wantsAds()) ads.start();

        billing = new Billing(this, gameView);
        gameView.setBilling(billing);
        billing.start();

        // Back button: leave the scoreboard / tutorial first, otherwise close the game.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    () -> { if (!gameView.handleBack()) finish(); });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        gameView.resume();
        if (billing != null) billing.refreshOwned();   // picks up purchases made elsewhere
    }

    @Override
    protected void onPause() {
        gameView.pause();
        super.onPause();
    }

    /** Fire TV remote, keyboards and game controllers. BACK still goes to onBackPressed. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (gameView != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK
                && gameView.onRemoteKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (gameView != null && event.getAction() == MotionEvent.ACTION_MOVE
                && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            gameView.onPadMotion(event);   // steering only; Android still turns the stick into arrows for menus
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!gameView.handleBack()) super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        gameView.release();
        if (billing != null) billing.end();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }
}
