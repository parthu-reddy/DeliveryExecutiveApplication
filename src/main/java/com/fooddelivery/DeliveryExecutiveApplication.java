package com.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.fooddelivery.common.outbox.config.EnableOutbox;

import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.fooddelivery", "com.fooddelivery.common"})

@EnableScheduling
@EnableOutbox
@EnableFeignClients
public class DeliveryExecutiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeliveryExecutiveApplication.class, args);
    }
}
