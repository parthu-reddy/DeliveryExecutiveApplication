package com.fooddelivery.common.constants;

public final class KafkaConstants {
    private KafkaConstants() {}
    
    public static final String TOPIC_ORDER_EVENTS = "order-events";
    public static final String TOPIC_PAYMENT_EVENTS = "payment-events";
    public static final String TOPIC_NOTIFICATIONS_DISPATCH = "platform.notifications.dispatch";
    public static final String TOPIC_NOTIFICATIONS_DLQ = "notifications-dlq";
    public static final String TOPIC_LOGISTICS_DISPATCH = "platform.logistics.dispatch";
    public static final String GROUP_FOOD_DELIVERY = "food-delivery-group";
}
