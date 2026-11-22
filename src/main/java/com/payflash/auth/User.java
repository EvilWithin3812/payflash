package com.payflash.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "transaction_pin_hash")
    private String transactionPinHash;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts;

    @Column(name = "pin_locked_until")
    private Instant pinLockedUntil;

    protected User() {
        // for JPA
    }

    public User(String email, String fullName, String passwordHash, Role role) {
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void promoteToAdmin() {
        this.role = Role.ADMIN;
    }

    public void markEmailVerified() {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = Instant.now();
        }
    }

    public void acceptTerms() {
        this.termsAcceptedAt = Instant.now();
    }

    public void changePassword(String newHash) {
        this.passwordHash = newHash;
    }

    /** Invalidates every token issued so far (refresh tokens via the DB, access tokens via Redis). */
    public int revokeTokens() {
        return ++tokenVersion;
    }

    /**
     * Removes personal data but keeps the row: transactions and ledger entries reference it, and payment
     * records must be kept for accounting. The account can never log in again.
     */
    public void anonymise(String unusablePasswordHash) {
        this.email = "deleted-" + id + "@deleted.invalid";
        this.fullName = "Deleted user";
        this.passwordHash = unusablePasswordHash;
        this.deletedAt = Instant.now();
        this.transactionPinHash = null;
        revokeTokens();
    }

    public String getTransactionPinHash() {
        return transactionPinHash;
    }

    public boolean hasTransactionPin() {
        return transactionPinHash != null;
    }

    public boolean isPinLocked(Instant now) {
        return pinLockedUntil != null && pinLockedUntil.isAfter(now);
    }

    public Instant getPinLockedUntil() {
        return pinLockedUntil;
    }

    public int getPinFailedAttempts() {
        return pinFailedAttempts;
    }

    /** Sets or changes the PIN and clears any lock from earlier wrong guesses. */
    public void setTransactionPin(String pinHash) {
        this.transactionPinHash = pinHash;
        this.pinFailedAttempts = 0;
        this.pinLockedUntil = null;
    }

    /** Counts a wrong PIN; after {@code maxAttempts} the PIN is locked until {@code lockUntil}. */
    public void recordWrongPin(int maxAttempts, Instant lockUntil) {
        pinFailedAttempts++;
        if (pinFailedAttempts >= maxAttempts) {
            pinLockedUntil = lockUntil;
            pinFailedAttempts = 0;
        }
    }

    public void recordCorrectPin() {
        if (pinFailedAttempts != 0 || pinLockedUntil != null) {
            pinFailedAttempts = 0;
            pinLockedUntil = null;
        }
    }
}
