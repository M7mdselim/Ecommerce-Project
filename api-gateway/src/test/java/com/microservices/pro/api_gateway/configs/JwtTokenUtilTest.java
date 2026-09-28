package com.microservices.pro.api_gateway.configs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenUtilTest {

    private JwtTokenUtil jwtTokenUtil;
    private static final String SECRET = "microservices-pro-course-secret-key-2024-minimum-256-bits";

    @BeforeEach
    void setUp() {
        jwtTokenUtil = new JwtTokenUtil();
        ReflectionTestUtils.setField(jwtTokenUtil, "secret", SECRET);
    }

    @Test
    void generateToken_producesThreePartJwt() {
        String token = jwtTokenUtil.generateToken("user-123", List.of("ROLE_USER"));

        assertThat(token).isNotNull();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload).contains("\"sub\":\"user-123\"");
        assertThat(payload).contains("ROLE_USER");
    }

    @Test
    void generateToken_withMultipleRoles_includesAllRolesInPayload() {
        String token = jwtTokenUtil.generateToken("admin-456", List.of("ROLE_ADMIN", "ROLE_USER"));

        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));

        assertThat(payload).contains("\"sub\":\"admin-456\"");
        assertThat(payload).contains("ROLE_ADMIN");
        assertThat(payload).contains("ROLE_USER");
    }
}
