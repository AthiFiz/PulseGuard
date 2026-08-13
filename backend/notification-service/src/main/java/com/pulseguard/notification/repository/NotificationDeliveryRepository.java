package com.pulseguard.notification.repository;

import com.pulseguard.notification.domain.NotificationDelivery;
import com.pulseguard.notification.enums.NotificationDeliveryStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    /**
     * Deliveries waiting to be attempted, oldest first.
     *
     * <p>The status filter is what keeps a SENT message from ever being sent
     * again, and {@code nextAttemptAt} is what makes a retry wait rather than
     * spin. Limited so a large backlog cannot be pulled into memory at once.
     */
    List<NotificationDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            NotificationDeliveryStatus status, Instant dueBy, Limit limit);
}
