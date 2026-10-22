package com.diamond.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Outbox {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    Outbox(JdbcClient db, ObjectMapper json, Clock clock) { this.db = db; this.json = json; this.clock = clock; }

    public void enqueue(String type, UUID aggregate, long version, Object data) {
        var id = UUID.randomUUID();
        String correlation = Optional.ofNullable(MDC.get("correlationId")).orElse(id.toString());
        var event = new DomainEvent(id, type, 1, aggregate, version, clock.instant().atOffset(ZoneOffset.UTC),
            correlation, json.valueToTree(data));
        db.sql("insert into outbox(id,event_type,envelope) values (?,?,cast(? as jsonb))")
            .params(id, type, encode(event)).update();
    }

    private String encode(DomainEvent event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    record Pending(UUID id, String eventType, String envelope, int attempts) {}

    @Component
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="diamond.outbox.enabled", havingValue="true")
    static class Dispatcher {
        private final Logger log = LoggerFactory.getLogger(Dispatcher.class);
        private final JdbcClient db;
        private final RabbitTemplate rabbit;
        private final TransactionTemplate transactions;

        Dispatcher(JdbcClient db, RabbitTemplate rabbit, TransactionTemplate transactions) {
            this.db = db;
            this.rabbit = rabbit;
            this.transactions = transactions;
        }

        @Scheduled(fixedDelay=500)
        void dispatch() {
            for (int i = 0; i < 24; i++) {
                if (!Boolean.TRUE.equals(transactions.execute(status -> publishOne()))) return;
            }
        }

        private boolean publishOne() {
            var next = db.sql("""
                select id,event_type,envelope,attempts from outbox
                where published_at is null and failed_at is null and available_at<=now()
                order by available_at,id limit 1 for update skip locked
                """).query(Pending.class).optional();
            if (next.isEmpty()) return false;
            var event = next.get();
            var correlation = new CorrelationData(event.id().toString());
            try {
                var message = MessageBuilder.withBody(event.envelope().getBytes(StandardCharsets.UTF_8))
                    .setMessageId(event.id().toString()).setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT).build();
                rabbit.send(Broker.EVENTS, event.eventType(), message, correlation);
                var confirm = correlation.getFuture().get(4, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) throw new IllegalStateException("Event was not routed");
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                int attempt = event.attempts() + 1;
                db.sql("""
                    update outbox set attempts=?, available_at=now()+make_interval(secs=>?),
                    failed_at=case when ?>=16 then now() else null end where id=?
                    """).params(attempt, (double) Math.min(300, 1 << Math.min(attempt, 9)), attempt, event.id()).update();
                log.warn("Publish attempt {} failed for event {}", attempt, event.id());
                return false;
            }
            db.sql("update outbox set published_at=now() where id=?").param(event.id()).update();
            return true;
        }
    }
}
