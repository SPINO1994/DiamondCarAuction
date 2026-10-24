package com.diamond.gateway;

import java.util.List;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
class RateLimits implements GlobalFilter, Ordered {
    private static final RedisScript<Long> COUNT = RedisScript.of(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n", Long.class);
    private final ReactiveStringRedisTemplate redis;

    RateLimits(ReactiveStringRedisTemplate redis) { this.redis = redis; }

    @Override public int getOrder() { return -10; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        var next = exchange.mutate().request(request.mutate().headers(headers -> headers.set(
            "X-Correlation-Id", idValue(request))).build()).build();
        String path = request.getPath().value();
        boolean auth = path.equals("/api/auth/login") || path.equals("/api/auth/register");
        boolean bid = path.matches("/api/auctions/[^/]+/(bids|max-bid)");
        if (request.getMethod() != HttpMethod.POST || (!auth && !bid)) return chain.filter(next);
        var remote = request.getRemoteAddress();
        String forwarded = request.getHeaders().getFirst("X-Client-IP");
        String address = forwarded != null && forwarded.matches("[0-9a-fA-F:.]{2,45}") ? forwarded :
            remote == null ? "unknown" : remote.getAddress().getHostAddress();
        return redis.execute(COUNT, List.of("limit:" + (auth ? "auth:" : "bid:") + address), List.of())
            .next().onErrorReturn(-1L).flatMap(count -> {
                if (count >= 0 && count <= (auth ? 25 : 180)) return chain.filter(next);
                next.getResponse().setStatusCode(count < 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS);
                if (count >= 0) next.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, "60");
                return next.getResponse().setComplete();
            });
    }

    private String idValue(org.springframework.http.server.reactive.ServerHttpRequest request) {
        String value = request.getHeaders().getFirst("X-Correlation-Id");
        return value != null && value.matches("[a-zA-Z0-9_-]{1,80}") ? value : UUID.randomUUID().toString();
    }
}
