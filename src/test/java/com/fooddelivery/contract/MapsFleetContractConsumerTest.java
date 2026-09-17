package com.fooddelivery.contract;

import com.fooddelivery.common.client.MapsServiceClient;
import com.fooddelivery.common.dto.maps.SetAvailabilityRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.stubrunner.spring.AutoConfigureStubRunner;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins this service's two fleet calls against MapsIntegration's published stubs.
 *
 * <p>Both are on the driver-availability path and neither was contract-covered until now.
 * {@code releaseDriverLock} is the only way a driver returns to the pool after selection removed
 * them with an atomic SREM, and {@code reserveDriverLock} is how a failed decline undoes that
 * release. A silent break in either strands drivers or double-offers them, and nothing would go red.
 *
 * <p>What this does NOT prove: that {@code available=false} actually removes the driver from Redis.
 * MapsIntegration's contract base uses {@code standaloneSetup} with a mocked
 * {@code FleetTrackingService}, so the contract pins the HTTP shape only. The Redis semantics are
 * covered by {@code RejectRollbackReReservesTest} on this side and MapsIntegration's own tests.
 */
@ActiveProfiles("contract-test")
@SpringBootTest(classes = MapsFleetContractConsumerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureStubRunner(ids = {"com.fooddelivery:mapsintegration:+:stubs"})
public class MapsFleetContractConsumerTest {

    private static final String CITY = "BLR";
    private static final String DRIVER = "4f4a4e37-6ca5-5598-94f1-43ef1628f631";

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    @EnableFeignClients(basePackages = "com.fooddelivery.common.client")
    static class TestConfig {
        @Bean
        public com.fooddelivery.common.client.MapsServiceClientFallback mapsServiceClientFallback() {
            return org.mockito.Mockito.mock(com.fooddelivery.common.client.MapsServiceClientFallback.class);
        }
    }

    @Autowired
    private MapsServiceClient mapsServiceClient;

    private SetAvailabilityRequest request(boolean available) {
        SetAvailabilityRequest r = new SetAvailabilityRequest();
        r.setCityId(CITY);
        r.setDriverId(UUID.fromString(DRIVER).toString());
        r.setAvailable(available);
        return r;
    }

    @Test
    public void releaseDriverMatchesThePublishedContract() {
        Map<String, Object> response = mapsServiceClient.releaseDriver(request(true));

        assertThat(response).containsEntry("success", true);
    }

    /**
     * The re-reserve direction. This is the call that had no endpoint on the client at all until
     * 2026-09-17, and the reason it needs its own endpoint: {@code /api/fleet/release} requires
     * {@code available} to be present and then ignores it.
     */
    @Test
    public void setDriverAvailabilityFalseMatchesThePublishedContract() {
        Map<String, Object> response = mapsServiceClient.setDriverAvailability(request(false));

        assertThat(response).containsEntry("success", true);
    }
}
