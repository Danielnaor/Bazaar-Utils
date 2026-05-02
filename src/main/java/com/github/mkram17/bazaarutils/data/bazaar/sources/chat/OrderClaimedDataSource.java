package com.github.mkram17.bazaarutils.data.bazaar.sources.chat;

import com.github.mkram17.bazaarutils.data.bazaar.pipeline.ChatOrderSource;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderDelta;
import com.github.mkram17.bazaarutils.events.bazaar.chat.BazaarChatEvent;
import com.github.mkram17.bazaarutils.misc.NotificationType;
import com.github.mkram17.bazaarutils.utils.BazaarLogger;
import com.github.mkram17.bazaarutils.utils.PlayerLogger;
import com.github.mkram17.bazaarutils.utils.annotations.modules.DataSource;
import com.github.mkram17.bazaarutils.data.bazaar.BazaarDataOrigin;
import com.github.mkram17.bazaarutils.utils.bazaar.market.ProductInfo;
import com.github.mkram17.bazaarutils.utils.bazaar.market.TaxContext;
import com.github.mkram17.bazaarutils.utils.bazaar.market.TransactionType;
import com.github.mkram17.bazaarutils.utils.bazaar.market.order.Order;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderResolver;
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription;

/**
 * Advances {@code claimedAmount} on the matched order when a claim chat message is received.
 *
 * <p>Both buy and sell claim messages carry {@code pricePerUnit} as the pre-tax listed price,
 * enabling direct price-similarity matching against the stored order without tax reversal.
 * Target selection prefers the screen-selection hint from
 * {@link com.github.mkram17.bazaarutils.data.HandledOrderAPI}; when absent, falls back to
 * the first live, claimable order at a similar price whose unclaimed fill covers the volume.
 *
 * <p>A sell claim match failure when the product has other tracked sell orders is attributed
 * to a {@code BazaarFlipper} tier mismatch rather than a logic error — a wrong tier shifts
 * the expected post-tax per-unit tolerance band enough to fail the similarity check.
 */
@DataSource
public final class OrderClaimedDataSource extends ChatOrderSource {
    private static final BazaarLogger LOG = BazaarLogger.of(OrderClaimedDataSource.class);

    @Subscription
    public void onBuyOrderClaimed(BazaarChatEvent.BuyOrderClaimed event) {
        var product = ProductInfo.fromDisplayName(event.product).orElse(null);
        if (product == null) {
            LOG.info("Claim skipped (unknown product) — name={}", event.product);

            return;
        }

        applyClaim(product.getProductId(), TransactionType.Side.BUY, event.pricePerUnit, event.amount, event.receivedAt);
    }

    @Subscription
    public void onSellOfferClaimed(BazaarChatEvent.SellOfferClaimed event) {
        // pricePerUnit in SellOfferClaimed is the pre-tax listed price — no reversal needed.
        var product = ProductInfo.fromDisplayName(event.product).orElse(null);
        if (product == null) {
            LOG.info("Claim skipped (unknown product) — name={}", event.product);

            return;
        }

        applyClaim(product.getProductId(), TransactionType.Side.SELL, event.pricePerUnit, event.amount, event.receivedAt);
    }

    /**
     * Advances {@code claimedAmount} on the matched order. For sell claims, a match failure when
     * the product has other tracked sell orders is attributed to tax misconfiguration rather than
     * a logic error — sell claim matching is price-sensitive, and a wrong {@code BazaarFlipper}
     * tier shifts the expected post-tax per-unit value enough to miss the tolerance band.
     */
    private void applyClaim(String productId, TransactionType.Side side, double pricePerUnit, int volume, long receivedAt) {
        var origin = new BazaarDataOrigin.OrderClaim(receivedAt);
        var storage = requireStorage(origin); if (storage == null) return;

        var matched = OrderResolver.forClaim(productId, side, pricePerUnit, volume, storage).orElse(null);
        if (matched == null) {
            if (side == TransactionType.Side.SELL && storage.stream().anyMatch(Order.forProduct(productId, TransactionType.Side.SELL))) {
                TaxContext.warnTaxMisconfiguration("Sell claim for %s matched no tracked order.".formatted(productId));
            } else {
                LOG.warn("Claim skipped — no matching order: product={} side={} price={} vol={}", productId, side, pricePerUnit, volume);
            }

            return;
        }

        PlayerLogger.debug("%s — Claim Δ+%d on %s %s @ %.4f (unclaimed=%d)".formatted(
                origin.describe(), volume, side, productId, pricePerUnit,
                matched.unclaimedFilled()), NotificationType.ORDER_LIFECYCLE, LOG);

        commit(OrderDelta.Update.claim(matched, matched.withClaim(volume, origin)), origin);
    }
}