package com.microservices.pro.api_gateway.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * JwtTokenUtil — Session 20 utility for generating HS256-signed JWTs for testing.
 *
 * <p>This is NOT a security filter. It exists only to generate tokens that can be
 * used in integration tests and Postman requests against the gateway.
 *
 * <p>The old {@code JwtUtil.java} that manually parsed/validated tokens is deleted.
 * Token validation is now handled entirely by Spring Security's
 * {@code oauth2-resource-server} in {@link SecurityConfig}.
 *
 * <p>Token format follows the compact JWT structure:
 * {@code Base64(header).Base64(payload).Base64(signature)}
 * with HS256 algorithm and the shared HMAC secret.
 */
@Component
public class JwtTokenUtil {

    @Value("${jwt.secret}")
    private String secret;

    private static final long EXPIRY_SECONDS = 3600L; // 1 hour

    /**
     * Generates a signed JWT for a given subject and list of roles.
     *
     * @param subject the user identifier (becomes the {@code sub} claim)
     * @param roles   list of roles (e.g. {@code ["ROLE_ADMIN"]} or {@code ["ROLE_USER"]})
     * @return a compact JWT string ready for use in {@code Authorization: Bearer <token>}
     */
    public String generateToken(String subject, List<String> roles) {
        long now = Instant.now().getEpochSecond();

        // Build JSON parts using simple string formatting (no external library)
        String header = base64UrlEncode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64UrlEncode(
                "{\"sub\":\"" + subject + "\"," +
                "\"roles\":" + toJsonArray(roles) + "," +
                "\"iat\":" + now + "," +
                "\"exp\":" + (now + EXPIRY_SECONDS) + "}");

        String signingInput = header + "." + payload;
        String signature = hmacSha256(signingInput, secret);

        return signingInput + "." + signature;
    }

    private static String base64UrlEncode(String input) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append("\"").append(values.get(i)).append("\"");
            if (i < values.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}
