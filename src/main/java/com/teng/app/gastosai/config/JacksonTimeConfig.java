package com.teng.app.gastosai.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Serializes every {@link LocalDateTime} on the API with an explicit {@code +08:00} offset.
 *
 * <p>The domain stores wall-clock Manila time in {@code LocalDateTime} columns, and Jackson
 * renders that as {@code 2026-06-26T12:00:00} — a value whose meaning depends on who reads it.
 * The OpenAPI contract declares these fields {@code format: date-time}, which per RFC 3339
 * requires an offset, so the published contract has been making a promise the payload did not
 * keep. A generated client is entitled to treat the value as an absolute instant; without an
 * offset it silently resolves against the *reader's* timezone instead of Manila's.
 *
 * <p>That is not hypothetical here: day and month rollups are computed in {@code Asia/Manila},
 * so a client in any other zone could place an expense in the wrong day — and therefore the
 * wrong monthly total — with nothing failing.
 *
 * <p>This is deliberately <strong>not</strong> a breaking contract change. The declared type is
 * unchanged and the wall-clock reading is unchanged; the value simply becomes unambiguous.
 * {@code 2026-06-26T12:00:00} and {@code 2026-06-26T12:00:00+08:00} denote the same instant to
 * any client that was already assuming Manila — and the correct one for every client that
 * was not. Ship it as a minor contract version.
 *
 * <p>Deserialization stays permissive on purpose: existing clients (and the AI parser, which
 * gets its JSON from a language model) send offset-less values, and those must keep working.
 * An inbound offset is honoured by converting into Manila before dropping to
 * {@code LocalDateTime}, so a client that starts sending fully-qualified timestamps is
 * interpreted correctly rather than truncated.
 *
 * <p>Storage is untouched — the columns remain Manila wall-clock. Moving storage to UTC, which
 * is what {@code CONTRACT.md} ultimately calls for, is a separate migration; this fixes the
 * wire format, which is the part clients actually depend on.
 */
@Configuration
public class JacksonTimeConfig {

	public static final ZoneId APP_ZONE = ZoneId.of("Asia/Manila");

	@Bean
	JsonMapperBuilderCustomizer manilaOffsetDateTimes() {
		SimpleModule module = new SimpleModule("gastosai-manila-time");
		module.addSerializer(LocalDateTime.class, new ManilaOffsetSerializer());
		module.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
		return builder -> builder.addModule(module);
	}

	static final class ManilaOffsetSerializer extends ValueSerializer<LocalDateTime> {
		@Override
		public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
			gen.writeString(value.atZone(APP_ZONE).toOffsetDateTime()
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
		}
	}

	static final class LenientLocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {
		@Override
		public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
			String raw = p.getString();
			if (raw == null || raw.isBlank()) {
				return null;
			}
			try {
				// Offset supplied: convert into Manila so the instant is preserved rather than
				// having the offset silently discarded.
				return OffsetDateTime.parse(raw).atZoneSameInstant(APP_ZONE).toLocalDateTime();
			}
			catch (DateTimeParseException notOffsetDateTime) {
				return LocalDateTime.parse(raw);
			}
		}
	}
}
