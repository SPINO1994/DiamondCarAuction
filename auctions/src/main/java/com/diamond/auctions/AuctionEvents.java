package com.diamond.auctions;

import com.diamond.shared.Outbox;
import java.time.OffsetDateTime;
import java.util.*;

@org.springframework.stereotype.Component
class AuctionEvents {
    private final Outbox outbox;

    AuctionEvents(Outbox outbox) { this.outbox = outbox; }

    void accepted(AuctionStore.Row previous, BidPricing.Result result, UUID bidder, long version,
                  OffsetDateTime endsAt, boolean publicChange, boolean extended) {
        if (publicChange) outbox.enqueue("BidPlaced", previous.id(), version, Map.of("price", result.price()));
        if (extended) outbox.enqueue("AuctionExtended", previous.id(), version, Map.of("endsAt", endsAt));
        if (previous.leaderId() != null && !previous.leaderId().equals(result.leader()))
            outbox.enqueue("BidderOutbid", previous.id(), version, Map.of("recipientId", previous.leaderId(), "price", result.price()));
        if (!bidder.equals(result.leader()))
            outbox.enqueue("BidderOutbid", previous.id(), version, Map.of("recipientId", bidder, "price", result.price()));
    }

    void ended(AuctionStore.Row auction, UUID winner, String outcome, String title, String currency) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("outcome", outcome);
        payload.put("title", title);
        payload.put("amount", auction.currentPrice());
        payload.put("currency", currency);
        if (winner != null) payload.put("winnerId", winner);
        outbox.enqueue("AuctionEnded", auction.id(), auction.version()+1, payload);
    }

    void cancelled(AuctionStore.Row auction, String reason, Collection<UUID> recipients) {
        outbox.enqueue("AuctionCancelled", auction.id(), auction.version()+1, Map.of("reason", reason, "recipients", recipients));
    }
}
