package com.fooddelivery.delivery.service.strategy;

import com.fooddelivery.common.event.OrderAcceptedEvent;
import com.fooddelivery.common.event.OrderCancelledEvent;
import com.fooddelivery.common.event.OrderScopedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A strategy handed the wrong event type must fail loudly.
 *
 * <p>This is the property the typed interface exists for. When {@code process(Object, String)} was
 * the contract, every strategy recovered its type with {@code instanceof} — so an event bound to the
 * wrong class matched no branch, nothing threw, and the event disappeared. Six event types were lost
 * that way (ORDER_PREPARING, ORDER_READY, DRIVER_ASSIGNED, DISPATCH_FAILED,
 * MANUAL_INTERVENTION_REQUIRED, ORDER_DELAY_REJECTED) with nothing in the logs.
 *
 * <p>{@code dispatch} now goes through {@link Class#cast}, so the same mistake is a
 * {@code ClassCastException} naming both classes and the message lands in the DLT instead of
 * vanishing.
 */
public class StrategyDispatchTypingTest {

    /** Minimal strategy over a single concrete event type, exactly like the four real ones. */
    private static final class OnlyAcceptsOrderAccepted
            implements DeliveryEventStrategy<OrderAcceptedEvent> {
        private OrderAcceptedEvent received;

        @Override
        public Class<OrderAcceptedEvent> eventClass() {
            return OrderAcceptedEvent.class;
        }

        @Override
        public void handle(OrderAcceptedEvent event, String eventType) {
            this.received = event;
        }

        @Override
        public List<String> getEventTypes() {
            return List.of("ORDER_ACCEPTED");
        }
    }

    @Test
    public void dispatchDeliversTheRightTypeToHandle() throws Exception {
        OnlyAcceptsOrderAccepted strategy = new OnlyAcceptsOrderAccepted();
        OrderAcceptedEvent event = new OrderAcceptedEvent();
        event.setOrderId(UUID.randomUUID().toString());

        strategy.dispatch(event, "ORDER_ACCEPTED");

        assertEquals(event, strategy.received, "handle() received the event dispatch was given");
    }

    @Test
    public void dispatchThrowsRatherThanSilentlyDroppingAMismatchedEvent() {
        OnlyAcceptsOrderAccepted strategy = new OnlyAcceptsOrderAccepted();
        OrderScopedEvent wrongType = OrderCancelledEvent.builder()
                .orderId(UUID.randomUUID().toString()).reason("test").build();

        ClassCastException e = assertThrows(ClassCastException.class,
                () -> strategy.dispatch(wrongType, "ORDER_ACCEPTED"),
                "a mismatched event must throw, not be ignored");

        assertTrue(e.getMessage().contains("OrderCancelledEvent")
                        && e.getMessage().contains("OrderAcceptedEvent"),
                "the exception must name both classes so the DLT entry is diagnosable, was: "
                        + e.getMessage());
        assertEquals(null, strategy.received, "handle() must not have been called");
    }

    @Test
    public void aMultiTypeStrategyStillGetsTheOrderIdWithoutCasting() throws Exception {
        // TerminalStateStrategy and OrderStatusUpdatedStrategy declare OrderScopedEvent because they
        // cover several concrete types. The bound is what makes that safe rather than a cop-out:
        // orderUuid() resolves on any of them, which is what their instanceof chains were for.
        UUID id = UUID.randomUUID();
        OrderScopedEvent cancelled = OrderCancelledEvent.builder().orderId(id.toString()).build();
        OrderAcceptedEvent accepted = new OrderAcceptedEvent();
        accepted.setOrderId(id.toString());

        assertEquals(id, cancelled.orderUuid());
        assertEquals(id, accepted.orderUuid());
    }
}
