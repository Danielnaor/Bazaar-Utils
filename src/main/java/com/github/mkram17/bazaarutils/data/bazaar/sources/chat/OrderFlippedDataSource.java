package com.github.mkram17.bazaarutils.data.bazaar.sources.chat;

import com.github.mkram17.bazaarutils.data.bazaar.pipeline.BookMutation;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.ChatOrderSource;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderDelta;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderResolver;
import com.github.mkram17.bazaarutils.events.bazaar.chat.BazaarChatEvent;
import com.github.mkram17.bazaarutils.misc.NotificationType;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.github.mkram17.bazaarutils.utils.Util;
import com.github.mkram17.bazaarutils.utils.annotations.modules.DataSource;
import com.github.mkram17.bazaarutils.data.bazaar.BazaarDataOrigin;
import com.github.mkram17.bazaarutils.utils.bazaar.gui.layouts.OrdersPageLayout;
import com.github.mkram17.bazaarutils.utils.bazaar.market.ProductInfo;
import com.github.mkram17.bazaarutils.utils.bazaar.market.TransactionType;
import com.github.mkram17.bazaarutils.utils.bazaar.market.order.*;
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription;

import java.util.UUID;

/**
 * Claims the matched buy order and places a new sell offer when a flip chat message is received.
 *
 * <p>The message carries the product name, flipped volume, and total expected profit — not the
 * sell price. The sell price is recovered as:
 * <pre>
 *   sellPrice = truncate(matchedBuy.pricePerItem() + totalProfit / amount)
 * </pre>
 * {@code totalProfit} is already net of Bazaar tax; do not apply a tax factor. The buy book
 * requires no mutation — the fill decrement was already applied when the fill was originally
 * recorded. Only the sell book gains a new level at the recovered price.
 *
 * <p>The buy order is fully claimed in the same step. Both the claim and the sell placement
 * are committed atomically via {@link OrderDelta.Swap}. The new sell order starts at
 * {@link OrderStatus.Set} with a 7-day expiry relative to the confirmation timestamp.
 */
@DataSource
public final class OrderFlippedDataSource extends ChatOrderSource {
    @Subscription
    public void onOrderFlipped(BazaarChatEvent.BuyOrderFlipped event) {
        var origin = new BazaarDataOrigin.OrderFlipped(event.receivedAt);
        var storage = requireStorage(origin); if (storage == null) return;

        var product = ProductInfo.fromDisplayName(event.product).orElse(null);
        if (product == null) {
            Util.logMessage("Flip skipped — unknown product: %s".formatted(event.product));

            return;
        }

        String productId = product.getProductId();
        int flipVolume = event.amount;
        double profitPerUnit = event.totalProfit / event.amount;

        var matchedBuy = OrderResolver.forFlip(productId, flipVolume, storage).orElse(null);
        if (matchedBuy == null) {
            Util.notifyError("Flip match not found — %s vol=%d profitPerUnit=%.4f".formatted(productId, flipVolume, profitPerUnit), new Throwable());

            return;
        }

        double sellPrice = Util.truncateNum(matchedBuy.pricePerItem() + profitPerUnit);
        var claimedBuy = matchedBuy.withClaim(matchedBuy.unclaimedFilled(), origin);
        var slotPosition = OrdersPageLayout.computeScreenSlot(productId, TransactionType.Side.SELL, sellPrice, origin.confirmedAt(), false, storage);

        long expiresAt = origin.confirmedAt() + 7L * 24 * 3_600_000L;

        var newSell = new Order(
                UUID.randomUUID(), productId, TransactionType.Side.SELL,
                sellPrice, flipVolume, 0, 0, slotPosition,
                new OrderStatus.Set(), origin.confirmedAt(), origin.confirmedAt(), false,
                expiresAt);

        PlayerActionUtil.notifyAll("%s — Flipped %s %dx: buy @ %.4f → sell @ %.4f (Δprofit/unit=%.4f)".formatted(
                origin.describe(), productId, flipVolume,
                matchedBuy.pricePerItem(), sellPrice, profitPerUnit), NotificationType.ORDERDATA);

        PlayerActionUtil.notifyAll("%s — Book place: %s %s Δ%d @ %.4f (flip)".formatted(
                origin.describe(),
                TransactionType.of(TransactionType.Side.SELL, TransactionType.Method.ORDER).getPriceType(),
                productId, flipVolume, sellPrice), NotificationType.BAZAARDATA);

        commit(new OrderDelta.Swap(
                matchedBuy, claimedBuy, newSell,
                BookMutation.place(TransactionType.of(
                        TransactionType.Side.SELL, TransactionType.Method.ORDER), sellPrice, flipVolume),
                profitPerUnit), origin);
    }
}