package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.auth.RegisterRequest;
import com.pulseguard.controlapi.dto.auth.UserResponse;
import com.pulseguard.controlapi.enums.SystemRole;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String RAW_PASSWORD = "SecurePassword123!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private TokenService tokenService;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void registerNormalizesEmailBeforeStoring() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}hashed");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(new RegisterRequest("  User@Example.COM  ", RAW_PASSWORD, "Example User"));

        assertThat(savedUser().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void registerStoresAHashRatherThanThePlaintextPassword() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn("{bcrypt}hashed");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(new RegisterRequest("user@example.com", RAW_PASSWORD, "Example User"));

        User saved = savedUser();
        verify(passwordEncoder).encode(RAW_PASSWORD);
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}hashed");
    }

    @Test
    void registerAlwaysCreatesAPlainUserAndNeverAnAdmin() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}hashed");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        UserResponse response =
                authService.register(new RegisterRequest("user@example.com", RAW_PASSWORD, "Example User"));

        assertThat(savedUser().getSystemRole()).isEqualTo(SystemRole.USER);
        assertThat(savedUser().isEnabled()).isTrue();
        assertThat(response.systemRole()).isEqualTo(SystemRole.USER);
    }

    @Test
    void registerRejectsAnEmailThatAlreadyExistsRegardlessOfCase() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() ->
                        authService.register(new RegisterRequest("USER@Example.com", RAW_PASSWORD, "Duplicate")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.EMAIL_ALREADY_REGISTERED);

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerTrimsTheDisplayName() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}hashed");
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        authService.register(new RegisterRequest("user@example.com", RAW_PASSWORD, "  Example User  "));

        assertThat(savedUser().getDisplayName()).isEqualTo("Example User");
    }

    private User savedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }
}
