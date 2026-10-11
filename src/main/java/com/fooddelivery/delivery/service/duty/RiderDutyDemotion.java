package com.fooddelivery.delivery.service.duty;

import com.fooddelivery.common.constants.RedisKeyConstants;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.enums.DutyChangeReason;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.RiderLiveness;
import com.fooddelivery.delivery.service.location.RiderLocationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Takes an idle rider off duty on the server's initiative: the stale-location sweeper and the location guard.
 *
 * <p>Only a rider who is ONLINE under the row lock is demoted. A rider carrying an order (ON_DELIVERY) never is: the
 * order would be stranded. Redis and the DUTY_STATUS push happen only after the commit.
 */
@Component
@RequiredArgsConstructor
public class RiderDutyDemotion {

    private final StringRedisTemplate redisTemplate;
    private final IDeliveryExecutiveRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final RiderDutyNotifier dutyNotifier;

    /**
     * @param stillDue re-checked under the lock with the locked row (e.g. "the ping is still stale")
     * @return the demoted rider, or null when they were not ONLINE or no longer due
     */
    public DeliveryExecutive demote(UUID driverId, Predicate<DeliveryExecutive> stillDue, DutyChangeReason reason) {
        DeliveryExecutive demoted = transactionTemplate.execute(status -> {
            DeliveryExecutive rider = repository.findLockedById(driverId).orElse(null);
            if (rider == null || rider.getStatus() != DeliveryExecutiveStatus.ONLINE || !stillDue.test(rider)) return null;
            rider.setStatus(DeliveryExecutiveStatus.OFFLINE);
            return repository.save(rider);
        });
        if (demoted == null) return null;
        String id = driverId.toString();
        if (demoted.getCityId() != null) {
            redisTemplate.opsForSet().remove(RedisKeyConstants.PREFIX_DRIVERS_AVAILABLE + demoted.getCityId(), id);
            redisTemplate.opsForGeo().remove("drivers:geo:" + demoted.getCityId(), id);
        }
        redisTemplate.opsForZSet().remove(RiderLiveness.LAST_PING_KEY, id);
        redisTemplate.opsForHash().put("drivers:status", id, DeliveryExecutiveStatus.OFFLINE.name());
        RiderLocationGuard.forgetKeys(redisTemplate, id);
        dutyNotifier.publish(driverId, DeliveryExecutiveStatus.OFFLINE, reason);
        return demoted;
    }
}
