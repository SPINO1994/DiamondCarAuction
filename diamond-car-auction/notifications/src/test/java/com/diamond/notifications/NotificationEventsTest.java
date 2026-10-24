package com.diamond.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import com.diamond.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

@SpringBootTest(properties = {
    "diamond.jwt-secret=integration-tests-have-a-long-local-key",
    "diamond.internal-secret=integration-key",
    "diamond.email.enabled=false"
})
@Testcontainers
class NotificationEventsTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.4-management-alpine")
        .withUser("diamond", "integration-password", Set.of("administrator")).withPermission("/", "diamond", ".*", ".*", ".*");

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        props.add("spring.datasource.username", POSTGRES::getUsername);
        props.add("spring.datasource.password", POSTGRES::getPassword);
        props.add("spring.rabbitmq.host", RABBIT::getHost);
        props.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        props.add("spring.rabbitmq.username", () -> "diamond");
        props.add("spring.rabbitmq.password", () -> "integration-password");
    }

    @Autowired JdbcClient db;
    @Autowired ObjectMapper json;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;

    @Test void queuesDuringDowntimeAndIgnoresDuplicateAndReorderedOrderEvents() throws Exception {
        var recipient = UUID.randomUUID();
        var order = UUID.randomUUID();
        var timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        var paid = new DomainEvent(UUID.randomUUID(), "OrderStatusChanged", 1, order, 2, timestamp, "test",
            json.valueToTree(Map.of("recipientId", recipient, "status", "PAID")));
        var pending = new DomainEvent(UUID.randomUUID(), "OrderCreated", 1, order, 1, timestamp.minusSeconds(1), "test",
            json.valueToTree(Map.of("recipientId", recipient, "status", "PENDING")));
        listeners.stop();
        try {
            publish(paid);
            publish(paid);
            publish(pending);
            assertThat(db.sql("select count(*) from notice").query(Long.class).single()).isZero();
        } finally { listeners.start(); }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(db.sql("select count(*) from inbox").query(Long.class).single()).isEqualTo(2L));
        assertThat(db.sql("select title from notice where recipient_id=?").param(recipient).query(String.class).list())
            .containsExactly("Simulated payment complete");
    }

    private void publish(DomainEvent event) throws Exception {
        rabbit.send(Broker.EVENTS, event.eventType(), MessageBuilder.withBody(json.writeValueAsBytes(event))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
    }
}
