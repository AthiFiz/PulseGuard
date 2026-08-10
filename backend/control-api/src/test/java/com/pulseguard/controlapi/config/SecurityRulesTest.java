package com.pulseguard.controlapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseguard.controlapi.dto.auth.AuthResponse;
import com.pulseguard.controlapi.dto.auth.AuthUserSummary;
import com.pulseguard.controlapi.dto.auth.UserResponse;
import com.pulseguard.controlapi.enums.SystemRole;
import com.pulseguard.controlapi.security.SecurityErrorResponder;
import com.pulseguard.controlapi.security.SystemRoleJwtAuthenticationConverter;
import com.pulseguard.controlapi.service.AuthService;
import com.pulseguard.controlapi.service.ProjectMemberService;
import com.pulseguard.controlapi.service.ProjectService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies which endpoints are public and which demand a token, using the real
 * {@link SecurityConfig} rather than a stub.
 *
 * <p>The {@link JwtDecoder} is mocked because these tests never present a
 * token; token validation itself is covered by {@code TokenServiceTest}.
 */
@WebMvcTest
@Import({SecurityConfig.class, SecurityErrorResponder.class, SystemRoleJwtAuthenticationConverter.class})
@EnableConfigurationProperties(CorsProperties.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Needed by the authentication manager; never exercised without a login here. */
    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private ProjectMemberService projectMemberService;

    @Test
    void systemInfoIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/system/info")).andExpect(status().isOk());
    }

    @Test
    void registrationIsPublic() throws Exception {
        Mockito.when(authService.register(ArgumentMatchers.any()))
                .thenReturn(new UserResponse(
                        1L, "user@example.com", "Example User", SystemRole.USER, true, Instant.now()));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"email":"user@example.com","password":"SecurePassword123!","displayName":"Example User"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void loginIsPublic() throws Exception {
        Mockito.when(authService.login(ArgumentMatchers.any()))
                .thenReturn(AuthResponse.bearer(
                        "token",
                        3600,
                        new AuthUserSummary(1L, "user@example.com", "Example User", SystemRole.USER)));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"SecurePassword123!"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void currentUserRequiresAuthenticationAndAnswersWithJson() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void listingProjectsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void creatingAProjectRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Production APIs"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void projectMemberEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects/1/members")).andExpect(status().isUnauthorized());
    }

    /** Anything not explicitly permitted must be protected by default. */
    @Test
    void unknownApiPathsAreProtectedRatherThanOpen() throws Exception {
        mockMvc.perform(get("/api/v1/some/future/endpoint")).andExpect(status().isUnauthorized());
    }

    @Test
    void securityFailuresNeverReturnAnHtmlLoginPage() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> {
                    String contentType = result.getResponse().getContentType();
                    if (contentType == null || !contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
                        throw new AssertionError("Expected a JSON error body but got: " + contentType);
                    }
                });
    }
}
