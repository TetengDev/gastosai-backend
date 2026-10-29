package com.teng.app.gastosai;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEN-506: with pgjdbc's default socketTimeout of 0, one stalled read held its connection forever
 * and the requests queued behind the pool hung with it. The suite runs on its own
 * {@code src/test/resources/application.properties}, so a Spring context here would never see the
 * shipped values; this binds the real files into a {@link HikariConfig} the way Boot does.
 */
class DatasourceTimeoutPropertiesTest {

	private static HikariConfig bind(String... files) throws IOException {
		MutablePropertySources sources = new MutablePropertySources();
		for (String file : files) {
			// Later files win, as a profile file overrides the base one.
			for (PropertySource<?> source : new PropertiesPropertySourceLoader().load(file,
					new FileSystemResource("src/main/resources/" + file))) {
				sources.addFirst(source);
			}
		}
		HikariConfig config = new HikariConfig();
		new Binder(ConfigurationPropertySources.from(sources)).bind("spring.datasource.hikari",
				Bindable.ofInstance(config));
		return config;
	}

	private static void assertBounded(HikariConfig config) {
		// The key reaches the driver verbatim; pgjdbc looks it up case-sensitively.
		String socketTimeout = config.getDataSourceProperties().getProperty("socketTimeout");
		assertThat(socketTimeout).as("pgjdbc socketTimeout, seconds").isNotNull();
		assertThat(Integer.parseInt(socketTimeout)).isBetween(1, 60);
		assertThat(config.getConnectionTimeout()).isBetween(1L, 30_000L);
	}

	@Test
	void baseProfileBoundsSocketReadsAndPoolWaits() throws IOException {
		assertBounded(bind("application.properties"));
	}

	@Test
	void prodProfileKeepsBothBounds() throws IOException {
		assertBounded(bind("application.properties", "application-prod.properties"));
	}

}
