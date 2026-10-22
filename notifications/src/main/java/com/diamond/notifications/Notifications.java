package com.diamond.notifications;

import com.diamond.shared.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.OffsetDateTime;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Notifications {
    private final JdbcClient db;
    private final ObjectMapper json;

    Notifications(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

    record Notice(UUID id, String title, String body, String link, OffsetDateTime createdAt, OffsetDateTime readAt) {}

    @RabbitListener(queues=Broker.NOTIFICATIONS)
    @Transactional
    public void onEvent(byte[] message) throws Exception {
        var event = json.readValue(message, DomainEvent.class);
        if (event.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported event version");
        if (db.sql("insert into inbox(event_id) values (?) on conflict do nothing").param(event.eventId()).update() == 0) return;
        String auction = "/auctions/" + event.aggregateId();
        switch (event.eventType()) {
            case "BidderOutbid" -> save(event, event.uuid("recipientId"), "BID", "You were outbid",
                "Another bidder is leading. View the auction for the latest price.", auction);
            case "AuctionEnded" -> {
                if (event.text("outcome").equals("SOLD"))
                    save(event, event.uuid("winnerId"), "RESULT", "You won the auction",
                        event.text("title") + " is yours. Your order may take a moment to appear.", auction);
            }
            case "AuctionCancelled" -> {
                for (var recipient : event.data().required("recipients"))
                    save(event, UUID.fromString(recipient.asText()), "RESULT", "Auction cancelled", event.text("reason"), auction);
            }
            case "OrderCreated", "OrderStatusChanged" -> {
                String status = event.text("status");
                String title = switch (status) {
                    case "PENDING" -> "Your order is ready";
                    case "PAID" -> "Simulated payment complete";
                    case "CANCELLED" -> "Order cancelled";
                    default -> throw new IllegalArgumentException("Unsupported order status");
                };
                save(event, event.uuid("recipientId"), "ORDER", title,
                    "Open the order to review its details.", "/orders/" + event.aggregateId());
            }
            default -> throw new IllegalArgumentException("Unsupported event type");
        }
    }

    private void save(DomainEvent event, UUID recipient, String category, String title, String body, String link) {
        int updated = db.sql("""
            insert into notice_version(recipient_id,aggregate_id,category,version) values (?,?,?,?)
            on conflict(recipient_id,aggregate_id,category) do update set version=excluded.version
            where notice_version.version<excluded.version
            """).params(recipient, event.aggregateId(), category, event.aggregateVersion()).update();
        if (updated == 0) return;
        db.sql("insert into notice(id,recipient_id,event_id,title,body,link,created_at) values (?,?,?,?,?,?,?)")
            .params(UUID.randomUUID(), recipient, event.eventId(), title, body, link, event.timestamp()).update();
    }

    @GetMapping("/api/notifications")
    List<Notice> list(@AuthenticationPrincipal Jwt user) {
        return db.sql("""
            select id,title,body,link,created_at,read_at from notice where recipient_id=?
            order by created_at desc,id limit 100
            """).param(userId(user)).query(Notice.class).list();
    }

    record Read(boolean read) {}

    @PutMapping("/api/notifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @RequestBody Read body) {
        int updated = db.sql("update notice set read_at=case when ? then coalesce(read_at,now()) else null end where id=? and recipient_id=?")
            .params(body.read(), id, userId(user)).update();
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification unavailable.");
    }

    private UUID userId(Jwt user) { return UUID.fromString(user.getSubject()); }
}
