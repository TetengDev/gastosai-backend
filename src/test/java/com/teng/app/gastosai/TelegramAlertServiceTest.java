package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AlertProperties;
import com.teng.app.gastosai.service.TelegramAlertService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class TelegramAlertServiceTest {

    @Test
    void inactive_send_isNoOpAndNeverThrows() {
        AlertProperties props = new AlertProperties(); // enabled=false by default
        TelegramAlertService service = new TelegramAlertService(props);

        assertThatCode(() -> service.send("should not be sent")).doesNotThrowAnyException();
    }
}
