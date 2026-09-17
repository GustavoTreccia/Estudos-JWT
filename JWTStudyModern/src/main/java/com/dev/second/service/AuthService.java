package com.dev.second.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dev.second.dtos.AuthResponse;
import com.dev.second.dtos.LoginRequest;
import com.dev.second.dtos.RegisterRequest;
import com.dev.second.entity.ConfirmationToken;
import com.dev.second.entity.User;
import com.dev.second.repository.ConfirmationTokenRepository;
import com.dev.second.repository.UserRepository;
import com.dev.second.security.JwtService;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final ConfirmationTokenRepository confirmationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final String confirmationBaseUrl;
    private final long confirmationExpirationHours;

    public AuthService(UserRepository userRepository, ConfirmationTokenRepository confirmationTokenRepository,
            PasswordEncoder passwordEncoder, EmailService emailService, JwtService jwtService,
            @Value("${app.auth.confirmation-base-url}") String confirmationBaseUrl,
            @Value("${app.auth.confirmation-expiration-hours}") long confirmationExpirationHours) {
        this.userRepository = userRepository;
        this.confirmationTokenRepository = confirmationTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.jwtService = jwtService;
        this.confirmationBaseUrl = confirmationBaseUrl;
        this.confirmationExpirationHours = confirmationExpirationHours;
    }

    @Transactional
    public void register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("Já existe uma conta com este e-mail");
        }

        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password())));
        String tokenValue = UUID.randomUUID().toString();
        ConfirmationToken token = confirmationTokenRepository.save(new ConfirmationToken(tokenValue,
                Instant.now().plus(confirmationExpirationHours, ChronoUnit.HOURS), user));
        emailService.sendConfirmationEmail(email, confirmationBaseUrl + token.getToken());
    }

    @Transactional
    public void confirmEmail(String tokenValue) {
        ConfirmationToken token = confirmationTokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Token de confirmação inválido"));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Token de confirmação expirado ou já utilizado");
        }
        token.getUser().setEmailConfirmed(true);
        token.markUsed();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
                .orElseThrow(() -> new BadCredentialsException("E-mail ou senha inválidos"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("E-mail ou senha inválidos");
        }
        if (!user.isEmailConfirmed()) {
            throw new IllegalStateException("Confirme seu e-mail antes de entrar");
        }
        return new AuthResponse(jwtService.createToken(user.getEmail()), "Bearer", jwtService.getExpirationSeconds());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
