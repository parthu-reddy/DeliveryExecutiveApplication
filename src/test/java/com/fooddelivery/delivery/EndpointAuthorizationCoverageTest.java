package com.fooddelivery.delivery;

import com.fooddelivery.common.test.EndpointAuthorizationCoverage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every HTTP endpoint in this module must carry an authorization rule, at class or method level.
 *
 * <p>Closes gap G-6 of the 2026-08-22 core services review. At the time of that review 26 of 150
 * endpoints across the platform had no authorization of any kind -- including an unauthenticated
 * KYC-approval callback and an order-invoice endpoint that returned both delivery OTPs. Those were
 * fixed; nothing stopped the next endpoint from arriving bare. This does.
 *
 * <p>Needs no Spring context, no database and no broker: it is a reflective scan over the compiled
 * controllers, so it cannot be skipped by an infrastructure failure.
 */
class EndpointAuthorizationCoverageTest {

    private static final String BASE_PACKAGE = "com.fooddelivery";

    /** Floor, not an exact count: adding endpoints must not break the build. */
    private static final int MINIMUM_EXPECTED_ENDPOINTS = 5;

    /**
     * Endpoints that are intentionally anonymous. Adding one here is a deliberate, reviewable act;
     * forgetting {@code @PreAuthorize} is not.
     */
    private static final Set<String> INTENTIONALLY_ANONYMOUS = Set.of();

    /**
     * A scan that discovers no controllers would report "nothing unprotected" and pass while
     * guarding nothing -- the vacuous-test failure this review recorded as I-17. Assert the scan
     * actually found endpoints before trusting what it says about them.
     */
    @Test
    void theScanActuallyFindsEndpoints() {
        assertThat(EndpointAuthorizationCoverage.countEndpoints(BASE_PACKAGE))
                .describedAs("endpoints discovered under " + BASE_PACKAGE)
                .isGreaterThan(MINIMUM_EXPECTED_ENDPOINTS);
    }

    @Test
    void everyEndpointCarriesAnAuthorizationRule() {
        List<EndpointAuthorizationCoverage.Unprotected> unprotected =
                EndpointAuthorizationCoverage.scan(BASE_PACKAGE, INTENTIONALLY_ANONYMOUS);

        assertThat(unprotected)
                .describedAs("Endpoints with no @PreAuthorize/@Secured/@RolesAllowed at class or "
                        + "method level. Add an authorization rule, or -- if the endpoint really is "
                        + "public -- add it to INTENTIONALLY_ANONYMOUS with a comment saying why.")
                .isEmpty();
    }

    @Test
    void theAllowlistHasNoStaleEntries() {
        List<String> stale =
                EndpointAuthorizationCoverage.staleAllowlistEntries(BASE_PACKAGE, INTENTIONALLY_ANONYMOUS);

        assertThat(stale)
                .describedAs("Allowlist entries matching no endpoint. A stale entry exempts nothing "
                        + "today but will silently exempt the next method that takes that name.")
                .isEmpty();
    }

    /**
     * Multi-resource endpoints whose authorization rule does not itself name every id.
     *
     * <p>Each entry was read this session and the binding confirmed to exist somewhere the
     * reflective scan cannot see -- in the method body, in the repository query, or in a Redis
     * claim. The value of the list is not the exemptions: it is that a NEW endpoint taking an owned
     * tenant id and an unowned resource id arrives red, which is exactly how
     * {@code FulfillmentController} shipped a cross-tenant write past a green build.
     */
    private static final Set<String> RESOURCE_BINDING_VERIFIED_ELSEWHERE = Set.of(
            // acceptOrderPing claims the order through a Redis SISMEMBER on the ping set: a driver who was
            // not pinged gets INVALID.
            "DeliveryExecutiveController#acceptOrder",
            // rejectOrderPing is scoped to this driver's own pending ping.
            "DeliveryExecutiveController#rejectOrder",
            // OrderExecutionService.abortOrder denies unless order_assignments authorises this driver.
            "DeliveryExecutiveController#abortOrder",
            // AbstractDeliveryOrderState.handleStatusUpdate denies unless order_assignments authorises.
            "DeliveryExecutiveController#updateOrderStatus",
            // The handler denies unless order_assignments authorises this driver (added in Phase 2 --
            // this endpoint was genuinely unbound and the scan is what found it).
            "DeliveryExecutiveController#streamRestaurantStatus"
);

    @Test
    void everyMultiResourceEndpointBindsItsResource() {
        List<EndpointAuthorizationCoverage.UnboundResource> unbound =
                EndpointAuthorizationCoverage.unboundResourceParameters(
                        BASE_PACKAGE, RESOURCE_BINDING_VERIFIED_ELSEWHERE);

        assertThat(unbound)
                .describedAs("Endpoints taking two or more resource ids whose authorization rule "
                        + "constrains only some of them. Bind the resource -- preferably in the "
                        + "query, so it cannot be forgotten on the next method -- or add it to "
                        + "RESOURCE_BINDING_VERIFIED_ELSEWHERE with the place the binding lives.")
                .isEmpty();
    }

    @Test
    void theBindingScanActuallyFindsMultiResourceEndpoints() {
        // Without this the exemption list could grow to cover everything and the scan above would
        // pass while guarding nothing -- the vacuous-test failure recorded as I-17.
        assertThat(EndpointAuthorizationCoverage.countMultiResourceEndpoints(BASE_PACKAGE))
                .describedAs("multi-resource endpoints discovered under " + BASE_PACKAGE)
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void theBindingAllowlistHasNoStaleEntries() {
        assertThat(EndpointAuthorizationCoverage.staleBindingAllowlistEntries(
                        BASE_PACKAGE, RESOURCE_BINDING_VERIFIED_ELSEWHERE))
                .describedAs("Binding-allowlist entries matching no multi-resource endpoint.")
                .isEmpty();
    }
}
