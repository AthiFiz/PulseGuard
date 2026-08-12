package com.pulseguard.controlapi.dto.monitoring;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A stable pagination envelope.
 *
 * <p>Spring's {@code Page} is deliberately not returned directly. Its JSON shape
 * is an implementation detail of the framework — it has changed between Spring
 * versions and carries internal fields like {@code pageable} and {@code sort}
 * that clients would start depending on. This exposes only what a caller needs.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
