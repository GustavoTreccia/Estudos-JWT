package br.com.estudos.auth.config;

import br.com.estudos.auth.repository.AccountRepository;
import br.com.estudos.auth.service.AccountService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService users(AccountRepository accounts) {
        return email -> accounts.findByEmail(AccountService.normalize(email))
                .map(account -> User.withUsername(account.getEmail()).password(account.getPasswordHash())
                        .roles("USER").disabled(!account.isEnabled()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais invalidas."));
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServer(HttpSecurity http) throws Exception {
        var server = OAuth2AuthorizationServerConfigurer.authorizationServer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(server, config -> config.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/auth/register", "/api/auth/confirm", "/api/auth/resend").permitAll()
                        .requestMatchers("/api/me").hasAuthority("SCOPE_profile")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain browser(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    RegisteredClientRepository clients(JdbcTemplate jdbc, PasswordEncoder passwords, AuthProperties properties) {
        var repository = new JdbcRegisteredClientRepository(jdbc);
        RegisteredClient existing = repository.findByClientId(properties.clientId());
        var client = RegisteredClient.withId(existing == null ? "first-party-client" : existing.getId())
                .clientId(properties.clientId())
                .clientSecret(passwords.encode(properties.clientSecret()))
                .clientName("Aplicacao web (backend/BFF)")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.redirectUri())
                .scope(OidcScopes.OPENID).scope(OidcScopes.PROFILE).scope(OidcScopes.EMAIL)
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .requireAuthorizationConsent(true).build())
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofHours(8)).reuseRefreshTokens(false).build())
                .build();
        repository.save(client);
        return repository;
    }

    @Bean
    OAuth2AuthorizationService authorizations(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService consents(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    @Bean
    AuthorizationServerSettings serverSettings(AuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }

    @Bean
    RSAKey signingKey(AuthProperties properties) throws IOException, NoSuchAlgorithmException {
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;
        try (var input = properties.privateKey().getInputStream()) {
            privateKey = RsaKeyConverters.pkcs8().convert(input);
        }
        try (var input = properties.publicKey().getInputStream()) {
            publicKey = RsaKeyConverters.x509().convert(input);
        }
        if (privateKey == null || publicKey == null || publicKey.getModulus().bitLength() < 2048
                || !privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("Configure um par de chaves RSA valido, com pelo menos 2048 bits.");
        }
        String keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey key) {
        JWKSet keys = new JWKSet(key);
        return (selector, context) -> selector.select(keys);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, AuthProperties properties) throws com.nimbusds.jose.JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                jwt -> jwt.getAudience().contains(properties.audience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audience invalida.", null))));
        return decoder;
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> claims(AccountRepository accounts, AuthProperties properties) {
        return context -> {
            var account = accounts.findByEmail(context.getPrincipal().getName())
                    .orElseThrow(() -> new UsernameNotFoundException("Conta nao encontrada."));
            context.getClaims().subject(account.getId().toString());
            if ("access_token".equals(context.getTokenType().getValue())) {
                // JDBC metadata must use a Jackson-allowlisted collection type.
                context.getClaims().audience(new ArrayList<>(List.of(properties.audience())));
            }
            if (context.getAuthorizedScopes().contains(OidcScopes.EMAIL)
                    || context.getAuthorizedScopes().contains(OidcScopes.PROFILE)) {
                context.getClaims().claim("email", account.getEmail()).claim("email_verified", account.isEnabled());
            }
        };
    }
}
