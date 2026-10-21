package com.diamond.auctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Bidding {
    private final AuctionStore store;
    private final ObjectMapper json;
    private final LiveAuctions live;
    private final AuctionEvents events;

    Bidding(AuctionStore store, ObjectMapper json, LiveAuctions live, AuctionEvents events) {
        this.store = store;
        this.json = json;
        this.live = live;
        this.events = events;
    }

    record Amount(@NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal amount) {}
    record Receipt(long version, BigDecimal price, OffsetDateTime endsAt, boolean leading, boolean extended) {}
    record Request(String kind, BigDecimal amount, String receipt) {}
    record PublicBid(long version, String bidder, BigDecimal price, OffsetDateTime placedAt) {}
    record PublicState(AuctionStore.View auction, List<PublicBid> bids) {}
    record Position(AuctionStore.View auction, boolean leading, boolean won) {}

    @PostMapping("/api/auctions/{id}/bids")
    @PreAuthorize("hasRole('BUYER')")
    @Transactional
    Receipt manual(@PathVariable UUID id, @AuthenticationPrincipal Jwt user,
                   @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Amount body) {
        return accept(id, Listings.id(user), key, body.amount(), false);
    }

    @PostMapping("/api/auctions/{id}/max-bid")
    @PreAuthorize("hasRole('BUYER')")
    @Transactional
    Receipt maximum(@PathVariable UUID id, @AuthenticationPrincipal Jwt user,
                    @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Amount body) {
        return accept(id, Listings.id(user), key, body.amount(), true);
    }

    @GetMapping("/api/auctions/{id}/state")
    PublicState history(@PathVariable UUID id) { return state(store, id); }

    @GetMapping("/api/buyer/bids")
    @PreAuthorize("hasRole('BUYER')")
    List<Position> positions(@AuthenticationPrincipal Jwt user) {
        var buyer = Listings.id(user);
        return store.db.sql("""
            select a.* from auction a join standing_offer b on b.auction_id=a.id
            where b.bidder_id=? order by a.ends_at desc
            """).param(buyer).query(AuctionStore.Row.class).list().stream().map(a ->
                new Position(store.view(a), buyer.equals(a.leaderId()), buyer.equals(a.winnerId()))).toList();
    }

    Receipt accept(UUID id, UUID bidder, UUID key, BigDecimal amount, boolean automatic) {
        var auction = store.get(id, true);
        String kind = automatic ? "MAXIMUM" : "MANUAL";
        var previous = store.db.sql("select kind,amount,receipt from bid_request where auction_id=? and bidder_id=? and request_key=?")
            .params(id, bidder, key).query(Request.class).optional();
        if (previous.isPresent()) {
            var request = previous.get();
            if (!kind.equals(request.kind()) || amount.compareTo(request.amount()) != 0)
                throw AuctionStore.conflict("This idempotency key was already used for another bid.");
            return readReceipt(request.receipt());
        }

        var now = store.now();
        if (!auction.status().equals("APPROVED") || now.isBefore(auction.startsAt()) || !now.isBefore(auction.endsAt()))
            throw AuctionStore.conflict("Bidding is closed.");
        if (bidder.equals(auction.sellerId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sellers cannot bid on their own listing.");
        boolean leading = bidder.equals(auction.leaderId());
        BigDecimal minimum = auction.leaderId() == null || (leading && automatic)
            ? auction.currentPrice() : auction.currentPrice().add(auction.increment());
        if (amount.compareTo(minimum) < 0)
            throw AuctionStore.conflict("The amount is below the current minimum.");

        var ownOffer = store.db.sql("select bidder_id,amount,priority from standing_offer where auction_id=? and bidder_id=?")
            .params(id, bidder).query(BidPricing.Offer.class).optional();
        if (ownOffer.isPresent() && automatic && amount.compareTo(ownOffer.get().amount()) <= 0)
            throw AuctionStore.conflict("A maximum can only be increased.");
        long version = auction.version()+1;
        if (ownOffer.isEmpty() || amount.compareTo(ownOffer.get().amount()) > 0) {
            store.db.sql("""
                insert into standing_offer(auction_id,bidder_id,amount,priority) values (?,?,?,?)
                on conflict (auction_id,bidder_id) do update set amount=excluded.amount,priority=excluded.priority
                """).params(id, bidder, amount, version).update();
        }
        var top = store.db.sql("""
            select bidder_id,amount,priority from standing_offer where auction_id=?
            order by amount desc,priority limit 2
            """).param(id).query(BidPricing.Offer.class).list();
        var result = BidPricing.settle(top, automatic ? auction.currentPrice() : amount, auction.increment());
        // Compare the deadline after obtaining the same lock used by the closer.
        var end = auction.endsAt().isBefore(now.plusSeconds(30)) ? now.plusSeconds(30) : auction.endsAt();
        boolean extended = end.isAfter(auction.endsAt());
        boolean publicChange = !result.leader().equals(auction.leaderId()) || result.price().compareTo(auction.currentPrice()) != 0;
        store.db.sql("update auction set leader_id=?,current_price=?,ends_at=?,version=?,updated_at=? where id=?")
            .params(result.leader(), result.price(), end, version, now, id).update();
        if (publicChange) {
            store.db.sql("insert into public_bid(auction_id,version,bidder_id,price,placed_at) values (?,?,?,?,?)")
                .params(id, version, result.leader(), result.price(), now).update();
        }
        store.audit(id, bidder, "BID_ACCEPTED", null, version);
        events.accepted(auction, result, bidder, version, end, publicChange, extended);
        var receipt = new Receipt(version, result.price(), end, bidder.equals(result.leader()), extended);
        store.db.sql("insert into bid_request(auction_id,bidder_id,request_key,kind,amount,receipt) values (?,?,?,?,?,cast(? as jsonb))")
            .params(id, bidder, key, kind, amount, serialize(receipt)).update();
        live.afterCommit(id);
        return receipt;
    }

    static PublicState state(AuctionStore store, UUID id) {
        var row = store.get(id, false);
        if (!store.visible(row)) throw AuctionStore.missing();
        List<PublicBid> bids = store.db.sql("""
            select version,bidder_id,price,placed_at from public_bid where auction_id=? and version<=?
            order by version desc limit 50
            """).params(id, row.version()).query((rs, index) -> {
                String identity = id + ":" + rs.getObject("bidder_id", UUID.class);
                String alias = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
                return new PublicBid(rs.getLong("version"), "Bidder " + alias.toUpperCase(Locale.ROOT),
                    rs.getBigDecimal("price"), rs.getObject("placed_at", OffsetDateTime.class));
            }).list();
        return new PublicState(store.view(row), bids);
    }

    private Receipt readReceipt(String value) {
        try { return json.readValue(value, Receipt.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }

    private String serialize(Receipt receipt) {
        try { return json.writeValueAsString(receipt); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
