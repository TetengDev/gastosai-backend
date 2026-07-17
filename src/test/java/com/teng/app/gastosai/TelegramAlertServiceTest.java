package com.teng.app.gastosai;

import com.teng.app.gastosai.config.AlertProperties;
import com.teng.app.gastosai.service.TelegramAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;

class TelegramAlertServiceTest {

    private AlertProperties activeProps() {
        AlertProperties props = new AlertProperties();
        props.setEnabled(true);
        props.setBotToken("BOTTOKEN");
        props.setChatId("123");
        return props;
    }

    @Test
    void inactive_send_isNoOpAndNeverThrows() {
        AlertProperties props = new AlertProperties(); // enabled=false by default
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramAlertService service = new TelegramAlertService(props, builder.build());

        assertThatCode(() -> service.send("should not be sent")).doesNotThrowAnyException();
        server.verify(); // no request expected, none made
    }

    @Test
    void active_send_postsToBotApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramAlertService service = new TelegramAlertService(activeProps(), builder.build());
        server.expect(requestTo(containsString("/botBOTTOKEN/sendMessage")))
                .andExpect(method(POST))
                .andRespond(withSuccess());

        service.send("hello");

        server.verify();
    }

    @Test
    void active_send_swallowsHttpFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramAlertService service = new TelegramAlertService(activeProps(), builder.build());
        server.expect(requestTo(containsString("/sendMessage"))).andRespond(withServerError());

        assertThatCode(() -> service.send("boom")).doesNotThrowAnyException();
        server.verify();
    }
}
