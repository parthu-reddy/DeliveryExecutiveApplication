package com.fooddelivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeliveryExecutiveApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeliveryExecutiveApplication.class, args);
    }
}
