package com.teng.app.gastosai;

import com.teng.app.gastosai.config.JacksonTimeConfig;
import com.teng.app.gastosai.dto.ExpenseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the {@code +08:00} wire format.
 *
 * <p>Timestamps used to serialize naive ({@code 2026-06-26T12:00:00}) while the published
 * contract declared them {@code format: date-time}, which requires an offset. Clients in other
 * timezones resolved them against their own zone, which — with day/month rollups computed in
 * {@code Asia/Manila} — could bucket an expense into the wrong day and wrong monthly total
 * without anything failing.
 *
 * <p>These assertions are the regression guard. If someone reintroduces a {@code @JsonFormat}
 * pattern or removes {@link JacksonTimeConfig}, this fails rather than quietly shipping
 * ambiguous timestamps to every generated client.
 */
@SpringBootTest
class TimestampOffsetTest {

	@Autowired
	ObjectMapper objectMapper;

	@Test
	void serializesTimestampsWithTheManilaOffset() {
		ExpenseResponse response = new ExpenseResponse(
				1L, new BigDecimal("150.75"), "Food",
				LocalDateTime.of(2026, 6, 26, 12, 0, 0),
				"Lunch", "EXPENSE", false, "PHP", null, null);

		String json = objectMapper.writeValueAsString(response);

		assertTrue(json.contains("\"date\":\"2026-06-26T12:00:00+08:00\""),
				"Timestamp must carry the +08:00 offset. Got: " + json);
	}

	@Test
	void serializedTimestampParsesAsTheSameInstant() {
		LocalDateTime wallClock = LocalDateTime.of(2026, 6, 26, 12, 0, 0);
		ExpenseResponse response = new ExpenseResponse(
				1L, BigDecimal.ONE, "Food", wallClock, "Lunch", "EXPENSE", false, "PHP", null, null);

		String date = objectMapper.readTree(objectMapper.writeValueAsString(response)).get("date").asString();
		OffsetDateTime parsed = OffsetDateTime.parse(date);

		// The wall-clock reading is unchanged — this is why the change is not breaking.
		assertEquals(wallClock, parsed.toLocalDateTime());
		assertEquals(8 * 3600, parsed.getOffset().getTotalSeconds());
	}

	@Test
	void stillAcceptsOffsetlessInputFromExistingClients() {
		// Every client written against the old format sends naive timestamps, as does the AI
		// parser (its JSON comes from a language model). Those must keep working.
		String json = """
				{"amount":10,"date":"2026-06-26T12:00:00","description":"Lunch"}""";

		var parsed = objectMapper.readValue(json, com.teng.app.gastosai.dto.ExpenseRequest.class);

		assertEquals(LocalDateTime.of(2026, 6, 26, 12, 0, 0), parsed.date());
	}

	@Test
	void convertsOffsetInputIntoManilaRatherThanTruncatingIt() {
		// 04:00Z is noon in Manila. A client sending a fully-qualified timestamp should land on
		// the same instant, not have its offset silently dropped.
		String json = """
				{"amount":10,"date":"2026-06-26T04:00:00Z","description":"Lunch"}""";

		var parsed = objectMapper.readValue(json, com.teng.app.gastosai.dto.ExpenseRequest.class);

		assertEquals(LocalDateTime.of(2026, 6, 26, 12, 0, 0), parsed.date());
	}
}
