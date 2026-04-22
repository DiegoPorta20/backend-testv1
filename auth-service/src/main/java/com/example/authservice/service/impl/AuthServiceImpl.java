package com.example.authservice.service.impl;

import com.example.authservice.domain.Role;
import com.example.authservice.domain.User;
import com.example.authservice.dto.AuthResponse;
import com.example.authservice.dto.LoginRequest;
import com.example.authservice.dto.RegisterRequest;
import com.example.authservice.dto.UserResponse;
import com.example.authservice.exception.BusinessException;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.security.JwtService;
import com.example.authservice.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email already registered");
        }
        User user = User.builder()
                .name(req.name())
                .email(req.email())
                .password(passwordEncoder.encode(req.password()))
                .role(Role.USER)
                .build();
        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved);
        return new AuthResponse(token, jwtService.getExpirationMs(), UserResponse.from(saved));
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtService.generateToken(user);
        return new AuthResponse(token, jwtService.getExpirationMs(), UserResponse.from(user));
    }
}
