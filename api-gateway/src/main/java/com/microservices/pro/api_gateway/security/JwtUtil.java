package com.microservices.pro.api_gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JwtUtil is responsible for validating JWT tokens and extracting their claims.
 * It uses the HS256 HMAC algorithm for signature verification.
 * 
 * Why it exists:
 * In a microservices architecture, the API Gateway acts as the entry point and
 * the single security boundary. This class provides the logic to parse and verify
 * the integrity of tokens before forwarding traffic downstream.
 */
@Component
public class JwtUtil {

    // Inject the secret key configured in application properties.
    // The secret must be at least 256 bits (32 bytes) long to be secure.
    @Value("${jwt.secret}")
    private String secret;

    /**
     * Helper method to generate the cryptographic signing key from the secret string.
     * Uses keys helper class to convert string bytes into a SecretKey instance.
     * Why it is needed: Jwts parser requires a SecretKey object to check signature.
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates the provided token against the configured secret key.
     * 
     * Why it is needed:
     * To verify that the token was not modified, is signed by our authentic key,
     * and has not expired.
     *
     * @param token the JWT string
     * @return the verified Claims body containing subject, role, etc.
     * @throws io.jsonwebtoken.JwtException if token signature is invalid or expired
     */
    public Claims validateToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody(); // Throws JwtException automatically when token is invalid or expired
    }
}
