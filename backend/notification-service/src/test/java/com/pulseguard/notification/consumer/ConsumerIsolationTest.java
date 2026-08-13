package com.pulseguard.notification.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSender;

/**
 * The separation that makes a mail outage harmless.
 *
 * <p>If either of these classes could send email, a broken SMTP host would
 * throw inside the Kafka listener, the offset would never be committed, and the
 * same incident would be reprocessed until the mail server came back. Asserting
 * on the dependency structure is cheap and states the intent where it can be
 * broken.
 */
class ConsumerIsolationTest {

    @Test
    void theKafkaListenerCannotSendEmail() {
        assertNoMailSender(IncidentEventConsumer.class);
    }

    @Test
    void theEventProcessorCannotSendEmail() {
        assertNoMailSender(NotificationEventProcessor.class);
    }

    private static void assertNoMailSender(Class<?> type) {
        assertThat(Arrays.stream(type.getDeclaredFields()).map(Field::getType))
                .as("%s must not depend on a mail sender", type.getSimpleName())
                .noneMatch(MailSender.class::isAssignableFrom);
    }
}
