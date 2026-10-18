package com.horizonrun.game;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;

/**
 * Google Play in-app purchase for the one-time "full_version" upgrade.
 *
 * Create a one-time product with the id FULL_VERSION_ID in Play Console
 * (Monetize > Products > In-app products) and set the price there.
 * Purchases only work in copies installed from Google Play.
 */
public class Billing {

    static final String FULL_VERSION_ID = "full_version";

    public interface Listener {
        /** Called on the main thread whenever ownership is known or changes. */
        void onFullVersionOwned(boolean owned);
        /** Something worth telling the player ("Purchase cancelled" etc). */
        void onBillingMessage(String message);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BillingClient client;
    private volatile ProductDetails product;
    private volatile boolean ready;
    private int retries;

    public Billing(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start() {
        try {
            client = BillingClient.newBuilder(activity)
                    .setListener(this::onPurchasesUpdated)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .enableAutoServiceReconnection()   // Billing Library 8: reconnects by itself
                    .build();
            connect();
        } catch (Throwable t) {
            client = null;
        }
    }

    private void connect() {
        if (client == null) return;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                ready = true;
                retries = 0;
                refreshOwned();
                loadProduct();
            }

            @Override
            public void onBillingServiceDisconnected() {
                ready = false;
                if (retries++ < 5) main.postDelayed(Billing.this::connect, 2000L * retries);
            }
        });
    }

    /** Asks Play what the player already owns (also restores after a reinstall). */
    public void refreshOwned() {
        if (client == null || !ready) return;
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                    boolean owned = false;
                    for (Purchase p : purchases) owned |= handle(p);
                    final boolean o = owned;
                    main.post(() -> listener.onFullVersionOwned(o));
                });
    }

    private void loadProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(FULL_VERSION_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()))
                .build();
        // Billing Library 8: the callback gets a QueryProductDetailsResult
        client.queryProductDetailsAsync(params, (result, details) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK || details == null) return;
            List<ProductDetails> list = details.getProductDetailsList();
            if (list != null && !list.isEmpty()) product = list.get(0);
        });
    }

    /** Price as Play shows it in the player's currency, or null if not loaded yet. */
    public String price() {
        ProductDetails p = product;
        if (p == null || p.getOneTimePurchaseOfferDetails() == null) return null;
        return p.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }

    public void buy() {
        main.post(() -> {
            ProductDetails p = product;
            if (client == null || !ready || p == null) {
                listener.onBillingMessage("The store isn't available right now. "
                        + "Purchases work in the copy installed from Google Play.");
                return;
            }
            BillingFlowParams flow = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(p).build()))
                    .build();
            client.launchBillingFlow(activity, flow);
        });
    }

    private void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            boolean owned = false;
            for (Purchase p : purchases) owned |= handle(p);
            final boolean o = owned;
            main.post(() -> {
                if (o) listener.onFullVersionOwned(true);
                else listener.onBillingMessage("Payment pending - the upgrade unlocks once it goes through.");
            });
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refreshOwned();
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            main.post(() -> listener.onBillingMessage("Purchase cancelled."));
        } else {
            main.post(() -> listener.onBillingMessage("Purchase didn't go through. Please try again."));
        }
    }

    /** @return true if this purchase grants the full version (acknowledging it if needed). */
    private boolean handle(Purchase p) {
        if (!p.getProducts().contains(FULL_VERSION_ID)) return false;
        if (p.getPurchaseState() != Purchase.PurchaseState.PURCHASED) return false;
        if (!p.isAcknowledged() && client != null) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.getPurchaseToken()).build(), r -> { });
        }
        return true;
    }

    public void end() {
        try { if (client != null) client.endConnection(); } catch (Throwable ignored) { }
    }
}
