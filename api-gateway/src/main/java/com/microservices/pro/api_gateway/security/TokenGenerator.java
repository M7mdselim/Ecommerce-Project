package com.microservices.pro.api_gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;

/**
 * TokenGenerator is a utility class containing a main method to generate a valid JWT token
 * using the configured secret key.
 * 
 * Why it exists:
 * To facilitate manual and integration testing of the JwtAuthFilter by generating signed tokens.
 */
public class TokenGenerator {

    public static void main(String[] args) {
        String secret = "microservices-pro-course-secret-key-2024-minimum-256-bits";
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "ROLE_ADMIN");

        String token = Jwts.builder()
                .setClaims(claims)
                .setSubject("user_12345") // User ID (sub)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 24)) // 24 hours
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();

        System.out.println("\n==================================================");
        System.out.println("Generated valid JWT token (expires in 24 hours):");
        System.out.println("Bearer " + token);
        System.out.println("==================================================\n");
    }
}
