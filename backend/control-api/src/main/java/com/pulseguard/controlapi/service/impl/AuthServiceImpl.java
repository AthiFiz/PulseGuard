package com.pulseguard.controlapi.service.impl;

import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.auth.AuthResponse;
import com.pulseguard.controlapi.dto.auth.AuthUserSummary;
import com.pulseguard.controlapi.dto.auth.LoginRequest;
import com.pulseguard.controlapi.dto.auth.RegisterRequest;
import com.pulseguard.controlapi.dto.auth.UserResponse;
import com.pulseguard.controlapi.enums.SystemRole;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.util.EmailNormalizer;
import com.pulseguard.controlapi.service.AuthService;
import com.pulseguard.controlapi.service.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registration, login, and resolving the caller's own account. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final CurrentUserService currentUserService;

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw ApiException.emailAlreadyRegistered();
        }

        User user = new User(email, passwordEncoder.encode(request.password()), request.displayName().trim());
        user.setSystemRole(SystemRole.USER);
        user.setEnabled(true);

        User saved = userRepository.save(user);
        log.info("User registered: id={}", saved.getId());
        return UserResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (AuthenticationException ex) {
            log.info("Login failed for a submitted email: {}", ex.getClass().getSimpleName());
            throw ApiException.invalidCredentials();
        }

        User user = userRepository.findByEmail(email).orElseThrow(ApiException::invalidCredentials);
        log.info("Login succeeded: userId={}", user.getId());
        return AuthResponse.bearer(
                tokenService.generateAccessToken(user),
                tokenService.accessTokenValiditySeconds(),
                AuthUserSummary.from(user));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        return userRepository.findById(currentUserService.requireCurrentUserId())
                .map(UserResponse::from)
                .orElseThrow(ApiException::userNotFound);
    }
}
