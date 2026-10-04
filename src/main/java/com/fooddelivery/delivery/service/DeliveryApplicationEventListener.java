package com.fooddelivery.delivery.service;
import com.fooddelivery.common.constants.*;
import com.fooddelivery.common.enums.ApplicationStatus;
import com.fooddelivery.common.event.*;
import com.fooddelivery.common.event.application.DeliveryApplicationStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor @Slf4j
public class DeliveryApplicationEventListener {
    private final EventBinder binder;
    private final DeliveryApplicationService applications;
    @KafkaListener(topics=KafkaConstants.TOPIC_DELIVERY_PARTNER_EVENTS,groupId=KafkaConstants.GROUP_FOOD_DELIVERY+"-delivery-application-checks")
    @RetryableTopic(attempts="5",backoff=@Backoff(delay=1000,multiplier=2),autoCreateTopics="true",dltStrategy=DltStrategy.FAIL_ON_ERROR,exclude=EventBindingException.class,traversingCauses="true")
    public void onEvent(@Payload String payload,@Header("eventType") String type) {
        binder.bindIf(EventType.DELIVERY_APPLICATION_STATUS_CHANGED,type,payload,DeliveryApplicationStatusChangedEvent.class)
                .filter(event -> event.status()==ApplicationStatus.SUBMITTED).ifPresent(event -> applications.processChecks(event.executiveId(),event.applicationVersion()));
    }
    @DltHandler public void deadLetter(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic,@Header(KafkaHeaders.RECEIVED_PARTITION) int partition,@Header(KafkaHeaders.OFFSET) long offset) {
        log.error("Delivery application checks exhausted retries replay={}",com.fooddelivery.common.util.KafkaHeaderUtils.deadLetterPosition(topic,partition,offset));
    }
}
