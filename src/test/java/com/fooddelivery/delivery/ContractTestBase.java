package com.fooddelivery.delivery;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;


import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.controller.InternalDeliveryController;

public abstract class ContractTestBase {

    @BeforeEach
    public void setup() {

        DeliveryExecutiveProfileService profileService = Mockito.mock(DeliveryExecutiveProfileService.class);
        InternalDeliveryController controller = new InternalDeliveryController(profileService);
        RestAssuredMockMvc.standaloneSetup(controller);
        
    }
}
