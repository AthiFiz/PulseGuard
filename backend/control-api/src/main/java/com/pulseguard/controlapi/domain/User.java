package com.pulseguard.controlapi.domain;

import com.pulseguard.controlapi.enums.SystemRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A PulseGuard user account.
 *
 * <p>{@code passwordHash} exists only as a persistence foundation. Password
 * hashing, login, and authorization are implemented in a later stage, and this
 * class deliberately does not implement any Spring Security interface.
 *
 * <p>Lombok generates getters and the setters for mutable business fields. It
 * deliberately does not generate {@code equals}, {@code hashCode}, or
 * {@code toString}: value-based versions of those would load lazy associations
 * and change identity as fields mutate.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Setter
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Setter
    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "system_role", nullable = false, length = 32)
    private SystemRole systemRole = SystemRole.USER;

    @Setter
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    /**
     * Identity-based equality. Two unsaved instances are never equal, which
     * keeps behaviour predictable while entities move in and out of the
     * persistence context.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
