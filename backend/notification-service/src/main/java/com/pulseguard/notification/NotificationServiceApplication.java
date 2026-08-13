package com.pulseguard.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PulseGuard's third backend application: it consumes incident lifecycle events
 * from Kafka and turns them into email.
 *
 * <p>It creates no incidents and checks no endpoints. Its whole job begins
 * after something else has already decided that an outage happened.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
