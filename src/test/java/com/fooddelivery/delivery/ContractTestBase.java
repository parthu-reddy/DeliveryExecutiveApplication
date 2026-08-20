package com.fooddelivery.delivery;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import com.fooddelivery.delivery.controller.AdminDeliveryController;
import com.fooddelivery.delivery.controller.InternalDeliveryController;
import com.fooddelivery.delivery.entity.DeliveryExecutive;
import com.fooddelivery.delivery.enums.DeliveryExecutiveStatus;
import com.fooddelivery.delivery.repository.IDeliveryExecutiveRepository;
import com.fooddelivery.delivery.service.DeliveryExecutiveProfileService;
import com.fooddelivery.delivery.service.OrderAssignmentService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Base for the generated HTTP contract tests.
 *
 * RestAssuredMockMvc.standaloneSetup mounts ONLY the controllers handed to it. Previously just
 * InternalDeliveryController was mounted, so every contract for AdminDeliveryController returned 404
 * from an empty dispatcher -- which reads exactly like a missing endpoint and was misdiagnosed as one.
 */
public abstract class ContractTestBase {

    /** Concrete, because getDriversByIds.groovy asserts this id literally. */
    private static final UUID DRIVER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @BeforeEach
    public void setup() {
        DeliveryExecutiveProfileService profileService = Mockito.mock(DeliveryExecutiveProfileService.class);
        IDeliveryExecutiveRepository repository = Mockito.mock(IDeliveryExecutiveRepository.class);
        OrderAssignmentService orderAssignmentService = Mockito.mock(OrderAssignmentService.class);

        // The handlers return 404 / an empty list when the repository finds nothing, so a fixture is
        // required for the contracts to see 200. fullName and ONLINE are the real serialised field
        // and a real DeliveryExecutiveStatus value -- see the note in the contract files.
        DeliveryExecutive driver = new DeliveryExecutive();
        driver.setId(DRIVER_ID);
        driver.setFullName("Test Driver");
        driver.setStatus(DeliveryExecutiveStatus.ONLINE);

        Mockito.when(repository.findById(any(UUID.class))).thenReturn(Optional.of(driver));
        Mockito.when(repository.findAllById(anyList())).thenReturn(List.of(driver));

        InternalDeliveryController internalDeliveryController = new InternalDeliveryController(profileService);
        AdminDeliveryController adminDeliveryController =
                new AdminDeliveryController(repository, profileService, orderAssignmentService);

        RestAssuredMockMvc.standaloneSetup(internalDeliveryController, adminDeliveryController);
    }
}
