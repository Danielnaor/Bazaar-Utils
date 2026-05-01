package com.github.mkram17.bazaarutils.data.stored;

import com.github.mkram17.bazaarutils.utils.Util;
import com.github.mkram17.bazaarutils.data.bazaar.BazaarDataOrigin;
import com.github.mkram17.bazaarutils.utils.bazaar.gui.layouts.OrdersPageLayout;
import com.github.mkram17.bazaarutils.utils.bazaar.market.order.Order;
import com.github.mkram17.bazaarutils.utils.bazaar.market.order.OrderStatus;
import com.github.mkram17.bazaarutils.utils.storage.ProfileStorage;
import com.google.common.collect.Maps;
import com.mojang.serialization.Codec;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Persistent storage for the player's tracked Bazaar orders, backed by {@link ProfileStorage}.
 *
 * <p>Maintains a secondary slot index for O(1) container-slot → order lookups, used by
 * {@link com.github.mkram17.bazaarutils.data.HandledOrderAPI} on Orders page clicks. The index
 * covers only live on-screen orders and is rebuilt on every {@link #apply} call.
 *
 * <p>{@link #apply} is the single write path: it applies a {@link StorageOp} transformation,
 * strips all terminal orders, persists via {@link ProfileStorage}, rebuilds the slot index,
 * and returns the post-persist list. No other code path may write to storage.
 */
public final class UserOrdersStorage {
    private static final ProfileStorage<List<Order>> INSTANCE = new ProfileStorage<>(
            0,
            ArrayList::new,
            "user_orders",
            v -> Codec.list(Order.CODEC).xmap(ArrayList::new, list -> list),
            (it) -> rebuildSlotIndex(it.get())
    );

    private static volatile Map<Integer, Order> slotIndex = Map.of();

    private static void rebuildSlotIndex(List<Order> orders) {
        if (orders == null) {
            slotIndex = Map.of();
            Util.logMessage("rebuildSlotIndex: orders null — cleared index");

            return;
        }

        slotIndex = Maps.uniqueIndex(
                orders.stream()
                        .filter(order -> order.isLive() && order.slotPosition().isVisible())
                        .iterator(),
                order -> order.slotPosition().indexIfVisible().getAsInt() // safe as per #isVisible
        );

        Util.logMessage("rebuildSlotIndex: %d anchored entries from %d orders".formatted(slotIndex.size(), orders.size()));
    }

    /**
     * Returns the live on-screen order anchored to the given container slot index, or empty if
     * none is anchored there. Backed by the O(1) slot index rebuilt on every {@link #apply} call;
     * results are only valid until the next call to {@code apply}.
     */
    public static Optional<Order> bySlot(int slot) {
        return Optional.ofNullable(slotIndex.get(slot));
    }

    /**
     * Returns the current order list, or {@code null} if no profile is loaded.
     * Pipeline sources that need to early-return on an unloaded profile use this.
     */
    public static @Nullable List<Order> get() {
        return INSTANCE.get();
    }

    /** {@code true} when a profile is loaded and storage is available. */
    public static boolean isLoaded() {
        return INSTANCE.get() != null;
    }

    /**
     * All tracked orders, or empty if no profile is loaded.
     * Use when null and empty are equivalent to the caller.
     */
    public static List<Order> orders() {
        var it = INSTANCE.get();

        return it != null ? it : List.of();
    }

    /**
     * All active (Set or Partial) orders, or empty if no profile is loaded.
     */
    public static List<Order> active() {
        var it = INSTANCE.get();

        return it == null ? List.of() : it.stream().filter(Order::isActive).toList();
    }

    private UserOrdersStorage() {}

    /**
     * Applies {@code operation} to the current order list, strips terminal orders, persists,
     * rebuilds the slot index, and returns the post-persist list.
     * Returns an empty list if storage is not loaded.
     */
    public static List<Order> apply(StorageOp operation) {
        var storage = INSTANCE.get();
        if (storage == null) return List.of();

        var transformed = operation.apply(new ArrayList<>(storage));

        var filtered = transformed.stream()
                .filter(Order::isLive)
                .collect(Collectors.toCollection(ArrayList::new));

        Util.logMessage("Persist: %d → %d orders (dropped %d terminal)".formatted(transformed.size(), filtered.size(), transformed.size() - filtered.size()));

        INSTANCE.set(filtered);
        rebuildSlotIndex(filtered);

        return filtered;
    }

    /** Finds the post-commit copy of {@code ref} in a list returned by {@link #apply}. */
    public static Optional<Order> findById(List<Order> list, UUID id) {
        return list.stream().filter(order -> order.id().equals(id)).findFirst();
    }

    /**
     * Composable transformation over a mutable order list. Chain with {@link #then};
     * commit atomically via {@link UserOrdersStorage#apply}. Implementations must not
     * assume the list is sorted or deduplicated.
     */
    @FunctionalInterface
    public interface StorageOp extends UnaryOperator<List<Order>> {

        /** Sequences {@code this} then {@code next}, like {@link Function#andThen}. */
        default StorageOp then(StorageOp next) {
            return list -> next.apply(this.apply(list));
        }

        /** Replaces {@code target} in the list with its cancelled copy. */
        static StorageOp cancel(Order target, BazaarDataOrigin origin) {
            var cancelled = target.cancelled(origin);

            return list -> list.stream()
                    .map(order -> order.id().equals(target.id()) ? cancelled : order)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        /** Replaces the first order matching {@code target} by id with {@code mutation.apply(target)}. */
        static StorageOp replace(Order target, UnaryOperator<Order> mutation) {
            return list -> list.stream()
                    .map(order -> order.id().equals(target.id()) ? mutation.apply(order) : order)
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        /** Appends {@code order} to the list. */
        static StorageOp add(Order order) {
            return list -> {
                var next = new ArrayList<>(list);
                next.add(order);

                return next;
            };
        }

        /** Full reindex — unconditional. */
        static StorageOp reindex() {
            return OrdersPageLayout::reindexActive;
        }

        /**
         * Reindexes all active orders when {@code order} has just transitioned to
         * {@link OrderStatus.Filled}; a no-op otherwise. A newly filled order vacates its
         * position in the unfilled queue, shifting all subsequent slot offsets within the group.
         */
        static StorageOp reindexIfFilled(Order order) {
            return order.status() instanceof OrderStatus.Filled
                    ? reindex()
                    : list -> list;
        }
    }
}