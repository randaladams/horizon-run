package com.horizonrun.game;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;


import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google AdMob ads with Google's consent form (required in the EU/UK).
 *
 * - Rewarded ad: "watch an ad to keep running" after a crash, once per run.
 * - Interstitial: shown between games, after every 4 finished games.
 *
 * Ad unit ids come from BuildConfig: the Play Store bundle gets the real AdMob ids,
 * the side-loaded test APK gets Google's public test ids (which always show test ads).
 */
public class Ads {

    // Real ids in the Play Store bundle, Google test ids in the test APK (see app/build.gradle).
    static final String REWARDED_ID = BuildConfig.ADMOB_REWARDED;
    static final String INTERSTITIAL_ID = BuildConfig.ADMOB_INTERSTITIAL;

    /** What the game is told when a full-screen ad closes. */
    public interface Listener {
        /** @param rewarded true if the player watched the rewarded ad to the end */
        void onAdFinished(boolean wasRewardedAd, boolean rewarded);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ConsentInformation consent;

    private volatile RewardedAd rewarded;
    private volatile InterstitialAd interstitial;
    private volatile boolean showing;
    private boolean loadingRewarded, loadingInterstitial;
    private boolean earned;

    public Ads(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /** Ask for consent where the law requires it, then start the ads SDK. */
    public void start() {
        try {
            consent = UserMessagingPlatform.getConsentInformation(activity);
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            consent.requestConsentInfoUpdate(activity, params,
                    () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, formError -> {
                        if (consent.canRequestAds()) initSdk();
                    }),
                    requestError -> {
                        if (consent.canRequestAds()) initSdk();
                    });
            if (consent.canRequestAds()) initSdk();
        } catch (Throwable t) {
            // never let ads break the game
        }
    }

    private void initSdk() {
        if (!started.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                MobileAds.initialize(activity, status -> main.post(() -> {
                    loadRewarded();
                    loadInterstitial();
                }));
            } catch (Throwable ignored) { }
        }, "ads-init").start();
    }

    // ---- loading ------------------------------------------------------------------

    private void loadRewarded() {
        if (!started.get() || rewarded != null || loadingRewarded) return;
        loadingRewarded = true;
        RewardedAd.load(activity, REWARDED_ID, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(RewardedAd ad) {
                loadingRewarded = false;
                rewarded = ad;
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                loadingRewarded = false;
                rewarded = null;
                main.postDelayed(Ads.this::loadRewarded, 30_000);
            }
        });
    }

    private void loadInterstitial() {
        if (!started.get() || interstitial != null || loadingInterstitial) return;
        loadingInterstitial = true;
        InterstitialAd.load(activity, INTERSTITIAL_ID, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(InterstitialAd ad) {
                loadingInterstitial = false;
                interstitial = ad;
            }

            @Override
            public void onAdFailedToLoad(LoadAdError error) {
                loadingInterstitial = false;
                interstitial = null;
                main.postDelayed(Ads.this::loadInterstitial, 30_000);
            }
        });
    }

    // ---- showing (safe to call from any thread) -----------------------------------

    public boolean rewardedReady() { return rewarded != null && !showing; }

    public boolean interstitialReady() { return interstitial != null && !showing; }

    public boolean isShowing() { return showing; }

    public void showRewarded() {
        main.post(() -> {
            RewardedAd ad = rewarded;
            if (ad == null || showing) {
                listener.onAdFinished(true, false);
                return;
            }
            showing = true;
            earned = false;
            rewarded = null;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    showing = false;
                    listener.onAdFinished(true, earned);
                    loadRewarded();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError error) {
                    showing = false;
                    listener.onAdFinished(true, false);
                    loadRewarded();
                }
            });
            ad.show(activity, reward -> earned = true);
        });
    }

    public void showInterstitial() {
        main.post(() -> {
            InterstitialAd ad = interstitial;
            if (ad == null || showing) {
                listener.onAdFinished(false, false);
                return;
            }
            showing = true;
            interstitial = null;
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    showing = false;
                    listener.onAdFinished(false, false);
                    loadInterstitial();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(AdError error) {
                    showing = false;
                    listener.onAdFinished(false, false);
                    loadInterstitial();
                }
            });
            ad.show(activity);
        });
    }

    // ---- privacy options (a button the law requires once consent was asked) ------

    public boolean privacyOptionsRequired() {
        try {
            return consent != null && consent.getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void showPrivacyOptions() {
        main.post(() -> {
            try {
                UserMessagingPlatform.showPrivacyOptionsForm(activity, formError -> {
                    if (consent != null && consent.canRequestAds()) initSdk();
                });
            } catch (Throwable ignored) { }
        });
    }
}
