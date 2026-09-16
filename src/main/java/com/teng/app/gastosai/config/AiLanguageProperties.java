package com.teng.app.gastosai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The languages the AI may be told to write in.
 *
 * <p>Configuration rather than code because the allow-list exists for injection safety, not for
 * capability: the model can write far more languages than this list names, and adding one should
 * not require editing an enum, two clients and a contract.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gastos.ai.language")
public class AiLanguageProperties {

	/** In picker order. English must be present; it is the default and the fallback. */
	private List<Entry> supported = new ArrayList<>();

	/**
	 * Both values are required and neither may be blank; {@code AiLanguageRegistry} fails startup
	 * with the offending entry's position rather than letting a missing one reach a prompt.
	 *
	 * @param code        BCP-47 code, stored on the user row and published in the contract
	 * @param displayName written in its own language — a picker that says "Japanese" to someone who
	 *                    reads Japanese is the wrong way round
	 */
	public record Entry(String code, String displayName) {
	}
}
