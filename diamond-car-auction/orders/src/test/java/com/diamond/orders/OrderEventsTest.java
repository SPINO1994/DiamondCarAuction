package com.diamond.orders;

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

@SpringBootTest(properties = {
    "diamond.jwt-secret=integration-tests-have-a-long-local-key",
    "diamond.internal-secret=integration-key",
    "diamond.outbox.enabled=false"
})
@Testcontainers
class OrderEventsTest {
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

    @Test void deduplicatesRetriesAndCreatesOneOrderForAnAuction() throws Exception {
        UUID auction = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        var data = json.valueToTree(Map.of("outcome", "SOLD", "winnerId", buyer, "title", "2004 BMW M3",
            "amount", new java.math.BigDecimal("18750.55"), "currency", "USD"));
        var event = new DomainEvent(UUID.randomUUID(), "AuctionEnded", 1, auction, 4, OffsetDateTime.now(ZoneOffset.UTC), "test", data);
        var related = new DomainEvent(UUID.randomUUID(), "AuctionEnded", 1, auction, 3, event.timestamp(), "test", data);
        publish(event);
        publish(event);
        publish(related);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            assertThat(db.sql("select count(*) from inbox").query(Long.class).single()).isEqualTo(2L));
        assertThat(db.sql("select count(*) from purchase where auction_id=?").param(auction).query(Long.class).single()).isEqualTo(1L);
        assertThat(db.sql("select amount from purchase where auction_id=?").param(auction).query(java.math.BigDecimal.class).single())
            .isEqualByComparingTo("18750.55");
        assertThat(db.sql("select count(*) from outbox where event_type='OrderCreated'").query(Long.class).single()).isEqualTo(1L);
    }

    private void publish(DomainEvent event) throws Exception {
        rabbit.send(Broker.EVENTS, event.eventType(), MessageBuilder.withBody(json.writeValueAsBytes(event))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON).build());
    }
}
