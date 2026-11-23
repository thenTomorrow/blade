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
        var sessionId = UUID.randomUUID().toString();
        var secondSessionId = UUID.randomUUID().toString();

        try (var factory = configuration.buildSessionFactory()) {
            var userId = new Long[1];
            var accessId = new Long[1];
            factory.inTransaction(em -> {
                var user = new AuthUser("blade", "hash-fixture", true,
                        LocalDateTime.ofInstant(issued, ZoneOffset.UTC));
                em.persist(user);
                em.flush();
                userId[0] = user.id();
                for (String code : new String[] {"USER", "READER"}) {
                    var role = new AuthRole(code);
                    em.persist(role);
                    em.flush();
                    em.persist(new AuthUserRole(user, role));
                }
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.findByUsername("blade")).contains(
                        new AuthAccounts.User(userId[0], "blade", "hash-fixture", true, issued));
                assertThat(accounts.findByUsername("Blade")).isEmpty();
                assertThat(accounts.roles(userId[0])).containsExactly("READER", "USER");
                accounts.createAccess(new AuthAccounts.UserAccess(sessionId, userId[0], issued, expires, null, null));
                accounts.createAccess(new AuthAccounts.UserAccess(secondSessionId, userId[0], issued, expires, null, null));
                var ids = em.createQuery("select a.id from AuthUserAccess a where a.sessionId = :sessionId", Long.class);
                accessId[0] = ids.setParameter("sessionId", sessionId).getSingleResult();
                assertThat(accessId[0]).isPositive();
                assertThat(ids.setParameter("sessionId", secondSessionId).getSingleResult()).isGreaterThan(accessId[0]);
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId[0], expires, expires)).isFalse();
                assertThat(accounts.useAccess(sessionId, userId[0], later, expires.plusSeconds(1))).isFalse();
                assertThat(accounts.useAccess(sessionId, 999L, later, expires)).isFalse();
                assertThat(accounts.useAccess(sessionId, userId[0], later, expires)).isTrue();
                assertThat(em.find(AuthUserAccess.class, accessId[0]).lastUsedAt())
                        .isEqualTo(LocalDateTime.ofInstant(later, ZoneOffset.UTC));
                assertThat(em.createMutationQuery("update AuthUser u set u.enabled = false where u.id = :id")
                        .setParameter("id", userId[0]).executeUpdate()).isEqualTo(1);
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId[0], later.plusSeconds(1), expires)).isFalse();
                assertThat(em.createMutationQuery("update AuthUser u set u.enabled = true where u.id = :id")
                        .setParameter("id", userId[0]).executeUpdate()).isEqualTo(1);
                em.find(AuthUserAccess.class, accessId[0]).revoke(LocalDateTime.ofInstant(later, ZoneOffset.UTC));
            });

            factory.inTransaction(em -> {
                var accounts = new AuthAccounts();
                accounts.setEntityManager(em);
                assertThat(accounts.useAccess(sessionId, userId[0], later.plusSeconds(2), expires)).isFalse();
            });
        }
    }
}
