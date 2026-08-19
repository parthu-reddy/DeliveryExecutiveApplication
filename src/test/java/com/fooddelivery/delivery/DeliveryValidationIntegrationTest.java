package com.fooddelivery.delivery;

import com.fooddelivery.common.test.BaseIntegrationTest;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("contract-test")
public class DeliveryValidationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private IDeliveryExecutiveRepository deliveryExecutiveRepository;

    
    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.data.redis.listener.RedisMessageListenerContainer redisMessageListenerContainer;

@Test
    void shouldThrowExceptionWhenDuplicateVehicleNumberIsSaved() {
        String duplicateVehicle = "AP09CC1234";
        
        DeliveryExecutive exec1 = new DeliveryExecutive();
        exec1.setId(UUID.randomUUID());
        exec1.setPhoneNumber("9999999999");
        exec1.setVehicleNumber(duplicateVehicle);
        exec1.setPhotoUrl("http://example.com/photo1.jpg");
        exec1.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);
        
        deliveryExecutiveRepository.saveAndFlush(exec1);

        // Try to create second delivery executive with same vehicle number
        DeliveryExecutive exec2 = new DeliveryExecutive();
        exec2.setId(UUID.randomUUID());
        exec2.setPhoneNumber("8888888888");
        exec2.setVehicleNumber(duplicateVehicle); // Same vehicle number
        exec2.setPhotoUrl("http://example.com/photo2.jpg");
        exec2.setStatus(com.fooddelivery.delivery.enums.DeliveryExecutiveStatus.ONLINE);

        // Expect DataIntegrityViolationException due to unique constraint
        assertThatThrownBy(() -> deliveryExecutiveRepository.saveAndFlush(exec2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
