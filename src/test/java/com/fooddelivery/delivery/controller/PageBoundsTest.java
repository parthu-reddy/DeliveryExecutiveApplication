package com.fooddelivery.delivery.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageBoundsTest {

    @Test
    void theLargestPageTheUiAsksForIsAllowed() {
        assertThat(PageBounds.of(0, 100).getPageSize()).isEqualTo(100);
        PageBounds.check(3, 1);
    }

    @ParameterizedTest
    @CsvSource({"0,101", "0,1000000", "0,0", "-1,20"})
    void outOfRangeIsABadRequest(int page, int size) {
        assertThatThrownBy(() -> PageBounds.check(page, size))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
