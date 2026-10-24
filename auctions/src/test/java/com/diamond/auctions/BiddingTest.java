package com.diamond.auctions;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {
    "diamond.jwt-secret=integration-tests-have-a-long-local-key",
    "diamond.internal-secret=integration-key",
    "diamond.closure.delay=3600000",
    "diamond.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class BiddingTest {
    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1.4-management-alpine")
        .withUser("diamond", "integration-password", Set.of("administrator")).withPermission("/", "diamond", ".*", ".*", ".*");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.5-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        props.add("spring.datasource.username", POSTGRES::getUsername);
        props.add("spring.datasource.password", POSTGRES::getPassword);
        props.add("spring.rabbitmq.host", RABBIT::getHost);
        props.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        props.add("spring.rabbitmq.username", () -> "diamond");
        props.add("spring.rabbitmq.password", () -> "integration-password");
        props.add("spring.data.redis.host", REDIS::getHost);
        props.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired AuctionStore store;
    @Autowired Bidding bidding;
    @Autowired Closing closing;
    @Autowired TransactionTemplate tx;
    @Autowired TestClock clock;
    @Autowired MockMvc mvc;
    @Autowired RabbitTemplate rabbit;

    @BeforeEach void resetClock() { clock.time.set(START); }

    @Test void simultaneousEqualBidsCommitOnePrice() throws Exception {
        UUID id = auction(120, null);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i=0; i<12; i++) tasks.add(workers.submit(() -> {
                start.await();
                try { bid(id, UUID.randomUUID(), UUID.randomUUID(), 1000, false); return true; }
                catch (ResponseStatusException error) {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    return false;
                }
            }));
            start.countDown();
            int accepted = 0;
            for (var task : tasks) if (task.get(15, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(store.get(id, false).currentPrice()).isEqualByComparingTo("1000");
        assertThat(Bidding.state(store, id).bids()).hasSize(1);
    }

    @Test void duplicateRequestsReturnOriginalReceiptAndConflictsAreRejected() {
        UUID id = auction(120, null);
        UUID key = UUID.randomUUID();
        var first = bid(id, FIRST, key, 1000, false);
        bid(id, SECOND, UUID.randomUUID(), 1250, false);
        assertThat(bid(id, FIRST, key, 1000, false)).isEqualTo(first);
        assertThatThrownBy(() -> bid(id, FIRST, key, 1500, false)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.get(id, false).version()).isEqualTo(2);
    }

    @Test void automaticBidsUseIncrementTiePriorityAndPublicReserveState() {
        UUID id = auction(120, "3000");
        var initial = bid(id, FIRST, UUID.randomUUID(), 3200, true);
        assertThat(initial.price()).isEqualByComparingTo("1000");
        assertThat(store.view(store.get(id, false)).reserveMet()).isFalse();
        var challenge = bid(id, SECOND, UUID.randomUUID(), 1250, false);
        assertThat(challenge.price()).isEqualByComparingTo("1500");
        assertThat(challenge.leading()).isFalse();
        var tie = bid(id, SECOND, UUID.randomUUID(), 3200, true);
        assertThat(tie.price()).isEqualByComparingTo("3200");
        assertThat(store.get(id, false).leaderId()).isEqualTo(FIRST);
        assertThat(store.view(store.get(id, false)).reserveMet()).isTrue();
        assertThatThrownBy(() -> bid(id, FIRST, UUID.randomUUID(), 3200, true)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void extensionSurvivesCloserAndOneWinnerIsRecorded() throws Exception {
        UUID id = auction(20, "1000");
        var bid = bid(id, FIRST, UUID.randomUUID(), 1000, false);
        assertThat(bid.extended()).isTrue();
        assertThat(bid.endsAt().toInstant()).isEqualTo(START.plusSeconds(30));
        clock.time.set(START.plusSeconds(20));
        closing.finishDue();
        assertThat(store.get(id, false).status()).isEqualTo("APPROVED");
        clock.time.set(START.plusSeconds(30));
        try (var workers = Executors.newFixedThreadPool(3)) {
            var closer = workers.submit(closing::finishDue);
            var otherCloser = workers.submit(closing::finishDue);
            var late = workers.submit(() -> assertThatThrownBy(() ->
                bid(id, SECOND, UUID.randomUUID(), 1300, false)).isInstanceOf(ResponseStatusException.class));
            closer.get(10, TimeUnit.SECONDS);
            otherCloser.get(10, TimeUnit.SECONDS);
            late.get(10, TimeUnit.SECONDS);
        }
        var auction = store.get(id, false);
        assertThat(auction.status()).isEqualTo("SOLD");
        assertThat(auction.winnerId()).isEqualTo(FIRST);
        assertThat(store.db.sql("select count(*) from outbox where event_type='AuctionEnded' and envelope->>'aggregateId'=?")
            .param(id.toString()).query(Long.class).single()).isEqualTo(1);
        String queue = rabbit.execute(channel -> channel.queueDeclarePassive("diamond.orders").getQueue());
        assertThat(queue).isEqualTo("diamond.orders");
    }

    @Test void resolvesNoBidAndReserveNotMetSeparately() {
        UUID empty = auction(60, "2000");
        UUID belowReserve = auction(60, "2000");
        bid(belowReserve, FIRST, UUID.randomUUID(), 1300, false);
        clock.time.set(START.plusSeconds(60));
        closing.finishDue();
        assertThat(store.get(empty, false).status()).isEqualTo("NO_BIDS");
        assertThat(store.get(belowReserve, false).status()).isEqualTo("RESERVE_NOT_MET");
        assertThat(store.get(belowReserve, false).winnerId()).isNull();
    }

    @Test void enforcesRolesAndOwnership() throws Exception {
        UUID id = auction(120, null);
        mvc.perform(post("/api/auctions/{id}/bids", id).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auctions/{id}/bids", id).with(jwt().jwt(token -> token.subject(SELLER.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_SELLER"))).header("Idempotency-Key", UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/seller/auctions/{id}/cancel", id).with(jwt().jwt(token -> token.subject(FIRST.toString()))
            .authorities(new SimpleGrantedAuthority("ROLE_SELLER"))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Not the owner\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/auctions/{id}/state", id)).andExpect(status().isOk());
    }

    private Bidding.Receipt bid(UUID id, UUID buyer, UUID key, long amount, boolean auto) {
        return tx.execute(status -> bidding.accept(id, buyer, key, BigDecimal.valueOf(amount), auto));
    }

    private UUID auction(int seconds, String reserve) {
        UUID id = UUID.randomUUID();
        var now = store.now();
        var vehicle = new Listing("BMW", "M3", "E46", 2004, 65000, "mi", "Manual", "Gasoline",
            "Coupe", "Silver", "Black", "Portland", "Description", "Condition", "History", List.of());
        store.db.sql("""
            insert into auction(id,seller_id,seller_name,vehicle,starting_price,increment,reserve,starts_at,ends_at,status,current_price,created_at,updated_at)
            values (?,?,'Test Seller',cast(? as jsonb),1000,250,?,?,?,'APPROVED',1000,?,?)
            """).params(Arrays.asList(id, SELLER, store.encode(vehicle), reserve == null ? null : new BigDecimal(reserve),
                now.minusSeconds(3), now.plusSeconds(seconds), now, now)).update();
        return id;
    }

    @TestConfiguration
    static class Time {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> time = new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time.get(), zone); }
        @Override public Instant instant() { return time.get(); }
    }
}
