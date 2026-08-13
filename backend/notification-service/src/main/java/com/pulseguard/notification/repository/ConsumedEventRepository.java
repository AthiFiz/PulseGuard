package com.pulseguard.notification.repository;

import com.pulseguard.notification.domain.ConsumedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, Long> {

    /**
     * The deduplication check.
     *
     * <p>A read-before-write, which on its own would race under concurrency —
     * the unique constraint on {@code event_id} is the real guarantee. This
     * only turns the common case into a cheap lookup instead of a failed
     * insert.
     */
    boolean existsByEventId(String eventId);
}
