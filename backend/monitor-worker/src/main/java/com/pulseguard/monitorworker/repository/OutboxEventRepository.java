package com.pulseguard.monitorworker.repository;

import com.pulseguard.monitorworker.domain.OutboxEvent;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Events still waiting to be published, oldest first.
     *
     * <p>Insertion order is publication order. Events for one monitor describe a
     * sequence — opened, then resolved — and sending them out of order would
     * tell a consumer the outage ended before it began.
     *
     * <p>Limited so a long Kafka outage cannot pull an unbounded backlog into
     * memory when the broker returns. No row locking: one publisher is assumed
     * at this stage, exactly as one worker is.
     */
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);
}
