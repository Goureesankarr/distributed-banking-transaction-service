package com.bank.txn.config;

import com.bank.txn.domain.AppUser;
import com.bank.txn.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator, but only when a password is supplied via
 * {@code BOOTSTRAP_ADMIN_PASSWORD}. No default is provided: a service
 * that ships with a known admin credential has a public back door.
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(AppUserRepository users,
                          PasswordEncoder passwordEncoder,
                          @Value("${BOOTSTRAP_ADMIN_USERNAME:admin}") String adminUsername,
                          @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String adminPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.info("BOOTSTRAP_ADMIN_PASSWORD not set; skipping administrator bootstrap");
            return;
        }
        if (users.existsByUsername(adminUsername)) {
            return;
        }
        users.save(new AppUser(
                adminUsername,
                passwordEncoder.encode(adminPassword),
                "Platform Administrator",
                "ROLE_USER,ROLE_ADMIN"));
        log.info("Bootstrapped administrator '{}'", adminUsername);
    }
}
