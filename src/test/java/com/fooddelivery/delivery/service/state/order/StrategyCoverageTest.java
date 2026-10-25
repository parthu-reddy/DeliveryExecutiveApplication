package com.fooddelivery.delivery.service.state.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fooddelivery.common.enums.DeliveryStatus;
import com.fooddelivery.common.outbox.repository.OutboxEventRepository;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.LogisticsDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Every step a rider can confirm after accepting has its own strategy, registered as Spring registers it: the strategies
 * are found by the same component scan, not listed here. A step without one falls to DefaultDeliveryOrderStateStrategy,
 * which publishes a bare ORDER_STATUS_UPDATED -- for AT_DROP that would be a door arrival with no time, no geo check and
 * no consumer (DelayPenalties Phase 2).
 */
class StrategyCoverageTest {

    private DeliveryOrderStateFactory factory() throws Exception {
        // @Component only, as Spring's own scan registers them: a strategy class that lost its annotation is not a bean.
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(org.springframework.stereotype.Component.class));
        List<DeliveryOrderStateStrategy> strategies = new ArrayList<>();
        DefaultDeliveryOrderStateStrategy fallback = null;
        for (var candidate : scan.findCandidateComponents(DeliveryOrderStateStrategy.class.getPackageName())) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (!DeliveryOrderStateStrategy.class.isAssignableFrom(type)) {
                continue;
            }
            DeliveryOrderStateStrategy strategy = (DeliveryOrderStateStrategy) type.getConstructor(StringRedisTemplate.class,
                            ObjectMapper.class, TransactionTemplate.class, OutboxEventRepository.class, IDeliveryExecutiveRepository.class,
                            LogisticsDispatchService.class, OrderAssignmentRepository.class, Clock.class)
                    .newInstance(mock(StringRedisTemplate.class), new ObjectMapper(), mock(TransactionTemplate.class),
                            mock(OutboxEventRepository.class), mock(IDeliveryExecutiveRepository.class),
                            mock(LogisticsDispatchService.class), mock(OrderAssignmentRepository.class), Clock.systemUTC());
            strategies.add(strategy);
            if (strategy instanceof DefaultDeliveryOrderStateStrategy d) {
                fallback = d;
            }
        }
        assertNotNull(fallback, "the default strategy is a component too");
        return new DeliveryOrderStateFactory(strategies, fallback);
    }

    /** The steps after ASSIGNED, terminal ones included: each is something only the rider's app posts. */
    private static List<DeliveryStatus> riderSteps() {
        return Arrays.stream(DeliveryStatus.values())
                .filter(s -> s.getSequence() > DeliveryStatus.ASSIGNED.getSequence() && s != DeliveryStatus.CANCELLED)
                .toList();
    }

    @Test
    void everyRiderStepResolvesToItsOwnStrategy() throws Exception {
        DeliveryOrderStateFactory factory = factory();
        assertTrue(riderSteps().contains(DeliveryStatus.AT_DROP), riderSteps().toString());
        for (DeliveryStatus step : riderSteps()) {
            DeliveryOrderStateStrategy strategy = factory.getStrategy(step);
            assertFalse(strategy instanceof DefaultDeliveryOrderStateStrategy, step + " falls to the default strategy");
            assertEquals(step, strategy.getSupportedStatus());
        }
    }

    @Test
    void theDoorArrivalPublishesOrderAtDrop() throws Exception {
        assertEquals(com.fooddelivery.common.constants.EventType.ORDER_AT_DROP,
                ((AbstractDeliveryOrderState) factory().getStrategy(DeliveryStatus.AT_DROP)).getEventType());
    }
}
