package com.fooddelivery.delivery.controller;

import com.fooddelivery.common.exception.GlobalExceptionHandler;
import com.fooddelivery.delivery.entity.OrderAssignment;
import com.fooddelivery.delivery.repository.OrderAssignmentRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;
import com.fooddelivery.delivery.service.OrderExecutionService;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A real JPA read must not retain the only JDBC connection for the SSE request lifetime. */
class RestaurantStatusStreamConnectionTest {
    private static final UUID ORDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DRIVER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final Path DEPLOYMENT_CONFIG = Path.of(System.getProperty(
            "delivery.deployment.config", "../Deployment/delivery-service.yml"));

    static Stream<Boolean> configurationVariants() {
        // A service-only checkout has no sibling Deployment repo. Always cover packaged defaults;
        // include the actual Config Server override in assembled builds. An explicit path must exist.
        if (System.getProperty("delivery.deployment.config") != null && !Files.isRegularFile(DEPLOYMENT_CONFIG))
            throw new IllegalStateException("Configured delivery deployment file does not exist: " + DEPLOYMENT_CONFIG);
        return Files.isRegularFile(DEPLOYMENT_CONFIG) ? Stream.of(false, true) : Stream.of(false);
    }

    private WebApplicationContextRunner application(boolean deployed) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, JacksonAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withUserConfiguration(StreamConfiguration.class)
                .withInitializer(context -> {
                    try {
                        var loader = new YamlPropertySourceLoader();
                        for (var source : loader.load("service", new ClassPathResource("application.yml")))
                            context.getEnvironment().getPropertySources().addLast(source);
                        if (deployed) for (var source : loader.load("deployed", new FileSystemResource(DEPLOYMENT_CONFIG)))
                            context.getEnvironment().getPropertySources().addBefore("service", source);
                    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                })
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:stream_" + UUID.randomUUID(),
                        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
                        "spring.datasource.password=", "spring.datasource.hikari.maximum-pool-size=1",
                        "spring.datasource.hikari.minimum-idle=1", "spring.datasource.hikari.connection-timeout=500",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect");
    }

    @ParameterizedTest(name = "{displayName}[deploymentConfig={0}]")
    @MethodSource("configurationVariants")
    void openStreamLeavesTheDatabaseAvailableForArrivalAndOrderReads(boolean deployed) {
        application(deployed).run(context -> {
            var jdbc = new JdbcTemplate(context.getBean(HikariDataSource.class));
            jdbc.update("INSERT INTO order_assignments(order_id,driver_id,state,assigned_at,version) VALUES (?,?,?,?,0)",
                    ORDER, DRIVER, "ASSIGNED", Instant.now());
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            MvcResult stream = mvc.perform(get(path(DRIVER))).andExpect(status().isOk())
                    .andExpect(request().asyncStarted()).andReturn();
            var pool = context.getBean(HikariDataSource.class).getHikariPoolMXBean();
            try {
                assertThat(pool.getActiveConnections()).as("JDBC connections held while the status stream remains open").isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_assignments", Integer.class)).isEqualTo(1);
                assertThat(jdbc.update("UPDATE order_assignments SET delivery_status='AT_RESTAURANT' WHERE order_id=?", ORDER)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT delivery_status FROM order_assignments WHERE order_id=?", String.class, ORDER))
                        .isEqualTo("AT_RESTAURANT");
            } finally {
                context.getBean(StreamHandle.class).emitter.get().complete();
                mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
            }
            verify(context.getBean(RedisMessageListenerContainer.class), atLeastOnce()).removeMessageListener(any(), any(org.springframework.data.redis.listener.ChannelTopic.class));
        });
    }

    @ParameterizedTest(name = "{displayName}[deploymentConfig={0}]")
    @MethodSource("configurationVariants")
    void anotherRiderCannotOpenTheOrdersStream(boolean deployed) {
        application(deployed).run(context -> {
            var jdbc = new JdbcTemplate(context.getBean(HikariDataSource.class));
            jdbc.update("INSERT INTO order_assignments(order_id,driver_id,state,assigned_at,version) VALUES (?,?,?,?,0)",
                    ORDER, DRIVER, "ASSIGNED", Instant.now());
            MockMvcBuilders.webAppContextSetup(context).build().perform(get(path(UUID.randomUUID())))
                    .andExpect(status().isForbidden());
            verify(context.getBean(RedisMessageListenerContainer.class), never()).addMessageListener(any(), any(org.springframework.data.redis.listener.ChannelTopic.class));
        });
    }

    @ParameterizedTest(name = "{displayName}[deploymentConfig={0}]")
    @MethodSource("configurationVariants")
    void aReleasedAssignmentCannotOpenTheStream(boolean deployed) {
        application(deployed).run(context -> {
            var jdbc = new JdbcTemplate(context.getBean(HikariDataSource.class));
            jdbc.update("INSERT INTO order_assignments(order_id,driver_id,state,assigned_at,version) VALUES (?,?,?,?,0)",
                    ORDER, DRIVER, "RELEASED", Instant.now());
            MockMvcBuilders.webAppContextSetup(context).build().perform(get(path(DRIVER)))
                    .andExpect(status().isForbidden());
            verify(context.getBean(RedisMessageListenerContainer.class), never()).addMessageListener(any(), any(org.springframework.data.redis.listener.ChannelTopic.class));
        });
    }

    private static String path(UUID rider) {
        return "/api/delivery/drivers/" + rider + "/orders/" + ORDER + "/restaurant-status-stream";
    }

    static class StreamHandle { final AtomicReference<SseEmitter> emitter = new AtomicReference<>(); }

    // Registered only through withUserConfiguration. Not @Configuration: this package is component-
    // scanned by OpenApiGenerationTest, which then found a second DeliveryExecutiveController bean.
    static class StreamConfiguration {
        @Bean PersistenceManagedTypes managedTypes() { return PersistenceManagedTypes.of(OrderAssignment.class.getName()); }
        @Bean OrderAssignmentRepository assignments(EntityManagerFactory factory) {
            return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory)).getRepository(OrderAssignmentRepository.class);
        }
        @Bean RedisMessageListenerContainer listeners() { return mock(RedisMessageListenerContainer.class); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean StreamHandle handle() { return new StreamHandle(); }
        @Bean DeliveryExecutiveController controller(OrderAssignmentRepository assignments,
                RedisMessageListenerContainer listeners, StreamHandle handle) {
            var controller = spy(new DeliveryExecutiveController(mock(DeliveryExecutiveProfileService.class),
                    mock(OrderAssignmentService.class), mock(OrderExecutionService.class), assignments,
                    mock(StringRedisTemplate.class), listeners));
            doAnswer(call -> {
                SseEmitter emitter = (SseEmitter) call.callRealMethod();
                handle.emitter.set(emitter);
                return emitter;
            }).when(controller).streamRestaurantStatus(any(), any());
            return controller;
        }
    }
}
