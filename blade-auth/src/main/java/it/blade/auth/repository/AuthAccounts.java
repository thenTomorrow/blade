package it.blade.auth.repository;

import it.blade.auth.entity.AuthUser;
import it.blade.auth.entity.AuthUserAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accesso JPA alle tabelle di autenticazione; gli orari delle sessioni sono in UTC. */
@Repository
public class AuthAccounts {
    public record User(Long id, String username, String passwordHash, boolean enabled, Instant createdAt) {}
    public record UserAccess(String sessionId, Long userId, Instant issuedAt, Instant expiresAt,
                      Instant lastUsedAt, Instant revokedAt) {}

    private EntityManager entities;

    @PersistenceContext
    void setEntityManager(EntityManager entities) {
        this.entities = entities;
    }

    public Optional<User> findByUsername(String username) {
        var user = entities.createQuery("select u from AuthUser u where u.username = :username", AuthUser.class)
                .setParameter("username", username).getSingleResultOrNull();
        return Optional.ofNullable(user).map(u -> new User(u.id(), u.username(), u.passwordHash(), u.enabled(),
                u.createdAt().toInstant(ZoneOffset.UTC)));
    }

    public List<String> roles(Long userId) {
        return entities.createQuery("""
                select r.code from AuthUserRole ur join ur.role r
                where ur.user.id = :userId order by r.code
                """, String.class).setParameter("userId", userId).setMaxResults(33).getResultList();
    }

    @Transactional
    public void createAccess(UserAccess access) {
        var user = entities.getReference(AuthUser.class, access.userId());
        entities.persist(new AuthUserAccess(access.sessionId(), user,
                localUtc(access.issuedAt()), localUtc(access.expiresAt())));
    }

    // Un UPDATE condizionale rispetta anche una revoca concorrente.
    public boolean useAccess(String sessionId, Long userId, Instant now, Instant expiresAt) {
        return entities.createQuery("""
                update AuthUserAccess a set a.lastUsedAt = :now
                where a.sessionId = :sessionId and a.user.id = :userId and a.revokedAt is null
                    and a.expiresAt = :expires and a.expiresAt > :now
                    and exists (select u.id from AuthUser u where u.id = :userId and u.enabled = true)
                """).setParameter("sessionId", sessionId).setParameter("userId", userId).setParameter("now", localUtc(now))
                .setParameter("expires", localUtc(expiresAt)).executeUpdate() == 1;
    }

    private static LocalDateTime localUtc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}