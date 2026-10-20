package com.diamond.users;

import com.diamond.shared.ServiceKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class Accounts {
    private static final Duration REFRESH_TTL = Duration.ofDays(14);
    private final SecureRandom random = new SecureRandom();
    private final JdbcClient db;
    private final JwtEncoder jwt;
    private final BCryptPasswordEncoder passwords;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String origin;
    private final boolean secure;
    private final ServiceKey internal;

    Accounts(JdbcClient db, JwtEncoder jwt, BCryptPasswordEncoder passwords, Clock clock,
             TransactionTemplate transactions, ServiceKey internal, @Value("${diamond.origin}") String origin,
             @Value("${diamond.secure-cookies}") boolean secure) {
        this.db = db;
        this.jwt = jwt;
        this.passwords = passwords;
        this.clock = clock;
        this.transactions = transactions;
        this.internal = internal;
        this.origin = origin;
        this.secure = secure;
    }

    record Register(@Email @NotBlank @Size(max=254) String email, @Size(min=12,max=72) @NotNull String password,
                    @NotBlank @Size(max=80) String name, @Pattern(regexp="BUYER|SELLER") @NotNull String role) {}
    record Login(@Email @NotBlank @Size(max=254) String email, @NotBlank @Size(max=72) String password) {}
    record Profile(UUID id, String email, String name, String role, OffsetDateTime joinedAt) {}
    record Session(String accessToken, Profile profile) {}
    record Refresh(UUID accountId, UUID family, OffsetDateTime expiresAt, boolean used) {}
    record Name(@NotBlank @Size(max=80) String name) {}

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    Session register(@Valid @RequestBody Register body, HttpServletRequest request, HttpServletResponse response) {
        checkOrigin(request);
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes.");
        var id = UUID.randomUUID();
        try {
            db.sql("insert into account(id,email,password_hash,name,role) values (?,?,?,?,?)")
                .params(id, normalize(body.email()), passwords.encode(body.password()), body.name().strip(), body.role()).update();
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email is already registered.");
        }
        return issue(profile(id), UUID.randomUUID(), response);
    }

    @PostMapping("/api/auth/login")
    Session login(@Valid @RequestBody Login body, HttpServletRequest request, HttpServletResponse response) {
        checkOrigin(request);
        var account = db.sql("select id,password_hash from account where email=?").param(normalize(body.email()))
            .query((row, index) -> Map.entry(row.getObject("id", UUID.class), row.getString("password_hash"))).optional();
        String hash = account.map(Map.Entry::getValue).orElse(DUMMY_HASH);
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(body.password(), hash) || account.isEmpty())
            throw invalid();
        return issue(profile(account.orElseThrow().getKey()), UUID.randomUUID(), response);
    }

    @PostMapping("/api/auth/refresh")
    Session refresh(@CookieValue(name="diamond_refresh", required=false) String token,
                    HttpServletRequest request, HttpServletResponse response) {
        checkOrigin(request);
        if (token == null) throw invalid();
        Session result = transactions.execute(transaction -> {
            var session = findRefresh(token);
            if (session.isEmpty()) return null;
            var old = session.get();
            if (old.used()) {
                db.sql("delete from refresh_session where family=?").param(old.family()).update();
                return null;
            }
            if (!old.expiresAt().toInstant().isAfter(clock.instant())) return null;
            db.sql("update refresh_session set used=true where digest=?").param(digest(token)).update();
            return issue(profile(old.accountId()), old.family(), response);
        });
        if (result == null) {
            cookie(response, "", Duration.ZERO);
            throw invalid();
        }
        return result;
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name="diamond_refresh", required=false) String token,
                HttpServletRequest request, HttpServletResponse response) {
        checkOrigin(request);
        if (token != null) transactions.executeWithoutResult(transaction ->
            findRefresh(token).ifPresent(session ->
                db.sql("delete from refresh_session where family=?").param(session.family()).update()));
        cookie(response, "", Duration.ZERO);
    }

    @GetMapping("/api/profile")
    Profile current(@AuthenticationPrincipal Jwt user) { return profile(UUID.fromString(user.getSubject())); }

    @PutMapping("/api/profile")
    Profile update(@AuthenticationPrincipal Jwt user, @Valid @RequestBody Name body) {
        UUID id = UUID.fromString(user.getSubject());
        db.sql("update account set name=? where id=?").params(body.name().strip(), id).update();
        return profile(id);
    }

    @GetMapping("/internal/users/{id}")
    Profile recipient(@PathVariable UUID id, @RequestHeader(value="X-Service-Key", required=false) String key) {
        internal.check(key);
        return profile(id);
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    void cleanExpired() {
        db.sql("delete from refresh_session where expires_at < now() and family not in "
            + "(select family from refresh_session where expires_at >= now())").update();
    }

    private Optional<Refresh> findRefresh(String token) {
        var family = db.sql("select family from refresh_session where digest=?").param(digest(token)).query(UUID.class).optional();
        if (family.isEmpty()) return Optional.empty();
        db.sql("select pg_advisory_xact_lock(hashtextextended(?,0))").param(family.get().toString())
            .query((result, index) -> true).single();
        return db.sql("select account_id, family, expires_at, used from refresh_session where digest=?")
            .param(digest(token)).query(Refresh.class).optional();
    }

    private Profile profile(UUID id) {
        return db.sql("select id,email,name,role,joined_at from account where id=?").param(id).query(Profile.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account unavailable."));
    }

    private Session issue(Profile profile, UUID family, HttpServletResponse response) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(32));
        Instant now = clock.instant();
        db.sql("insert into refresh_session(digest,account_id,family,expires_at) values (?,?,?,?)")
            .params(digest(token), profile.id(), family, now.plus(REFRESH_TTL).atOffset(ZoneOffset.UTC)).update();
        cookie(response, token, REFRESH_TTL);
        var claims = JwtClaimsSet.builder().issuer("diamond").subject(profile.id().toString())
            .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("role", profile.role()).claim("name", profile.name()).build();
        return new Session(jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue(), profile);
    }

    private void cookie(HttpServletResponse response, String value, Duration age) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("diamond_refresh", value)
            .httpOnly(true).secure(secure).sameSite("Strict").path("/api/auth").maxAge(age).build().toString());
    }

    private void checkOrigin(HttpServletRequest request) {
        if (!origin.equals(request.getHeader(HttpHeaders.ORIGIN)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Request origin is not allowed.");
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in with a valid email and password.");
    }

    private static final String DUMMY_HASH = new BCryptPasswordEncoder(12).encode("unused-password-for-timing");
}

@org.springframework.context.annotation.Configuration
class Passwords {
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
