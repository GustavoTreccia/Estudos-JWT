package br.com.estudos.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled;
    private String codeHash;
    private Instant codeExpiresAt;
    private Instant codeSentAt;
    @Column(nullable = false)
    private int codeAttempts;

    protected Account() {
    }

    public Account(String email, String passwordHash) {
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public Instant getCodeSentAt() {
        return codeSentAt;
    }

    public int getCodeAttempts() {
        return codeAttempts;
    }

    public void issueCode(String hash, Instant now) {
        codeHash = hash;
        codeExpiresAt = now.plusSeconds(600);
        codeSentAt = now;
        codeAttempts = 0;
    }

    public void recordFailedAttempt() {
        codeAttempts++;
    }

    public void confirm() {
        enabled = true;
        codeHash = null;
        codeExpiresAt = null;
    }
}
