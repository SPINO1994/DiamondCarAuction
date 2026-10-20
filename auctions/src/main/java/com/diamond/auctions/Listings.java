package com.diamond.auctions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Validated
class Listings {
    private final AuctionStore store;
    private final Images images;

    Listings(AuctionStore store, Images images) { this.store = store; this.images = images; }

    @GetMapping("/api/auctions")
    AuctionStore.Page browse(@RequestParam(defaultValue="") @Size(max=100) String q,
        @RequestParam(defaultValue="") @Size(max=50) String make,
        @RequestParam(defaultValue="") @Size(max=40) String bodyStyle,
        @RequestParam(defaultValue="") @Pattern(regexp="|live|sold|upcoming") String status,
        @RequestParam(defaultValue="ending") @Pattern(regexp="ending|recent|price") String sort,
        @RequestParam(required=false) @DecimalMin("0") BigDecimal maxPrice,
        @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
        @RequestParam(defaultValue="9") @Min(1) @Max(24) int size) {
        var where = new StringBuilder(" where status not in ('DRAFT','REVIEW','REJECTED')");
        var params = new HashMap<String, Object>();
        if (!q.isBlank()) {
            where.append(" and strpos(lower(concat_ws(' ',vehicle->>'make',vehicle->>'model',vehicle->>'trim',vehicle->>'year',vehicle->>'location')), lower(:q)) > 0");
            params.put("q", q.strip());
        }
        if (!make.isBlank()) {
            where.append(" and lower(vehicle->>'make')=lower(:make)");
            params.put("make", make);
        }
        if (!bodyStyle.isBlank()) {
            where.append(" and lower(vehicle->>'bodyStyle')=lower(:body)");
            params.put("body", bodyStyle);
        }
        if (maxPrice != null) {
            where.append(" and current_price<=:maxPrice");
            params.put("maxPrice", maxPrice);
        }
        switch (status) {
            case "live" -> where.append(" and status='APPROVED' and starts_at<=:now and ends_at>:now");
            case "upcoming" -> where.append(" and status='APPROVED' and starts_at>:now");
            case "sold" -> where.append(" and status='SOLD'");
            default -> { }
        }
        if (where.indexOf(":now") >= 0) params.put("now", store.now());
        long total = store.db.sql("select count(*) from auction" + where).params(params).query(Long.class).single();
        String order = switch (sort) {
            case "price" -> "current_price";
            case "recent" -> "updated_at desc";
            default -> "ends_at";
        };
        params.put("limit", size);
        params.put("offset", page * size);
        var items = store.db.sql("select * from auction" + where + " order by " + order + ",id limit :limit offset :offset")
            .params(params).query(AuctionStore.Row.class).list().stream().map(store::view).toList();
        return new AuctionStore.Page(items, page, total, size);
    }

    @GetMapping("/api/auctions/{id}")
    AuctionStore.View detail(@PathVariable UUID id) {
        var row = store.get(id, false);
        if (!store.visible(row)) throw AuctionStore.missing();
        return store.view(row);
    }

    @GetMapping("/api/seller/auctions")
    @PreAuthorize("hasRole('SELLER')")
    List<AuctionStore.Managed> seller(@AuthenticationPrincipal Jwt user) {
        return store.db.sql("select * from auction where seller_id=? order by updated_at desc")
            .param(id(user)).query(AuctionStore.Row.class).list().stream().map(store::managed).toList();
    }

    @GetMapping("/api/admin/auctions")
    @PreAuthorize("hasRole('ADMIN')")
    List<AuctionStore.Managed> moderation() {
        return store.db.sql("select * from auction where status in ('REVIEW','APPROVED','CANCELLED') order by updated_at desc")
            .query(AuctionStore.Row.class).list().stream().map(store::managed).toList();
    }

    @PostMapping("/api/seller/auctions")
    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AuctionStore.Managed create(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Listing.Input body) {
        validate(body, id(user), null);
        UUID auction = UUID.randomUUID();
        var now = store.now();
        store.db.sql("""
            insert into auction(id,seller_id,seller_name,vehicle,starting_price,increment,reserve,starts_at,ends_at,status,current_price,created_at,updated_at)
            values (?,?,?,cast(? as jsonb),?,?,?,?,?,'DRAFT',?,?,?)
            """).params(Arrays.asList(auction, id(user), user.getClaimAsString("name"), store.encode(body.vehicle()),
                body.startingPrice(), body.increment(), body.reserve(), body.startsAt(), body.endsAt(), body.startingPrice(), now, now)).update();
        store.audit(auction, id(user), "CREATED", null, 0);
        return store.managed(store.get(auction, false));
    }

    @PutMapping("/api/seller/auctions/{id}")
    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    AuctionStore.Managed update(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody Listing.Input body) {
        var auction = owned(id, id(user));
        if (!Set.of("DRAFT", "REJECTED").contains(auction.status()))
            throw AuctionStore.conflict("Only drafts and rejected listings can be edited.");
        validate(body, id(user), store.decode(auction.vehicle()).images());
        store.db.sql("""
            update auction set vehicle=cast(? as jsonb),starting_price=?,increment=?,reserve=?,starts_at=?,ends_at=?,
            current_price=?,status='DRAFT',reason=null,version=version+1,updated_at=? where id=?
            """).params(Arrays.asList(store.encode(body.vehicle()), body.startingPrice(), body.increment(), body.reserve(),
                body.startsAt(), body.endsAt(), body.startingPrice(), store.now(), id)).update();
        store.audit(id, id(user), "EDITED", null, auction.version()+1);
        return store.managed(store.get(id, false));
    }

    @PostMapping("/api/seller/auctions/{id}/submit")
    @PreAuthorize("hasRole('SELLER')")
    @Transactional
    AuctionStore.Managed submit(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
        var auction = owned(id, id(user));
        if (!auction.status().equals("DRAFT")) throw AuctionStore.conflict("Only a draft can be submitted.");
        if (store.decode(auction.vehicle()).images().isEmpty())
            throw AuctionStore.conflict("Add a cover image before submitting.");
        ensureFuture(auction);
        return transition(auction, id(user), "REVIEW", null);
    }

    record Review(@Pattern(regexp="APPROVE|REJECT") @NotNull String decision, @Size(max=500) String reason) {}

    @PostMapping("/api/admin/auctions/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    AuctionStore.Managed review(@AuthenticationPrincipal Jwt user, @PathVariable UUID id, @Valid @RequestBody Review body) {
        var auction = store.get(id, true);
        if (!auction.status().equals("REVIEW")) throw AuctionStore.conflict("This listing is outside the review queue.");
        if (body.decision().equals("APPROVE")) {
            ensureFuture(auction);
            return transition(auction, id(user), "APPROVED", null);
        }
        if (body.reason() == null || body.reason().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for rejection.");
        return transition(auction, id(user), "REJECTED", body.reason().strip());
    }

    @GetMapping("/api/buyer/watchlist")
    @PreAuthorize("hasRole('BUYER')")
    List<AuctionStore.View> watches(@AuthenticationPrincipal Jwt user) {
        return store.db.sql("select a.* from auction a join watch w on w.auction_id=a.id where w.account_id=? order by a.ends_at")
            .param(id(user)).query(AuctionStore.Row.class).list().stream().map(store::view).toList();
    }

    @PutMapping("/api/buyer/watchlist/{id}")
    @PreAuthorize("hasRole('BUYER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void watch(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
        if (!store.visible(store.get(id, false))) throw AuctionStore.missing();
        store.db.sql("insert into watch(account_id,auction_id) values (?,?) on conflict do nothing").params(id(user), id).update();
    }

    @DeleteMapping("/api/buyer/watchlist/{id}")
    @PreAuthorize("hasRole('BUYER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unwatch(@AuthenticationPrincipal Jwt user, @PathVariable UUID id) {
        store.db.sql("delete from watch where account_id=? and auction_id=?").params(id(user), id).update();
    }

    private AuctionStore.Row owned(UUID auction, UUID owner) {
        var row = store.get(auction, true);
        if (!owner.equals(row.sellerId())) throw AuctionStore.missing();
        return row;
    }

    private void validate(Listing.Input input, UUID owner, List<String> retained) {
        if (!input.endsAt().isAfter(input.startsAt()) ||
            (input.reserve() != null && input.reserve().compareTo(input.startingPrice()) < 0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the schedule and reserve price.");
        if (new HashSet<>(input.vehicle().images()).size() != input.vehicle().images().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each gallery image must be unique.");
        for (String image : input.vehicle().images())
            if (retained == null || !retained.contains(image)) images.checkOwner(image, owner);
    }

    private void ensureFuture(AuctionStore.Row row) {
        if (!row.startsAt().isAfter(store.now()))
            throw AuctionStore.conflict("Choose a future start time.");
    }

    private AuctionStore.Managed transition(AuctionStore.Row row, UUID actor, String status, String reason) {
        store.db.sql("update auction set status=?,reason=?,version=version+1,updated_at=? where id=?")
            .params(Arrays.asList(status, reason, store.now(), row.id())).update();
        store.audit(row.id(), actor, status, reason, row.version()+1);
        return store.managed(store.get(row.id(), false));
    }

    static UUID id(Jwt user) { return UUID.fromString(user.getSubject()); }
}
