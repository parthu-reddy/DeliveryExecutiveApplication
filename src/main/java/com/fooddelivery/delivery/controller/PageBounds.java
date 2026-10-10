package com.fooddelivery.delivery.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The page a client may ask for. An unbounded {@code size} let one call read every row the caller can see
 * ({@code size=1000000}); the UI never asks for more than 100. Out of range is a 400.
 */
public final class PageBounds {
    public static final int MAX_SIZE = 100;

    private PageBounds() {
    }

    public static PageRequest of(int page, int size) {
        check(page, size);
        return PageRequest.of(page, size);
    }

    public static void check(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size between 1 and " + MAX_SIZE);
        }
    }
}
