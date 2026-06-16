package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AesGcmEncryptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AesGcmEncryptorTest {

	private final AesGcmEncryptor encryptor = new AesGcmEncryptor("unit-test-secret");

	@Test
	void encryptThenDecrypt_roundTrips() {
		String plain = "sk-proj-abc123-secret-key";
		String encrypted = encryptor.encrypt(plain);

		assertThat(encrypted).isNotNull().isNotEqualTo(plain);
		assertThat(encryptor.decrypt(encrypted)).isEqualTo(plain);
	}

	@Test
	void encrypt_sameInput_producesDifferentCiphertext() {
		String plain = "sk-proj-abc123";
		assertThat(encryptor.encrypt(plain)).isNotEqualTo(encryptor.encrypt(plain));
	}

	@Test
	void nullPassesThrough() {
		assertThat(encryptor.encrypt(null)).isNull();
		assertThat(encryptor.decrypt(null)).isNull();
	}
}
