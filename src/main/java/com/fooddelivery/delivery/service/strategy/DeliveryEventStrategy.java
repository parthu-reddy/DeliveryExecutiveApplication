package com.fooddelivery.delivery.service.strategy;

import com.fooddelivery.common.event.OrderScopedEvent;

import java.util.List;

/**
 * Handles one or more order event types on behalf of {@code OrderEventConsumer}.
 *
 * <p>Typed on the event it accepts. The first cut of typed binding declared
 * {@code process(Object payload, String eventType)} and left every implementation to recover its
 * type with {@code instanceof} or an unchecked cast. That is barely better than the {@code JsonNode}
 * it replaced: an event the listener bound to the wrong class matched no branch, nothing threw, and
 * the event was dropped — which is exactly how ORDER_PREPARING, ORDER_READY, DRIVER_ASSIGNED,
 * DISPATCH_FAILED, MANUAL_INTERVENTION_REQUIRED and ORDER_DELAY_REJECTED silently stopped working.
 *
 * <p>{@link #dispatch} is now the single unchecked point in the chain, and it uses
 * {@link Class#cast} — so a mismatch is a {@code ClassCastException} naming both classes, not a
 * silent no-op. Everything past it is compile-checked.
 *
 * <p>A strategy covering several event types with different classes declares
 * {@code DeliveryEventStrategy<OrderScopedEvent>}. That is deliberate rather than a cop-out: the
 * bound still gives it {@link OrderScopedEvent#orderUuid()} without any casting, which is all most
 * of them needed. {@code TerminalStateStrategy} carried a twelve-branch {@code instanceof} chain
 * whose only purpose was extracting the order id; it is one call now.
 *
 * @param <T> the event type this strategy accepts
 */
public interface DeliveryEventStrategy<T extends OrderScopedEvent> {

    /**
     * The class {@link #handle} accepts. Must be a supertype of every event this strategy declares
     * in {@link #getEventTypes()}; {@code StrategyEventTypeCoverageTest} checks that each declared
     * type has a bindable class, and {@link #dispatch} fails loudly if the two disagree.
     */
    Class<T> eventClass();

    /** Acts on a bound event. */
    void handle(T event, String eventType) throws Exception;

    /** The event type names this strategy is registered for. */
    List<String> getEventTypes();

    /**
     * Entry point for the listener, which holds strategies as {@code DeliveryEventStrategy<?>} and
     * cannot call {@link #handle} directly.
     */
    default void dispatch(OrderScopedEvent event, String eventType) throws Exception {
        handle(eventClass().cast(event), eventType);
    }
}
