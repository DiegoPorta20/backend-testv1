package com.example.authservice.service;

import com.example.authservice.dto.AuthResponse;
import com.example.authservice.dto.LoginRequest;
import com.example.authservice.dto.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest req);
    AuthResponse login(LoginRequest req);
}
