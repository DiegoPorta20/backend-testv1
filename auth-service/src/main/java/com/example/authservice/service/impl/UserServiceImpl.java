package com.example.authservice.service.impl;

import com.example.authservice.dto.UserResponse;
import com.example.authservice.exception.BusinessException;
import com.example.authservice.repository.UserRepository;
import com.example.authservice.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse findByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
