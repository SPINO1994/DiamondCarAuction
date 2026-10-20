package com.diamond.auctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class AuctionStore {
    final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;
    private final String currency;

    AuctionStore(JdbcClient db, ObjectMapper json, Clock clock, @Value("${diamond.currency}") String currency) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.currency = Currency.getInstance(currency).getCurrencyCode();
    }

    record Row(UUID id, UUID sellerId, String sellerName, String vehicle, BigDecimal startingPrice,
               BigDecimal increment, BigDecimal reserve, OffsetDateTime startsAt, OffsetDateTime endsAt,
               String status, String reason, UUID winnerId, UUID leaderId, BigDecimal currentPrice,
               long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    record View(UUID id, String seller, Listing vehicle, BigDecimal startingPrice, BigDecimal increment,
                OffsetDateTime startsAt, OffsetDateTime endsAt, String status, BigDecimal price, String currency,
                boolean reserveMet, boolean hasBids, long version, OffsetDateTime serverTime) {}
    record Managed(View auction, BigDecimal reserve, String reason) {}
    record Page(List<View> items, int page, long total, int size) {}

    OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }

    Row get(UUID id, boolean lock) {
        return db.sql("select * from auction where id=?" + (lock ? " for update" : ""))
            .param(id).query(Row.class).optional().orElseThrow(AuctionStore::missing);
    }

    View view(Row row) {
        var time = now();
        String status = row.status();
        if (status.equals("APPROVED"))
            status = !time.isBefore(row.endsAt()) ? "FINALIZING" : time.isBefore(row.startsAt()) ? "SCHEDULED" : "LIVE";
        return new View(row.id(), row.sellerName(), decode(row.vehicle()), row.startingPrice(), row.increment(),
            row.startsAt(), row.endsAt(), status, row.currentPrice(), currency,
            row.reserve() == null || row.currentPrice().compareTo(row.reserve()) >= 0,
            row.leaderId() != null, row.version(), time);
    }

    Managed managed(Row row) { return new Managed(view(row), row.reserve(), row.reason()); }

    boolean visible(Row row) { return !Set.of("DRAFT", "REJECTED", "REVIEW").contains(row.status()); }

    void audit(UUID id, UUID actor, String action, String reason, long version) {
        db.sql("insert into auction_audit(auction_id,actor_id,action,reason,version,occurred_at) values (?,?,?,?,?,?)")
            .params(Arrays.asList(id, actor, action, reason, version, now())).update();
    }

    String encode(Listing listing) {
        try { return json.writeValueAsString(listing); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    Listing decode(String listing) {
        try { return json.readValue(listing, Listing.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Auction unavailable.");
    }

    static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
