package com.pulseguard.controlapi.service;

import com.pulseguard.controlapi.dto.auth.AuthResponse;
import com.pulseguard.controlapi.dto.auth.LoginRequest;
import com.pulseguard.controlapi.dto.auth.RegisterRequest;
import com.pulseguard.controlapi.dto.auth.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    UserResponse getCurrentUser();
}
