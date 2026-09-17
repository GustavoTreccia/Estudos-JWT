package com.dev.second.dtos;

public record AuthResponse(String accessToken, String tokenType, long expiresIn) {
}
