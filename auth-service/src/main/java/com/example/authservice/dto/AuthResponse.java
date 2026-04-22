package com.example.authservice.dto;

public record AuthResponse(
        String token,
        long expiresInMs,
        UserResponse user
) {}
