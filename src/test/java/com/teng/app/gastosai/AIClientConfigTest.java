package com.teng.app.gastosai;

import com.sun.net.httpserver.HttpServer;
import com.teng.app.gastosai.config.AIClientConfig;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider base URLs used to be literals, which meant a local run had no way to avoid calling
 * out: the only thing between an evidence run and a live request was the operator remembering the
 * rule. These tests assert the value is configuration, that a dead value fails before any provider
 * is reached, and that the shipped defaults are still the real hosts.
 *
 * <p>Nothing here ever addresses a provider host — the only endpoint contacted is a loopback
 * server this test starts itself.
 */
class AIClientConfigTest {

	private final AIClientConfig config = new AIClientConfig();
	private HttpServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private AiManagedProperties managedProps() {
		AiManagedProperties props = new AiManagedProperties();
		props.setRequestTimeoutSeconds(5);
		return props;
	}

	private String baseUrlOf(HttpServer running) {
		return "http://127.0.0.1:" + running.getAddress().getPort();
	}

	/** A port nothing listens on: bound to find a free number, then released. */
	private static String deadBaseUrl() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		return "http://127.0.0.1:" + port;
	}

	private AtomicReference<String> recordPath(String path) {
		AtomicReference<String> seen = new AtomicReference<>();
		server.createContext(path, exchange -> {
			seen.set(exchange.getRequestURI().getPath());
			byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		return seen;
	}

	@Test
	void openAiRestClient_sendsToTheConfiguredBaseUrl() {
		AtomicReference<String> seen = recordPath("/v1/chat/completions");
		OpenAiProperties properties = new OpenAiProperties();
		properties.setApiKey("sk-not-a-real-key");

		RestClient client = config.openAiRestClient(properties, managedProps(), baseUrlOf(server));
		client.post().uri("/v1/chat/completions").body("{}").retrieve().body(String.class);

		assertThat(seen.get()).isEqualTo("/v1/chat/completions");
	}

	@Test
	void claudeRestClient_sendsToTheConfiguredBaseUrl() {
		AtomicReference<String> seen = recordPath("/v1/messages");
		ClaudeProperties properties = new ClaudeProperties();
		properties.setApiKey("sk-ant-not-a-real-key");

		RestClient client = config.claudeRestClient(properties, managedProps(), baseUrlOf(server) + "/v1");
		client.post().uri("/messages").body("{}").retrieve().body(String.class);

		assertThat(seen.get()).isEqualTo("/v1/messages");
	}

	@Test
	void openAiRestClient_deadBaseUrl_failsFastWithoutReachingAProvider() throws IOException {
		OpenAiProperties properties = new OpenAiProperties();
		properties.setApiKey("sk-not-a-real-key");
		RestClient client = config.openAiRestClient(properties, managedProps(), deadBaseUrl());

		assertThatThrownBy(() -> client.post().uri("/v1/chat/completions").body("{}").retrieve().body(String.class))
				.isInstanceOf(ResourceAccessException.class);
	}

	@Test
	void claudeRestClient_deadBaseUrl_failsFastWithoutReachingAProvider() throws IOException {
		ClaudeProperties properties = new ClaudeProperties();
		properties.setApiKey("sk-ant-not-a-real-key");
		RestClient client = config.claudeRestClient(properties, managedProps(), deadBaseUrl() + "/v1");

		assertThatThrownBy(() -> client.post().uri("/messages").body("{}").retrieve().body(String.class))
				.isInstanceOf(ResourceAccessException.class);
	}

	/**
	 * Read from the file rather than the classpath on purpose: {@code src/test/resources} shadows
	 * this file for the suite, and the shipped default is what production actually gets.
	 */
	@Test
	void shippedDefaults_areTheRealProviderHosts() throws IOException {
		String properties = Files.readString(Path.of("src/main/resources/application.properties"));

		assertThat(properties).contains("gastos.openai.base-url=${OPENAI_API_BASE_URL:https://api.openai.com}");
		assertThat(properties).contains("gastos.claude.base-url=${CLAUDE_API_BASE_URL:https://api.anthropic.com/v1}");
	}
}
