package com.diamond.notifications;

import com.diamond.shared.ServiceKey;
import java.time.Duration;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Component
class EmailDelivery {
    private final Logger log = LoggerFactory.getLogger(EmailDelivery.class);
    private final JdbcClient db;
    private final JavaMailSender mail;
    private final TransactionTemplate transactions;
    private final RestClient users;
    private final String origin;

    EmailDelivery(JdbcClient db, JavaMailSender mail, TransactionTemplate transactions, ServiceKey key,
                  @Value("${diamond.users-url}") String usersUrl, @Value("${diamond.origin}") String origin) {
        this.db = db;
        this.mail = mail;
        this.transactions = transactions;
        this.origin = origin;
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(3));
        this.users = RestClient.builder().baseUrl(usersUrl).requestFactory(requests)
            .defaultHeader("X-Service-Key", key.value()).build();
    }

    record Pending(UUID id, UUID recipientId, String title, String body, String link, int emailAttempts) {}
    record Recipient(String email) {}

    @Scheduled(fixedDelay=1200)
    void deliver() {
        transactions.executeWithoutResult(status -> {
            var pending = db.sql("""
                select id,recipient_id,title,body,link,email_attempts from notice
                where email_sent_at is null and email_available_at<=now() and email_attempts<12
                order by email_available_at,id limit 1 for update skip locked
                """).query(Pending.class).optional();
            pending.ifPresent(this::send);
        });
    }

    private void send(Pending notice) {
        try {
            var recipient = Objects.requireNonNull(users.get().uri("/internal/users/{id}", notice.recipientId())
                .retrieve().body(Recipient.class));
            var email = new SimpleMailMessage();
            email.setFrom("auctions@diamond.test");
            email.setTo(recipient.email());
            email.setSubject(notice.title());
            email.setText(notice.body() + "\n\n" + origin + notice.link());
            mail.send(email);
        } catch (RuntimeException error) {
            db.sql("update notice set email_attempts=email_attempts+1,email_available_at=now()+make_interval(secs=>?) where id=?")
                .params((double) Math.min(300, 2 << notice.emailAttempts()), notice.id()).update();
            log.warn("Email delivery failed for notice {}", notice.id());
            return;
        }
        db.sql("update notice set email_sent_at=now() where id=?").param(notice.id()).update();
    }
}
