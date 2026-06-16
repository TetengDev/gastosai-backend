package com.teng.app.gastosai.config;

/**
 * Per-request holder for the authenticated user's own AI provider keys. Populated by
 * {@link AiKeyContextInterceptor} on /ai/** requests and read by the RestClient interceptors in
 * {@link AIClientConfig}. When unset (or null), the clients fall back to the owner's configured key.
 */
public final class AiKeyContext {

	private AiKeyContext() {
	}

	private record Keys(String openai, String claude) {
	}

	private static final ThreadLocal<Keys> HOLDER = new ThreadLocal<>();

	public static void set(String openaiKey, String claudeKey) {
		HOLDER.set(new Keys(openaiKey, claudeKey));
	}

	public static String openai() {
		Keys keys = HOLDER.get();
		return keys == null ? null : keys.openai();
	}

	public static String claude() {
		Keys keys = HOLDER.get();
		return keys == null ? null : keys.claude();
	}

	public static void clear() {
		HOLDER.remove();
	}
}
