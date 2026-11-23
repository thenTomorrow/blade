package it.blade.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Gli orari in DATETIME sono salvati in UTC. */
@Entity
@Table(name = "user_access")
public class AuthUserAccess {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36, nullable = false, unique = true)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AuthUser user;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected AuthUserAccess() {}

    public AuthUserAccess(String sessionId, AuthUser user, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        this.sessionId = sessionId;
        this.user = user;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public LocalDateTime lastUsedAt() {
        return lastUsedAt;
    }

    public void revoke(LocalDateTime at) {
        revokedAt = at;
    }
}
