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
}
