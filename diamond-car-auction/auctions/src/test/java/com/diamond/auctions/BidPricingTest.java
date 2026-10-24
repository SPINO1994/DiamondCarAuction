package com.diamond.auctions;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BidPricingTest {
    @Test void honorsTheWinnerLimitWhenTheIncrementWouldExceedIt() {
        UUID earlier = UUID.randomUUID();
        var result = BidPricing.settle(List.of(
            new BidPricing.Offer(earlier, new BigDecimal("100.05"), 1),
            new BidPricing.Offer(UUID.randomUUID(), new BigDecimal("99.99"), 2)),
            new BigDecimal("90.00"), new BigDecimal("0.10"));
        assertThat(result.leader()).isEqualTo(earlier);
        assertThat(result.price()).isEqualByComparingTo("100.05");
    }
}
