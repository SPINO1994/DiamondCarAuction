package com.diamond.auctions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
class DemoAuctions {
    private static final UUID SELLER = new UUID(0, 3);
    private static final UUID BUYER = new UUID(0, 1);
    private final AuctionStore store;

    DemoAuctions(AuctionStore store) { this.store = store; }

    @Bean ApplicationRunner seedMarket(TransactionTemplate transactions) {
        return args -> transactions.executeWithoutResult(status -> {
            var now = store.now();
            listing(new UUID(1, 1), porsche(), 92500, 100000, now.minusHours(2), now.plusHours(2));
            listing(new UUID(1, 2), bmw(), 23500, null, now.minusHours(3), now.plusHours(6));
            listing(new UUID(1, 3), defender(), 51500, 56000, now.minusHours(3), now.plusHours(12));
            UUID previous = new UUID(1, 4);
            if (listing(previous, bmw(), 18000, null, now.minusDays(1), now.minusMinutes(3)))
                seedBid(previous, 26250);
            UUID active = new UUID(1, 1);
            if (store.get(active, false).version() == 0) seedBid(active, 96000);
        });
    }

    record Quick(@Min(15) @Max(180) int seconds) {}

    @PostMapping("/api/admin/demo/quick-auction")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    AuctionStore.View quickAuction(@Valid @RequestBody Quick body) {
        UUID id = UUID.randomUUID();
        var now = store.now();
        listing(id, bmw(), 1000, 1000, now.minusSeconds(2), now.plusSeconds(body.seconds()));
        return store.view(store.get(id, false));
    }

    private boolean listing(UUID id, Listing vehicle, int start, Integer reserve, OffsetDateTime opens, OffsetDateTime closes) {
        int created = store.db.sql("""
            insert into auction(id,seller_id,seller_name,vehicle,starting_price,increment,reserve,starts_at,ends_at,status,current_price,created_at,updated_at)
            values (?,?,?,cast(? as jsonb),?,250,?,?,?,'APPROVED',?,?,?) on conflict (id) do nothing
            """).params(Arrays.asList(id, SELLER, "Sterling Motor Company", store.encode(vehicle), start,
                reserve, opens, closes, start, store.now(), store.now())).update();
        if (created > 0) store.audit(id, SELLER, "DEMO_SEEDED", null, 0);
        return created != 0;
    }

    private void seedBid(UUID auction, int price) {
        BigDecimal value = BigDecimal.valueOf(price);
        var time = store.now();
        store.db.sql("update auction set leader_id=?,current_price=?,version=1 where id=?").params(BUYER, value, auction).update();
        store.db.sql("insert into standing_offer(auction_id,bidder_id,amount,priority) values (?,?,?,1)").params(auction, BUYER, value).update();
        store.db.sql("insert into public_bid(auction_id,version,bidder_id,price,placed_at) values (?,1,?,?,?)")
            .params(auction, BUYER, value, time).update();
        store.audit(auction, BUYER, "BID_ACCEPTED", null, 1);
    }

    private static Listing porsche() {
        return new Listing("Porsche", "911 GT3", "Touring", 2022, 8400, "mi", "Manual", "Gasoline", "Coupe",
            "Guards Red", "Black", "Scottsdale, AZ",
            "A 992-generation GT3 Touring with a six-speed manual, a naturally aspirated 4.0-liter flat-six, and a discreet rear profile.",
            "The fictional inspection notes light wear on the driver's bolster and a small chip on the lower front lip.",
            "The fictional service file includes annual oil changes and a recent brake fluid flush.",
            List.of("/cars/porsche.jpg", "/cars/porsche-side.jpg"));
    }

    private static Listing bmw() {
        return new Listing("BMW", "M3", "E46", 2004, 72450, "mi", "Manual", "Gasoline", "Coupe",
            "Titanium Silver", "Black", "Portland, OR",
            "An E46 M3 coupe with the S54 inline-six, a six-speed manual and a restrained factory look.",
            "The fictional inspection notes normal front bumper wear and a clean interior with light creasing.",
            "The fictional records include a valve adjustment and a cooling-system service.", List.of("/cars/bmw.jpg"));
    }

    private static Listing defender() {
        return new Listing("Land Rover", "Defender", "110", 2023, 18200, "mi", "Automatic", "Gasoline", "SUV",
            "Santorini Black", "Ebony", "Denver, CO",
            "A five-door Defender 110 with a rugged exterior, a versatile load space and everyday road manners.",
            "The fictional inspection notes a light scuff on the cargo sill and even tire wear.",
            "The fictional service file shows routine maintenance and a recent inspection.", List.of("/cars/defender.jpg"));
    }
}
