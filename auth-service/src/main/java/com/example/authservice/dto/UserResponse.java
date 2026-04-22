package com.example.authservice.dto;

import com.example.authservice.domain.Role;
import com.example.authservice.domain.User;

public record UserResponse(Long id, String name, String email, Role role) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getName(), u.getEmail(), u.getRole());
    }
}
