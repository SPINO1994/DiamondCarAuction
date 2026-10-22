package com.diamond.auctions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
class Closing {
    private final AuctionStore store;
    private final LiveAuctions live;
    private final AuctionEvents events;
    private final TransactionTemplate transactions;

    Closing(AuctionStore store, LiveAuctions live, AuctionEvents events, TransactionTemplate transactions) {
        this.store = store;
        this.live = live;
        this.events = events;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelay=700)
    void finishDue() {
        for (int i = 0; i < 30; i++) {
            if (!Boolean.TRUE.equals(transactions.execute(status -> closeNext()))) return;
        }
    }

    private boolean closeNext() {
        var time = store.now();
        var due = store.db.sql("""
            select * from auction where status='APPROVED' and ends_at <= ?
            order by ends_at,id limit 1 for update skip locked
            """).param(time).query(AuctionStore.Row.class).optional();
        if (due.isEmpty()) return false;
        var auction = due.get();
        var view = store.view(auction);
        String outcome = auction.leaderId() == null ? "NO_BIDS" : view.reserveMet() ? "SOLD" : "RESERVE_NOT_MET";
        UUID winner = outcome.equals("SOLD") ? auction.leaderId() : null;
        store.db.sql("update auction set status=?,winner_id=?,updated_at=?,version=version+1 where id=?")
            .params(Arrays.asList(outcome, winner, time, auction.id())).update();
        store.audit(auction.id(), null, outcome, null, auction.version()+1);
        var vehicle = view.vehicle();
        events.ended(auction, winner, outcome, vehicle.year() + " " + vehicle.make() + " " + vehicle.model(), view.currency());
        live.afterCommit(auction.id());
        return true;
    }

    record Reason(@NotBlank @Size(max=500) String reason) {}

    @PostMapping("/api/admin/auctions/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    AuctionStore.Managed adminCancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt user, @Valid @RequestBody Reason body) {
        var auction = store.get(id, true);
        if (!auction.status().equals("APPROVED") || !auction.endsAt().isAfter(store.now()))
            throw AuctionStore.conflict("Only a scheduled or live auction can be cancelled.");
        return cancel(auction, Listings.id(user), body.reason().strip());
    }

    @PostMapping("/api/seller/auctions/{id}/cancel")
    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    AuctionStore.Managed sellerCancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt user, @Valid @RequestBody Reason body) {
        var auction = store.get(id, true);
        if (!auction.sellerId().equals(Listings.id(user))) throw AuctionStore.missing();
        if (!auction.status().equals("APPROVED") || !auction.endsAt().isAfter(store.now()) || auction.leaderId() != null)
            throw AuctionStore.conflict("Only a scheduled or live auction without bids can be cancelled.");
        return cancel(auction, Listings.id(user), body.reason().strip());
    }

    private AuctionStore.Managed cancel(AuctionStore.Row auction, UUID actor, String reason) {
        store.db.sql("update auction set status='CANCELLED',reason=?,version=version+1,updated_at=? where id=?")
            .params(reason, store.now(), auction.id()).update();
        store.audit(auction.id(), actor, "CANCELLED", reason, auction.version()+1);
        var recipients = new HashSet<>(store.db.sql("select bidder_id from standing_offer where auction_id=?")
            .param(auction.id()).query(UUID.class).list());
        recipients.addAll(store.db.sql("select account_id from watch where auction_id=?")
            .param(auction.id()).query(UUID.class).list());
        recipients.add(auction.sellerId());
        events.cancelled(auction, reason, recipients);
        live.afterCommit(auction.id());
        return store.managed(store.get(auction.id(), false));
    }
}
