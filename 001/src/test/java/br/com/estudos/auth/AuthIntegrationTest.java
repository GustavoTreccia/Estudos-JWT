package br.com.estudos.auth;

import br.com.estudos.auth.config.AuthProperties;
import br.com.estudos.auth.model.Account;
import br.com.estudos.auth.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthIntegrationTest.TestConfig.class)
class AuthIntegrationTest {
    private static final String PASSWORD = "uma-senha-forte-123";
    private static final String CLIENT_SECRET = "test-client-secret-with-at-least-32-characters";
    private static final Path KEYS = createKeys();
    private static final String VERIFIER = "a".repeat(64);
    private static final PostgreSQLContainer<?> DATABASE = System.getenv("TEST_DATABASE_URL") == null
            ? new PostgreSQLContainer<>("postgres:17-alpine") : null;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired RecordingMailSender mail;
    @Autowired MutableClock clock;
    @Autowired RSAKey key;
    @Autowired AuthProperties properties;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (DATABASE != null) {
            DATABASE.start();
            registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
            registry.add("spring.datasource.username", DATABASE::getUsername);
            registry.add("spring.datasource.password", DATABASE::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
            registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USERNAME"));
            registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        }
        registry.add("app.auth.client-secret", () -> CLIENT_SECRET);
        registry.add("app.auth.code-secret", () -> "test-code-secret-with-at-least-32-characters");
        registry.add("app.auth.private-key", () -> KEYS.resolve("private.pem").toUri().toString());
        registry.add("app.auth.public-key", () -> KEYS.resolve("public.pem").toUri().toString());
        registry.add("server.servlet.session.cookie.secure", () -> "false");
    }

    @BeforeEach
    void resetMail() {
        mail.fail = false;
        mail.messages.clear();
        clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    @AfterAll
    static void cleanKeys() throws IOException {
        if (DATABASE != null) {
            DATABASE.stop();
        }
        Files.delete(KEYS.resolve("private.pem"));
        Files.delete(KEYS.resolve("public.pem"));
        Files.delete(KEYS);
    }

    @Test
    void registrationSendsCodeStoresHashesAndBlocksUnconfirmedLogin() throws Exception {
        String email = email();
        register(email.toUpperCase());
        Account account = accounts.findByEmail(email).orElseThrow();
        assertThat(account.isEnabled()).isFalse();
        assertThat(passwords.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.getCodeHash()).hasSize(64).isNotEqualTo(code(email));
        assertThat(account.getCodeExpiresAt()).isEqualTo(clock.instant().plusSeconds(600));
        assertThat(mail.messages.get(email).getFrom()).isEqualTo("no-reply@example.test");
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
        confirm(email, code(email), 200);
        assertThat(accounts.findByEmail(email).orElseThrow().isEnabled()).isTrue();
        mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
        confirm(email, code(email), 400);
    }

    @Test
    void invalidAttemptsArePersistedAndBlockEvenCorrectCode() throws Exception {
        String email = email();
        register(email);
        String right = code(email);
        String wrong = right.equals("000000") ? "111111" : "000000";
        for (int attempt = 0; attempt < 5; attempt++) {
            confirm(email, wrong, 400);
        }
        assertThat(accounts.findByEmail(email).orElseThrow().getCodeAttempts()).isEqualTo(5);
        confirm(email, right, 400);
        assertThat(accounts.findByEmail(email).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void expiryBoundaryAndResendCooldownAreEnforced() throws Exception {
        String email = email();
        register(email);
        mvc.perform(post("/api/auth/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        String expired = code(email);
        clock.now = clock.now.plusSeconds(600);
        confirm(email, expired, 400);
        mvc.perform(post("/api/auth/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
        confirm(email, code(email), 200);
    }

    @Test
    void resendInvalidatesOldCodeAndResetsAttempts() throws Exception {
        String email = email();
        register(email);
        String previousHash = accounts.findByEmail(email).orElseThrow().getCodeHash();
        String old = code(email);
        confirm(email, old.equals("000000") ? "111111" : "000000", 400);
        clock.now = clock.now.plusSeconds(60);
        mvc.perform(post("/api/auth/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
        Account account = accounts.findByEmail(email).orElseThrow();
        assertThat(account.getCodeAttempts()).isZero();
        assertThat(account.getCodeHash()).isNotEqualTo(previousHash);
        confirm(email, old, 400);
        confirm(email, code(email), 200);
    }

    @Test
    void duplicateRegistrationDoesNotChangePasswordOrSendEmail() throws Exception {
        String email = email();
        register(email);
        String hash = accounts.findByEmail(email).orElseThrow().getPasswordHash();
        mail.messages.clear();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "outra-senha-forte-123"))))
                .andExpect(status().isAccepted());
        assertThat(accounts.findByEmail(email).orElseThrow().getPasswordHash()).isEqualTo(hash);
        assertThat(mail.messages).isEmpty();
        mvc.perform(post("/api/auth/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email()))))
                .andExpect(status().isAccepted());
    }

    @Test
    void smtpFailureRollsBackRegistrationAndResend() throws Exception {
        String failed = email();
        mail.fail = true;
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", failed, "password", PASSWORD))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503));
        assertThat(accounts.findByEmail(failed)).isEmpty();
        mail.fail = false;
        String email = email();
        register(email);
        String old = code(email);
        String hash = accounts.findByEmail(email).orElseThrow().getCodeHash();
        clock.now = clock.now.plusSeconds(60);
        mail.fail = true;
        mvc.perform(post("/api/auth/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isServiceUnavailable());
        assertThat(accounts.findByEmail(email).orElseThrow().getCodeHash()).isEqualTo(hash);
        confirm(email, old, 200);
    }

    @Test
    void validationCsrfAndBearerOnlyApiAreEnforced() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email(), "password", "\u00e9".repeat(40)))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email(), "code", "123"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/login").param("username", email()).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void discoveryJwksAndJwtValidation() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.issuer").value(properties.issuer()));
        mvc.perform(get("/oauth2/jwks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(key.getKeyID()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
        String email = email();
        register(email);
        confirm(email, code(email), 200);
        String id = accounts.findByEmail(email).orElseThrow().getId().toString();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + signedToken(id, email,
                        properties.issuer(), properties.audience(), Instant.now().plusSeconds(300), "profile")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.email").value(email));
        for (String token : List.of(
                signedToken(id, email, "https://wrong.example", properties.audience(), Instant.now().plusSeconds(300), "profile"),
                signedToken(id, email, properties.issuer(), "wrong-audience", Instant.now().plusSeconds(300), "profile"),
                signedToken(id, email, properties.issuer(), properties.audience(), Instant.now().minusSeconds(120), "profile"))) {
            mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + signedToken(id, email,
                        properties.issuer(), properties.audience(), Instant.now().plusSeconds(300), "email")))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullAuthorizationCodePkceConsentRefreshAndRevocation() throws Exception {
        String email = email();
        register(email);
        confirm(email, code(email), 200);
        Cookie session = login(email);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        MvcResult consent = mvc.perform(get("/oauth2/authorize").cookie(session)
                        .queryParam("response_type", "code").queryParam("client_id", "web-client")
                        .queryParam("redirect_uri", properties.redirectUri()).queryParam("scope", "openid profile email")
                        .queryParam("state", "test-state").queryParam("nonce", "test-nonce")
                        .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256"))
                .andExpect(status().isOk()).andReturn();
        assertThat(consent.getResponse().getContentAsString()).contains("profile", "email");
        var state = Pattern.compile("name=\"state\" value=\"([^\"]+)\"")
                .matcher(consent.getResponse().getContentAsString());
        assertThat(state.find()).isTrue();
        MvcResult approved = mvc.perform(post("/oauth2/authorize").cookie(session).with(csrf())
                        .param("client_id", "web-client").param("state", state.group(1))
                        .param("scope", "profile", "email"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var query = UriComponentsBuilder.fromUriString(approved.getResponse().getRedirectedUrl())
                .build().getQueryParams();
        assertThat(query.getFirst("state")).isEqualTo("test-state");
        String code = query.getFirst("code");
        assertThat(code).isNotBlank();
        tokenRequest(code, "b".repeat(64)).andExpect(status().isBadRequest());
        MvcResult tokens = tokenRequest(code, VERIFIER).andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").isString())
                .andExpect(jsonPath("$.id_token").isString())
                .andExpect(jsonPath("$.refresh_token").isString()).andReturn();
        JsonNode body = json.readTree(tokens.getResponse().getContentAsString());
        String accessToken = body.path("access_token").asText();
        String refreshToken = body.path("refresh_token").asText();
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/userinfo").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email_verified").value(true));
        MvcResult refreshed = mvc.perform(post("/oauth2/token").header("Authorization", clientBasic())
                        .param("grant_type", "refresh_token").param("refresh_token", refreshToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode rotated = json.readTree(refreshed.getResponse().getContentAsString());
        String newRefresh = rotated.path("refresh_token").asText();
        assertThat(newRefresh).isNotEqualTo(refreshToken);
        mvc.perform(post("/oauth2/token").header("Authorization", clientBasic())
                        .param("grant_type", "refresh_token").param("refresh_token", refreshToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/oauth2/revoke").header("Authorization", clientBasic())
                        .param("token", newRefresh).param("token_type_hint", "refresh_token"))
                .andExpect(status().isOk());
        mvc.perform(post("/oauth2/token").header("Authorization", clientBasic())
                        .param("grant_type", "refresh_token").param("refresh_token", newRefresh))
                .andExpect(status().isBadRequest());
        tokenRequest(code, VERIFIER).andExpect(status().isBadRequest());
    }

    @Test
    void authorizationRequiresPkceAndRejectsArbitraryRedirect() throws Exception {
        String email = email();
        register(email);
        confirm(email, code(email), 200);
        Cookie session = login(email);
        mvc.perform(get("/oauth2/authorize").cookie(session).queryParam("response_type", "code")
                        .queryParam("client_id", "web-client").queryParam("redirect_uri", properties.redirectUri())
                        .queryParam("scope", "openid profile"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("error=invalid_request")));
        mvc.perform(get("/oauth2/authorize").cookie(session).queryParam("response_type", "code")
                        .queryParam("client_id", "web-client").queryParam("redirect_uri", "https://evil.example/callback")
                        .queryParam("scope", "openid profile"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void maximumLengthEmailFitsAccountAndJdbcSessionPrincipal() throws Exception {
        String local = UUID.randomUUID().toString().replace("-", "") + "a".repeat(32);
        String email = local + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertThat(email).hasSize(254);
        register(email);
        confirm(email, code(email), 200);
        Cookie session = login(email);
        mvc.perform(get("/oauth2/authorize").cookie(session).queryParam("response_type", "code")
                        .queryParam("client_id", "web-client").queryParam("redirect_uri", properties.redirectUri())
                        .queryParam("scope", "openid profile")
                        .queryParam("code_challenge", "a".repeat(43)).queryParam("code_challenge_method", "S256"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions tokenRequest(String code, String verifier) throws Exception {
        return mvc.perform(post("/oauth2/token").header("Authorization", clientBasic())
                .param("grant_type", "authorization_code").param("code", code)
                .param("redirect_uri", properties.redirectUri()).param("code_verifier", verifier));
    }

    private Cookie login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/login").with(csrf()).param("username", email).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andReturn();
        Cookie cookie = result.getResponse().getCookie("SESSION");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private String signedToken(String id, String email, String issuer, String audience, Instant expires, String scope)
            throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(id).issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300))).expirationTime(Date.from(expires))
                .claim("email", email).claim("scope", scope).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.code").doesNotExist());
    }

    private void confirm(String email, String code, int status) throws Exception {
        mvc.perform(post("/api/auth/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "code", code))))
                .andExpect(status().is(status));
    }

    private String code(String email) {
        var matcher = Pattern.compile("\\b[0-9]{6}\\b").matcher(mail.messages.get(email).getText());
        assertThat(matcher.find()).isTrue();
        return matcher.group();
    }

    private static String clientBasic() {
        return "Basic " + Base64.getEncoder().encodeToString(("web-client:" + CLIENT_SECRET)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }

    private static Path createKeys() {
        try {
            Path directory = Files.createTempDirectory("auth-test-keys-");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            writePem(directory.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
            writePem(directory.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
            return directory;
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Falha ao gerar chaves de teste.", exception);
        }
    }

    private static void writePem(Path path, String type, byte[] bytes) throws IOException {
        Files.writeString(path, "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n");
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingMailSender mailSender() {
            return new RecordingMailSender();
        }

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class RecordingMailSender extends JavaMailSenderImpl {
        final Map<String, SimpleMailMessage> messages = new HashMap<>();
        boolean fail;

        @Override
        public void send(SimpleMailMessage... messages) {
            if (fail) {
                throw new MailSendException("Simulated SMTP failure");
            }
            for (SimpleMailMessage message : messages) {
                this.messages.put(message.getTo()[0], new SimpleMailMessage(message));
            }
        }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
