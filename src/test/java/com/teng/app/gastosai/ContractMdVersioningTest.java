package com.teng.app.gastosai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the TEN-270 carve-out in CONTRACT.md: a widened output type is a minor bump, a narrowed
 * one is still major.
 *
 * <p>TEN-169 hit this ambiguity directly — publishing {@code title} as
 * {@code ["string","null"]} read as "changed type -> major" under the old, unqualified text, even
 * though the endpoint had always been able to return null. {@code pr-review-auditor} ruled the
 * minor bump correct and asked that the distinction be written down so the argument does not have
 * to be re-run. This test does not re-litigate that ruling; it only asserts the words that record
 * it are still in the file, since nothing else in the build would notice if a future edit dropped
 * them.
 */
class ContractMdVersioningTest {

	private static final Path CONTRACT_MD = Path.of("CONTRACT.md");

	@Test
	void versioningSectionDistinguishesWideningFromNarrowing() throws IOException {
		String contract = Files.readString(CONTRACT_MD);

		assertTrue(contract.contains("widen"),
				"CONTRACT.md's versioning section no longer mentions widening an output type — "
						+ "the TEN-270 carve-out (widened output = minor) appears to have been removed.");
		assertTrue(contract.contains("narrow"),
				"CONTRACT.md's versioning section no longer mentions narrowing an output type — "
						+ "the TEN-270 carve-out (narrowed output = major) appears to have been removed.");

		int versioningStart = contract.indexOf("## Versioning");
		assertTrue(versioningStart >= 0, "CONTRACT.md must have a Versioning section.");
		int nextSection = contract.indexOf("\n## ", versioningStart + 1);
		String versioningSection = nextSection >= 0
				? contract.substring(versioningStart, nextSection)
				: contract.substring(versioningStart);

		assertTrue(versioningSection.contains("widen"),
				"The widening rule must live in the Versioning section itself, not elsewhere in the "
						+ "file, so a reader deciding a bump finds it in one place.");
		assertTrue(versioningSection.contains("narrow"),
				"The narrowing rule must live in the Versioning section itself, not elsewhere in the "
						+ "file, so a reader deciding a bump finds it in one place.");
		assertTrue(versioningSection.toLowerCase().contains("minor"),
				"The widening carve-out must say it lands on the minor side of the line.");
		assertTrue(versioningSection.toLowerCase().contains("major"),
				"Narrowing must still say it lands on the major side of the line.");
	}
}
