package it.blade.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "`user`")
public class AuthUser {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    String id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AuthUser() {}

    public AuthUser(String id, String username, String passwordHash, boolean enabled, LocalDateTime createdAt) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String id() { return id; }
    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
    public boolean enabled() { return enabled; }
    public LocalDateTime createdAt() { return createdAt; }
}
