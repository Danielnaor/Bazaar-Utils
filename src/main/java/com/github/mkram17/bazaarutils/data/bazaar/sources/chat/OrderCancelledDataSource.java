package com.github.mkram17.bazaarutils.data.bazaar.sources.chat;

import com.github.mkram17.bazaarutils.data.bazaar.pipeline.BookMutation;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.ChatOrderSource;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderDelta;
import com.github.mkram17.bazaarutils.events.bazaar.chat.BazaarChatEvent;
import com.github.mkram17.bazaarutils.misc.NotificationType;
import com.github.mkram17.bazaarutils.utils.PlayerActionUtil;
import com.github.mkram17.bazaarutils.utils.Priority;
import com.github.mkram17.bazaarutils.utils.Util;
import com.github.mkram17.bazaarutils.utils.annotations.modules.DataSource;
import com.github.mkram17.bazaarutils.data.bazaar.BazaarDataOrigin;
import com.github.mkram17.bazaarutils.utils.bazaar.market.ProductInfo;
import com.github.mkram17.bazaarutils.utils.bazaar.market.TransactionType;
import com.github.mkram17.bazaarutils.data.bazaar.pipeline.OrderResolver;
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription;

/**
 * Evicts a cancelled order and decrements its unfilled volume from the book.
 *
 * <p>Buy cancel: the message carries only the refunded coin total. The stored order
 * is matched by {@code pricePerItem × unfilledAmount ≈ refundedCoins} within a tolerance
 * that absorbs per-unit epsilon rounding above {@link OrderInfo#FOLDING_THRESHOLD} and
 * k/M display truncation in the stored fill count.
 *
 * <p>Sell cancel: the message carries the product display name and the number of items
 * returned, which equals the unfilled remainder. The stored order is matched within
 * {@link com.github.mkram17.bazaarutils.utils.bazaar.components.PageOrderParser#FILL_TRUNCATION_MAX}
 * units of the computed unfilled amount.
 *
 * <p>A match failure is not an error in either path — {@code OrdersScreenDataSource} may have
 * already evicted the order before this message arrived. Both paths, on match, commit an
 * {@link OrderDelta.Evict} with a terminal book decrement at the unfilled volume.
 */
@DataSource
public final class OrderCancelledDataSource extends ChatOrderSource {
    @Subscription(priority = Priority.FIRST)
    private void onBuyOrderCancelled(BazaarChatEvent.BuyOrderCancelled event) {
        var origin = new BazaarDataOrigin.OrderCancelled(event.receivedAt);

        var storage = requireStorage(origin);
        if (storage == null) return;

        var matched = OrderResolver.forBuyCancel(event.refundedCoins, storage).orElse(null);
        if (matched == null) {
            // Not an error: the orders screen may have reconciled and evicted this order
            // before the chat cancellation message arrived.
            Util.logMessage("Buy cancel skipped (already screen-reconciled) — coinsRefunded=%f".formatted(event.refundedCoins));

            return;
        }

        PlayerActionUtil.notifyAll("%s — Cancelled — buy order: %s".formatted(origin.describe(), matched.describe()), NotificationType.ORDERDATA);

        PlayerActionUtil.notifyAll("%s — Book decrement: %s %s Δ%d @ %.4f (cancelled)".formatted(
                origin.describe(),
                TransactionType.of(TransactionType.Side.BUY, TransactionType.Method.ORDER).getPriceType(),
                matched.productId(), matched.unfilledAmount(), matched.pricePerItem()), NotificationType.BAZAARDATA);

        commit(new OrderDelta.Evict(matched, BookMutation.decrement(TransactionType.of(TransactionType.Side.BUY, TransactionType.Method.ORDER), matched.pricePerItem(), matched.unfilledAmount(), true)), origin);
    }

    @Subscription(priority = Priority.FIRST)
    private void onSellOfferCancelled(BazaarChatEvent.SellOfferCancelled event) {
        var origin = new BazaarDataOrigin.OrderCancelled(event.receivedAt);
        var storage = requireStorage(origin); if (storage == null) return;

        var product = ProductInfo.fromDisplayName(event.product).orElse(null);
        if (product == null) {
            Util.logMessage("Sell cancel skipped — unknown product: %s".formatted(event.product));

            return;
        }

        var matched = OrderResolver.forSellCancel(product.getProductId(), event.amount, storage).orElse(null);
        if (matched == null) {
            Util.logMessage("Sell cancel skipped (already screen-reconciled) — productId=%s".formatted(product.getProductId()));

            return;
        }

        PlayerActionUtil.notifyAll("%s — Cancelled — sell offer: %s".formatted(origin.describe(), matched.describe()), NotificationType.ORDERDATA);

        PlayerActionUtil.notifyAll("%s — Book decrement: %s %s Δ%d @ %.4f (cancelled)".formatted(
                origin.describe(),
                TransactionType.of(TransactionType.Side.SELL, TransactionType.Method.ORDER).getPriceType(),
                matched.productId(), matched.unfilledAmount(), matched.pricePerItem()), NotificationType.BAZAARDATA);

        commit(new OrderDelta.Evict(matched, BookMutation.decrement(
                TransactionType.of(TransactionType.Side.SELL, TransactionType.Method.ORDER),
                matched.pricePerItem(), matched.unfilledAmount(), true)), origin);
    }
}