package com.diamond.orders;

import com.diamond.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Purchases {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Outbox outbox;
    private final Clock clock;

    Purchases(JdbcClient db, ObjectMapper json, Outbox outbox, Clock clock) {
        this.db = db;
        this.json = json;
        this.outbox = outbox;
        this.clock = clock;
    }

    record Order(UUID id, UUID auctionId, UUID buyerId, String title, BigDecimal amount, String currency,
                 String status, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    @RabbitListener(queues=Broker.ORDERS)
    @Transactional
    public void onAuction(byte[] message) throws Exception {
        var event = json.readValue(message, DomainEvent.class);
        if (event.schemaVersion() != 1 || !event.eventType().equals("AuctionEnded"))
            throw new IllegalArgumentException("Unsupported auction event");
        if (db.sql("insert into inbox(event_id) values (?) on conflict do nothing").param(event.eventId()).update() == 0) return;
        if (!event.text("outcome").equals("SOLD")) return;
        var id = UUID.randomUUID();
        var time = now();
        var buyer = event.uuid("winnerId");
        try (var ignored = MDC.putCloseable("correlationId", event.correlationId())) {
            int created = db.sql("""
                insert into purchase(id,auction_id,buyer_id,title,amount,currency,status,created_at,updated_at)
                values (?,?,?,?,?,?,'PENDING',?,?) on conflict(auction_id) do nothing
                """).params(id, event.aggregateId(), buyer, event.text("title"), event.data().required("amount").decimalValue(),
                    event.text("currency"), time, time).update();
            if (created > 0) outbox.enqueue("OrderCreated", id, 1, Map.of("recipientId", buyer, "auctionId", event.aggregateId(), "status", "PENDING"));
        }
    }

    @GetMapping("/api/orders")
    @PreAuthorize("hasRole('BUYER')")
    List<Order> list(@AuthenticationPrincipal Jwt user) {
        return db.sql("select * from purchase where buyer_id=? order by created_at desc")
            .param(buyer(user)).query(Order.class).list();
    }

    @GetMapping("/api/orders/{id}")
    @PreAuthorize("hasRole('BUYER')")
    Order detail(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { return owned(id, buyer(user), false); }

    @PostMapping("/api/orders/{id}/simulate-payment")
    @PreAuthorize("hasRole('BUYER')")
    @Transactional
    Order pay(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { return transition(id, buyer(user), "PAID"); }

    @PostMapping("/api/orders/{id}/cancel")
    @PreAuthorize("hasRole('BUYER')")
    @Transactional
    Order cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt user) { return transition(id, buyer(user), "CANCELLED"); }

    private Order transition(UUID id, UUID buyer, String status) {
        Order order = owned(id, buyer, true);
        if (order.status().equals(status)) return order;
        if (!order.status().equals("PENDING")) throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is already closed.");
        db.sql("update purchase set status=?,version=version+1,updated_at=? where id=?").params(status, now(), id).update();
        outbox.enqueue("OrderStatusChanged", id, order.version()+1, Map.of("recipientId", buyer, "auctionId", order.auctionId(), "status", status));
        return owned(id, buyer, false);
    }

    private Order owned(UUID id, UUID buyer, boolean lock) {
        return db.sql("select * from purchase where id=? and buyer_id=?" + (lock ? " for update" : ""))
            .params(id, buyer).query(Order.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order unavailable."));
    }

    private UUID buyer(Jwt user) { return UUID.fromString(user.getSubject()); }
    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
}
