package com.teng.app.gastosai.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtUtil {

	private final JwtProperties jwtProperties;

	public String generate(String email) {
		return Jwts.builder()
				.subject(email)
				.issuedAt(new Date())
				.expiration(new Date(System.currentTimeMillis() + jwtProperties.expirationMs()))
				.signWith(key())
				.compact();
	}

	public String extractEmail(String token) {
		return parse(token).getPayload().getSubject();
	}

	public boolean isValid(String token) {
		try {
			parse(token);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private Jws<Claims> parse(String token) {
		return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token);
	}

	private SecretKey key() {
		return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
	}
}
