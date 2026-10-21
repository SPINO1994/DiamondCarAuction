package com.diamond.auctions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

final class BidPricing {
    private BidPricing() {}

    record Offer(UUID bidderId, BigDecimal amount, long priority) {}
    record Result(UUID leader, BigDecimal price) {}

    static Result settle(List<Offer> ranked, BigDecimal floor, BigDecimal increment) {
        var first = ranked.getFirst();
        if (ranked.size() == 1) return new Result(first.bidderId(), floor);
        BigDecimal competitive = ranked.get(1).amount().add(increment).min(first.amount());
        return new Result(first.bidderId(), floor.max(competitive));
    }
}
