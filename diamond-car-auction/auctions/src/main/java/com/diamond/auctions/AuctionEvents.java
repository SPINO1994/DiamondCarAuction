package com.diamond.auctions;

import java.time.OffsetDateTime;
import java.util.UUID;

@org.springframework.stereotype.Component
class AuctionEvents {
    void accepted(AuctionStore.Row previous, BidPricing.Result result, UUID bidder, long version,
                  OffsetDateTime endsAt, boolean publicChange, boolean extended) {
    }
}
