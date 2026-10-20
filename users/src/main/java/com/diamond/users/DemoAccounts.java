package com.diamond.users;

import java.util.UUID;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@Profile("dev")
class DemoAccounts {
    @Bean ApplicationRunner seedUsers(JdbcClient db, BCryptPasswordEncoder passwords) {
        return args -> {
            String hash = passwords.encode("DiamondDemo2026!");
            String[][] accounts = {
                {"buyer1", "Avery Morgan", "BUYER"},
                {"buyer2", "Jordan Ellis", "BUYER"},
                {"seller", "Sterling Motor Company", "SELLER"},
                {"admin", "Morgan Reed", "ADMIN"}
            };
            for (int i = 0; i < accounts.length; i++) {
                String[] account = accounts[i];
                db.sql("insert into account(id,email,password_hash,name,role) values (?,?,?,?,?) on conflict (id) do nothing")
                    .params(new UUID(0, i + 1), account[0] + "@diamond.test", hash, account[1], account[2]).update();
            }
        };
    }
}
