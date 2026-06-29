package com.fooddelivery.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.constants.KafkaConstants;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import com.fooddelivery.order.entity.Order;
import com.fooddelivery.order.entity.OutboxEventEntity;
import com.fooddelivery.order.entity.PaymentIntent;
import com.fooddelivery.order.enums.OrderStatus;
import com.fooddelivery.order.repository.IOrderRepository;
import com.fooddelivery.order.repository.IOutboxEventRepository;
import com.fooddelivery.order.repository.IPaymentIntentRepository;
import com.fooddelivery.restaurant.entity.Restaurant;
import com.fooddelivery.restaurant.repository.IRestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    @Mock
    private IOrderRepository orderRepository;

    @Mock
    private IOutboxEventRepository outboxEventRepository;

    @Mock
    private IRestaurantRepository restaurantRepository;

    @Mock
    private IPaymentIntentRepository paymentIntentRepository;

    @Mock
    private LogisticsDispatchService logisticsDispatchService;

    @Mock
    private DoubleEntryLedgerService ledgerService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private ObjectMapper objectMapper = new ObjectMapper();

    private OrderSagaOrchestrator orderSagaOrchestrator;

    @BeforeEach
    void setUp() {
        orderSagaOrchestrator = new OrderSagaOrchestrator(
                orderRepository,
                outboxEventRepository,
                restaurantRepository,
                paymentIntentRepository,
                objectMapper,
                kafkaTemplate,
                logisticsDispatchService,
                ledgerService
        );
    }

    @Test
    void startOrderSaga_ShouldSaveOrderAndOutboxEvent() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).totalAmount(new BigDecimal("100.00")).build();

        when(orderRepository.save(order)).thenReturn(order);

        Order savedOrder = orderSagaOrchestrator.startOrderSaga(order);

        assertThat(savedOrder).isNotNull();
        verify(orderRepository).save(order);
        
        ArgumentCaptor<OutboxEventEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        
        OutboxEventEntity savedOutbox = outboxCaptor.getValue();
        assertThat(savedOutbox.getEventType()).isEqualTo("OrderCreated");
        assertThat(savedOutbox.getAggregateType()).isEqualTo("Order");
    }

    @Test
    void handlePaymentSuccess_ShouldUpdateOrderToPaid() throws Exception {
        String gatewayOrderId = "pay_123";
        UUID internalOrderId = UUID.randomUUID();
        
        String payload = """
                {
                    "event": "payment.success",
                    "payload": {
                        "payment": {
                            "entity": {
                                "order_id": "pay_123",
                                "status": "captured",
                                "amount": 10000
                            }
                        }
                    }
                }
                """;

        PaymentIntent intent = new PaymentIntent();
        intent.setId(UUID.randomUUID());
        intent.setInternalOrderId(internalOrderId);
        intent.setStatus("CREATED");

        Order order = Order.builder().id(internalOrderId).status(OrderStatus.CREATED).build();

        when(paymentIntentRepository.findByGatewayOrderId(gatewayOrderId)).thenReturn(Optional.of(intent));
        when(orderRepository.findById(internalOrderId)).thenReturn(Optional.of(order));

        orderSagaOrchestrator.handlePaymentSuccess(payload);

        verify(orderRepository).save(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        
        verify(paymentIntentRepository).save(intent);
        assertThat(intent.getStatus()).isEqualTo("SUCCESS");

        verify(outboxEventRepository).save(any(OutboxEventEntity.class));
    }

    @Test
    void handleOrderEvents_ShouldDispatchDriver_WhenOrderAccepted() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).customerId(customerId).restaurantId(restaurantId).status(OrderStatus.ACCEPTED).build();

        String payload = String.format("{\"orderId\": \"%s\"}", orderId.toString());

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        
        GeometryFactory gf = new GeometryFactory();
        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);
        restaurant.setLocation(gf.createPoint(new Coordinate(77.5946, 12.9716)));
        
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        
        doNothing().when(logisticsDispatchService).dispatchNearestDriver(12.9716, 77.5946, orderId);

        orderSagaOrchestrator.handleOrderEvents(payload, "ORDER_ACCEPTED");

        // The status remains ACCEPTED and no driver is assigned until the async reply comes back
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        verify(logisticsDispatchService).dispatchNearestDriver(12.9716, 77.5946, orderId);
        // We no longer update the order status or send a notification in this flow synchronously
        verify(orderRepository, never()).save(order);
        verify(kafkaTemplate, never()).send(eq(KafkaConstants.TOPIC_NOTIFICATIONS_DISPATCH), anyString(), anyString());
    }
}
