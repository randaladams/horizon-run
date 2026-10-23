package com.horizonrun.game;

import android.app.Activity;

/**
 * Fire TV build: no ads. Fire TV has no Google Play services, and this build is the
 * full version anyway, so nothing here ever loads or shows an ad. Same methods as the
 * phone version so the game code doesn't need to know which build it is in.
 */
public class Ads {

    /** What the game is told when a full-screen ad closes. */
    public interface Listener {
        void onAdFinished(boolean wasRewardedAd, boolean rewarded);
    }

    private final Listener listener;

    public Ads(Activity activity, Listener listener) {
        this.listener = listener;
    }

    public void start() { }

    public boolean rewardedReady() { return false; }

    public boolean interstitialReady() { return false; }

    public boolean isShowing() { return false; }

    /** Never needed (the full version revives for free), but don't leave the game waiting. */
    public void showRewarded() {
        listener.onAdFinished(true, true);
    }

    public void showInterstitial() {
        listener.onAdFinished(false, false);
    }

    public boolean privacyOptionsRequired() { return false; }

    public void showPrivacyOptions() { }
}
