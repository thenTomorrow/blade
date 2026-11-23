package it.blade.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_role")
public class AuthUserRole {
    @Embeddable
    public record Key(@Column(name = "user_id") Long userId, @Column(name = "role_id") Long roleId) {}

    @EmbeddedId
    Key id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    AuthUser user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    AuthRole role;

    protected AuthUserRole() {}

    public AuthUserRole(AuthUser user, AuthRole role) {
        this.id = new Key(user.id(), role.id);
        this.user = user;
        this.role = role;
    }
}