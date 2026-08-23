package com.teng.app.gastosai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the TEN-269 record in KNOWN-GAPS.md: naive timestamps are a known, deliberate-for-now
 * deviation from Invariant 4 / CONTRACT.md, and this entry is its only durable record now that
 * PR #45 (where it was first raised) has scrolled out of view.
 */
class KnownGapsMdTest {

	private static final Path KNOWN_GAPS = Path.of("KNOWN-GAPS.md");

	@Test
	void namesTheNaiveTimestampInvariant() throws IOException {
		String content = readKnownGaps();
		assertTrue(content.contains("Invariant 4"), "should name the invariant it deviates from");
		assertTrue(content.contains("LocalDateTime"), "should name the naive type actually served");
	}

	@Test
	void namesTheHostDependentTimezoneCause() throws IOException {
		String content = normalizeWhitespace(readKnownGaps());
		assertTrue(
				content.contains("no JVM timezone is configured anywhere"),
				"should name that no JVM timezone is set, so the naive value is host-dependent");
	}

	@Test
	void namesTheCostOfFixingIt() throws IOException {
		String content = readKnownGaps();
		assertTrue(
				content.contains("breaking contract change"),
				"should name that fixing it is a breaking change across most response DTOs");
	}

	private static String readKnownGaps() throws IOException {
		return Files.readString(KNOWN_GAPS);
	}

	private static String normalizeWhitespace(String content) {
		return content.replaceAll("\\s+", " ");
	}
}
