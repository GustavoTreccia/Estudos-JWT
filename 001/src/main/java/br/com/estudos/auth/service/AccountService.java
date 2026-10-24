package br.com.estudos.auth.service;

import br.com.estudos.auth.config.AuthProperties;
import br.com.estudos.auth.exception.AuthException;
import br.com.estudos.auth.model.Account;
import br.com.estudos.auth.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final VerificationMailer mailer;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accounts, PasswordEncoder passwords,
                          VerificationMailer mailer, AuthProperties properties, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.mailer = mailer;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void register(String email, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "A senha deve ter no maximo 72 bytes UTF-8.");
        }
        String normalized = normalize(email);
        String encoded = passwords.encode(password);
        // Existing registrations must not allow an unauthenticated password replacement.
        if (accounts.findForUpdate(normalized).isPresent()) {
            return;
        }
        Account account = accounts.saveAndFlush(new Account(normalized, encoded));
        sendCode(account);
    }

    @Transactional
    public void resend(String email) {
        accounts.findForUpdate(normalize(email)).filter(account -> !account.isEnabled())
                .ifPresent(account -> {
                    if (account.getCodeSentAt().plusSeconds(60).isAfter(clock.instant())) {
                        throw new AuthException(HttpStatus.TOO_MANY_REQUESTS,
                                "Aguarde 60 segundos entre os envios.");
                    }
                    sendCode(account);
                });
    }

    @Transactional(noRollbackFor = AuthException.class)
    public void confirm(String email, String code) {
        Account account = accounts.findForUpdate(normalize(email)).orElseThrow(this::invalidCode);
        Instant now = clock.instant();
        if (account.isEnabled() || account.getCodeHash() == null
                || !now.isBefore(account.getCodeExpiresAt()) || account.getCodeAttempts() >= 5) {
            throw invalidCode();
        }
        if (!MessageDigest.isEqual(account.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(account.getEmail(), code).getBytes(StandardCharsets.US_ASCII))) {
            account.recordFailedAttempt();
            throw invalidCode();
        }
        account.confirm();
    }

    private void sendCode(Account account) {
        String code;
        String codeHash;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            codeHash = hash(account.getEmail(), code);
        } while (codeHash.equals(account.getCodeHash()));
        account.issueCode(codeHash, clock.instant());
        mailer.send(account.getEmail(), code);
    }

    private String hash(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.codeSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Nao foi possivel proteger o codigo de confirmacao.", exception);
        }
    }

    private AuthException invalidCode() {
        return new AuthException(HttpStatus.BAD_REQUEST,
                "Codigo invalido, expirado ou bloqueado. Solicite um novo codigo.");
    }

    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
