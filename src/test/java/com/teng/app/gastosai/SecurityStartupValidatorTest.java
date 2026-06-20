package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JwtProperties;
import com.teng.app.gastosai.config.SecurityStartupValidator;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityStartupValidatorTest {

    private static final String DEV_JWT = "gastos-dev-secret-key-change-in-production-min-32-chars";
    private static final String DEV_AI = "gastos-dev-ai-key-encryption-secret-change-me";
    private static final String SAFE_JWT = "super-safe-jwt-secret-for-production-with-enough-length";
    private static final String SAFE_AI = "super-safe-ai-key-encryption-secret-for-production";

    private SecurityStartupValidator validatorWith(
            String jwtSecret,
            String aiKeySecret,
            String datasourceUrl,
            String[] activeProfiles) {
        JwtProperties jwtProperties = new JwtProperties(jwtSecret, 28800000L);
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(activeProfiles);

        SecurityStartupValidator v = new SecurityStartupValidator(jwtProperties, environment);
        ReflectionTestUtils.setField(v, "datasourceUrl", datasourceUrl);
        ReflectionTestUtils.setField(v, "seedSampleData", false);
        ReflectionTestUtils.setField(v, "aiKeyEncryptionSecret", aiKeySecret);
        return v;
    }

    @Test
    void prodProfile_devJwtSecret_throws() {
        var v = validatorWith(DEV_JWT, SAFE_AI, "jdbc:h2:mem:test", new String[]{"prod"});
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void prodProfile_devAiKeySecret_throws() {
        var v = validatorWith(SAFE_JWT, DEV_AI, "jdbc:h2:mem:test", new String[]{"prod"});
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_KEY_ENCRYPTION_SECRET");
    }

    @Test
    void remoteDatabase_devJwtSecret_throws() {
        var v = validatorWith(DEV_JWT, SAFE_AI, "jdbc:postgresql://db.supabase.co/gastos", new String[]{});
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void remoteDatabase_devAiKeySecret_throws() {
        var v = validatorWith(SAFE_JWT, DEV_AI, "jdbc:postgresql://db.supabase.co/gastos", new String[]{});
        assertThatThrownBy(v::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_KEY_ENCRYPTION_SECRET");
    }

    @Test
    void devProfile_devSecrets_doesNotThrow() {
        var v = validatorWith(DEV_JWT, DEV_AI, "jdbc:h2:mem:test", new String[]{});
        assertThatCode(v::validate).doesNotThrowAnyException();
    }

    @Test
    void prodProfile_safeSecrets_doesNotThrow() {
        var v = validatorWith(SAFE_JWT, SAFE_AI, "jdbc:h2:mem:test", new String[]{"prod"});
        assertThatCode(v::validate).doesNotThrowAnyException();
    }
}
