package com.diamond.auctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
class LiveAuctions extends TextWebSocketHandler {
    private static final String CHANNEL = "diamond:auction";
    private final Map<UUID, Map<String, Subscription>> viewers = new ConcurrentHashMap<>();
    private final StringRedisTemplate redis;
    private final AuctionStore store;
    private final ObjectMapper json;

    LiveAuctions(StringRedisTemplate redis, AuctionStore store, ObjectMapper json) {
        this.redis = redis;
        this.store = store;
        this.json = json;
    }

    record Subscription(WebSocketSession session) {}

    void afterCommit(UUID id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.convertAndSend(CHANNEL, id.toString()); }
                catch (RuntimeException error) { LoggerFactory.getLogger(LiveAuctions.class).warn("Live publication unavailable"); }
            }
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        try {
            String[] segments = Objects.requireNonNull(session.getUri()).getPath().split("/");
            UUID id = UUID.fromString(segments[segments.length-1]);
            var state = Bidding.state(store, id);
            var safe = new ConcurrentWebSocketSessionDecorator(session, 3000, 128 * 1024);
            viewers.compute(id, (key, existing) -> {
                var subscriptions = existing == null ? new ConcurrentHashMap<String, Subscription>() : existing;
                subscriptions.put(session.getId(), new Subscription(safe));
                return subscriptions;
            });
            send(safe, state);
        } catch (RuntimeException error) { session.close(CloseStatus.POLICY_VIOLATION); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        viewers.keySet().forEach(id -> viewers.computeIfPresent(id, (key, subscriptions) -> {
            subscriptions.remove(session.getId());
            return subscriptions.isEmpty() ? null : subscriptions;
        }));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable error) throws IOException {
        session.close(CloseStatus.SERVER_ERROR);
    }

    void broadcast(UUID id) {
        var subscriptions = viewers.get(id);
        if (subscriptions == null || subscriptions.isEmpty()) return;
        try {
            var state = Bidding.state(store, id);
            for (var subscription : subscriptions.values()) send(subscription.session(), state);
        } catch (RuntimeException error) {
            LoggerFactory.getLogger(LiveAuctions.class).warn("Could not refresh auction {}", id);
        }
    }

    @Scheduled(fixedDelay=10000)
    void reconcile() {
        viewers.keySet().forEach(this::broadcast);
    }

    private void send(WebSocketSession session, Bidding.PublicState state) {
        try { session.sendMessage(new TextMessage(json.writeValueAsString(state))); }
        catch (IOException | RuntimeException error) {
            try { session.close(CloseStatus.SERVER_ERROR); }
            catch (IOException ignored) { }
        }
    }

    @Configuration
    @EnableWebSocket
    static class ConfigurationAdapter implements WebSocketConfigurer {
        private final LiveAuctions live;
        private final String origin;

        ConfigurationAdapter(LiveAuctions live, @Value("${diamond.origin}") String origin) {
            this.live = live;
            this.origin = origin;
        }

        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(live, "/live/auctions/*").setAllowedOrigins(origin);
        }

        @Bean RedisMessageListenerContainer auctionSubscriber(RedisConnectionFactory factory) {
            var subscriber = new RedisMessageListenerContainer();
            subscriber.setConnectionFactory(factory);
            subscriber.addMessageListener((message, pattern) -> {
                try { live.broadcast(UUID.fromString(message.toString())); }
                catch (IllegalArgumentException ignored) { }
            }, new ChannelTopic(CHANNEL));
            return subscriber;
        }
    }
}
