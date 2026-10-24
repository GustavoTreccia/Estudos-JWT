package br.com.estudos.auth.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank String clientId,
        @Size(min = 32) @NotNull String clientSecret,
        @NotBlank String redirectUri,
        @Size(min = 32) @NotNull String codeSecret,
        @Email @NotBlank String mailFrom,
        @NotNull Resource privateKey,
        @NotNull Resource publicKey) {
}
