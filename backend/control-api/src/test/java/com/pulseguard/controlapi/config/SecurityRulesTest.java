package com.pulseguard.controlapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pulseguard.controlapi.dto.auth.AuthResponse;
import com.pulseguard.controlapi.dto.auth.AuthUserSummary;
import com.pulseguard.controlapi.dto.auth.UserResponse;
import com.pulseguard.controlapi.enums.SystemRole;
import com.pulseguard.controlapi.security.SecurityErrorResponder;
import com.pulseguard.controlapi.security.SystemRoleJwtAuthenticationConverter;
import com.pulseguard.controlapi.service.AuthService;
import com.pulseguard.controlapi.service.DashboardService;
import com.pulseguard.controlapi.service.IncidentService;
import com.pulseguard.controlapi.service.MonitorService;
import com.pulseguard.controlapi.service.MonitoringQueryService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.BadJwtException;
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

    @MockitoBean
    private MonitorService monitorService;

    @MockitoBean
    private MonitoringQueryService monitoringQueryService;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private IncidentService incidentService;

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

    /**
     * The monitor endpoints were never added to SecurityConfig — they are
     * protected purely by anyRequest().authenticated(), which is the point of
     * writing the rule that way.
     */
    @Test
    void everyMonitorEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects/1/monitors")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/projects/1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/monitors/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/monitors/1/pause")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/monitors/1/resume")).andExpect(status().isUnauthorized());
    }

    /** The Task 06 reporting endpoints are protected by the same default rule. */
    @Test
    void everyMonitoringReadEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/monitors/1/checks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/monitors/1/statistics")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/projects/1/dashboard")).andExpect(status().isUnauthorized());
    }

    /** Incidents are read-only, and reading them still needs a token. */
    @Test
    void everyIncidentEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/projects/1/incidents")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/incidents/1")).andExpect(status().isUnauthorized());
    }

    /** Anything not explicitly permitted must be protected by default. */
    @Test
    void unknownApiPathsAreProtectedRatherThanOpen() throws Exception {
        mockMvc.perform(get("/api/v1/some/future/endpoint")).andExpect(status().isUnauthorized());
    }

    /**
     * Every test above omits the header entirely. A token that is <em>present
     * and bad</em> takes a different route through the filter chain — the
     * decoder throws rather than the entry point firing on an anonymous
     * request — and must still land on the same JSON 401 rather than a 500 with
     * a stack trace.
     *
     * <p>The reasons a real token is rejected (expiry, a foreign issuer, a
     * different signing key) are covered in {@code TokenServiceTest} against the
     * genuine decoder; what matters here is that the failure is presented well.
     */
    @Test
    void aRejectedTokenIsAnsweredWithJsonRatherThanAServerError() throws Exception {
        Mockito.when(jwtDecoder.decode(ArgumentMatchers.anyString()))
                .thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

        mockMvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** The rejection message names the signing failure; the client must not see it. */
    @Test
    void aRejectedTokenNeverExplainsWhyItWasRejected() throws Exception {
        Mockito.when(jwtDecoder.decode(ArgumentMatchers.anyString()))
                .thenThrow(new BadJwtException("Signed JWT rejected: Invalid signature"));

        String body = mockMvc.perform(
                        get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("signature")
                .doesNotContain("Signed JWT")
                .doesNotContain("Exception");
    }

    /**
     * An unsupported scheme is not a token at all. It must be treated as no
     * credentials rather than as a malformed one, and never parsed.
     */
    @Test
    void aNonBearerAuthorizationHeaderIsTreatedAsNoCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/projects").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        Mockito.verify(jwtDecoder, Mockito.never()).decode(ArgumentMatchers.anyString());
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
