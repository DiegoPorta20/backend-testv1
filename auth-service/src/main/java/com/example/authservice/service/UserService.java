package com.example.authservice.service;

import com.example.authservice.dto.UserResponse;

import java.util.List;

public interface UserService {
    List<UserResponse> findAll();
    UserResponse findByEmail(String email);
}
