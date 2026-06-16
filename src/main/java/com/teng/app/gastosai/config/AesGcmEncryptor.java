package com.teng.app.gastosai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryptor for secrets at rest (user-supplied AI provider keys). The 256-bit key is
 * derived by SHA-256 over {@code gastos.ai.key-encryption-secret} (set AI_KEY_ENCRYPTION_SECRET in
 * production). Output is Base64(iv(12) || ciphertext+tag). No external dependency.
 */
@Component
public class AesGcmEncryptor {

	private static final int IV_LENGTH = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public AesGcmEncryptor(
			@Value("${gastos.ai.key-encryption-secret:gastos-dev-ai-key-encryption-secret-change-me}") String secret) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
			this.key = new SecretKeySpec(digest, "AES");
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Unable to initialise encryptor", e);
		}
	}

	public String encrypt(String plaintext) {
		if (plaintext == null) {
			return null;
		}
		try {
			byte[] iv = new byte[IV_LENGTH];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] combined = ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
			return Base64.getEncoder().encodeToString(combined);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	public String decrypt(String stored) {
		if (stored == null) {
			return null;
		}
		try {
			byte[] combined = Base64.getDecoder().decode(stored);
			byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
			byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Decryption failed", e);
		}
	}
}
