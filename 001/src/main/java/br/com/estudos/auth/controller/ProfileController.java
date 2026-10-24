package br.com.estudos.auth.controller;

import br.com.estudos.auth.record.ProfileResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProfileController {
    @GetMapping("/api/me")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new ProfileResponse(jwt.getSubject(), jwt.getClaimAsString("email"));
    }
}
