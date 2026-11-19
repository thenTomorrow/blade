package it.blade.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import it.blade.auth.entity.AuthRole;
import it.blade.auth.entity.AuthUser;
import it.blade.auth.entity.AuthUserAccess;
import it.blade.auth.entity.AuthUserRole;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;

class AuthAccountsTest {
    @Test
    void mapsAccountsRolesAndRevocableSessions() {
        var configuration = new Configuration()
                .addAnnotatedClass(AuthUser.class).addAnnotatedClass(AuthRole.class)
                .addAnnotatedClass(AuthUserRole.class).addAnnotatedClass(AuthUserAccess.class)
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:auth-" + UUID.randomUUID())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop");
        var issued = Instant.parse("2026-01-01T10:00:00Z");
        var expires = issued.plusSeconds(60);
        var later = issued.plusSeconds(15);
        var userId = UUID.randomUUID().toString();
        var sessionId = UUID.randomUUID().toString();

        try (var factory = configuration.buildSessionFactory()) {
            factory.inTransaction(em -> {
                var user = new AuthUser(userId, "blade", "hash-fixture", true,
                        LocalDateTime.ofInstant(issued, ZoneOffset.UTC));
                em.persist(user);
                for (String code : new String[] {"USER", "READER"}) {
                    var role = new AuthRole(code);
                    em.persist(role);
                    em.persist(new AuthUserRole(user, role));
                }
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.findByUsername("blade")).contains(
                        new AuthAccounts.User(userId, "blade", "hash-fixture", true, issued));
                assertThat(accounts.findByUsername("Blade")).isEmpty();
                assertThat(accounts.roles(userId)).containsExactly("READER", "USER");
                accounts.createAccess(new AuthAccounts.UserAccess(sessionId, userId, issued, expires, null, null));
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId, expires, expires)).isFalse();
                assertThat(accounts.useAccess(sessionId, userId, later, expires.plusSeconds(1))).isFalse();
                assertThat(accounts.useAccess(sessionId, UUID.randomUUID().toString(), later, expires)).isFalse();
                assertThat(accounts.useAccess(sessionId, userId, later, expires)).isTrue();
                assertThat(em.find(AuthUserAccess.class, sessionId).lastUsedAt())
                        .isEqualTo(LocalDateTime.ofInstant(later, ZoneOffset.UTC));
                assertThat(em.createMutationQuery("update AuthUser u set u.enabled = false where u.id = :id")
                        .setParameter("id", userId).executeUpdate()).isEqualTo(1);
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId, later.plusSeconds(1), expires)).isFalse();
                assertThat(em.createMutationQuery("update AuthUser u set u.enabled = true where u.id = :id")
                        .setParameter("id", userId).executeUpdate()).isEqualTo(1);
                em.find(AuthUserAccess.class, sessionId).revoke(LocalDateTime.ofInstant(later, ZoneOffset.UTC));
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId, later.plusSeconds(2), expires)).isFalse();
            });
        }
    }
}
