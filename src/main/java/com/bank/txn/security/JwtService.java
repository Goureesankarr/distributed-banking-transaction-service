package com.bank.txn.security;

import com.bank.txn.config.BankingProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Issues and verifies HS256 access tokens. */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(BankingProperties properties) {
        byte[] secret = Decoders.BASE64.decode(properties.getJwt().getSecret());
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "banking.jwt.secret must decode to at least 32 bytes (256 bits) for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.issuer = properties.getJwt().getIssuer();
        this.ttlSeconds = properties.getJwt().getAccessTokenTtl().toSeconds();
    }

    public String issue(String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(username)
                .claim("roles", roles)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @return the verified claims, or {@code null} when the token is missing,
     *         malformed, expired or signed with the wrong key.
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
