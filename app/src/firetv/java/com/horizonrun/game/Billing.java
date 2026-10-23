package com.horizonrun.game;

import android.app.Activity;

/**
 * Fire TV build: the full version is always unlocked, with no store involved
 * (Fire TV has no Google Play). Same methods as the phone version.
 */
public class Billing {

    public interface Listener {
        void onFullVersionOwned(boolean owned);
        void onBillingMessage(String message);
    }

    private final Listener listener;

    public Billing(Activity activity, Listener listener) {
        this.listener = listener;
    }

    public void start() { listener.onFullVersionOwned(true); }

    public void refreshOwned() { }

    public String price() { return null; }

    public void buy() { }

    public void end() { }
}
