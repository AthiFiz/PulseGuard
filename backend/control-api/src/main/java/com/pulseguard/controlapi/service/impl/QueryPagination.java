package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.exception.ApiException;

/**
 * The pagination rules shared by every paged reporting endpoint.
 *
 * <p>Kept in one place so check history and incident history cannot drift into
 * different limits or different wording for the same mistake.
 */
final class QueryPagination {

    /**
     * Rows a single page may request.
     *
     * <p>These tables grow by one row per check and one per outage forever, so
     * an unbounded page is an unbounded query.
     */
    static final int MAX_PAGE_SIZE = 100;

    private QueryPagination() {
    }

    /**
     * Page numbers and sizes are rejected rather than clamped.
     *
     * <p>Silently turning {@code size=100000} into 100 would leave a client
     * convinced it had received everything.
     */
    static void validate(int page, int size) {
        if (page < 0) {
            throw ApiException.monitoringQueryInvalid("'page' must not be negative");
        }
        if (size < 1) {
            throw ApiException.monitoringQueryInvalid("'size' must be at least 1");
        }
        if (size > MAX_PAGE_SIZE) {
            throw ApiException.monitoringQueryInvalid(
                    "'size' must not exceed %d".formatted(MAX_PAGE_SIZE));
        }
    }
}
