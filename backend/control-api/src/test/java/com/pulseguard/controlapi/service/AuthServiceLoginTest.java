package com.pulseguard.controlapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.pulseguard.controlapi.domain.User;
import com.pulseguard.controlapi.dto.auth.AuthResponse;
import com.pulseguard.controlapi.dto.auth.LoginRequest;
import com.pulseguard.controlapi.exception.ApiErrorCode;
import com.pulseguard.controlapi.exception.ApiException;
import com.pulseguard.controlapi.repository.UserRepository;
import com.pulseguard.controlapi.security.CurrentUserService;
import com.pulseguard.controlapi.service.impl.AuthServiceImpl;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceLoginTest {

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
    void successfulLoginReturnsABearerTokenAndUserSummary() {
        User user = existingUser();
        when(authenticationManager.authenticate(any())).thenReturn(mockAuthentication());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(tokenService.generateAccessToken(user)).thenReturn("signed.jwt.value");
        when(tokenService.accessTokenValiditySeconds()).thenReturn(3600L);

        AuthResponse response = authService.login(new LoginRequest("User@Example.com", "correct-password"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt.value");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600L);
        assertThat(response.user().email()).isEqualTo("user@example.com");
    }

    /**
     * Every failure mode must be indistinguishable, otherwise the endpoint
     * becomes an oracle for which addresses are registered.
     *
     * <p>Each case runs as its own invocation so a stubbed throw is never
     * triggered while arranging the next one.
     */
    @ParameterizedTest(name = "{0} still yields a generic 401")
    @MethodSource("authenticationFailures")
    void everyAuthenticationFailureLooksTheSame(String label, RuntimeException failure) {
        doThrow(failure).when(authenticationManager).authenticate(any());

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "whatever")))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid email or password")
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
    }

    static Stream<Arguments> authenticationFailures() {
        return Stream.of(
                Arguments.of("wrong password", new BadCredentialsException("wrong password")),
                Arguments.of("unknown email", new UsernameNotFoundException("no such user")),
                Arguments.of("disabled account", new DisabledException("account disabled")));
    }

    /** The plaintext password must never appear in a log line via toString(). */
    @Test
    void loginRequestNeverRendersItsPassword() {
        assertThat(new LoginRequest("user@example.com", "super-secret").toString())
                .doesNotContain("super-secret")
                .contains("user@example.com");
    }

    private static User existingUser() {
        return new User("user@example.com", "{bcrypt}hashed", "Example User");
    }

    private static Authentication mockAuthentication() {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "user@example.com", null, java.util.List.of());
    }
}
