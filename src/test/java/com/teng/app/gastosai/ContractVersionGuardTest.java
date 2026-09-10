package com.teng.app.gastosai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the PR-time half of TEN-345: the "Contract version is publishable" job in
 * continuous-integration.yml.
 *
 * <p>The release lane publishes a bump automatically once it reaches {@code main}, but there are
 * two bumps it can only skip — a version that already carries a {@code contract-v*} tag, and one
 * that moves backwards. A skipped publish is the original silent failure wearing a different hat,
 * so both are rejected on the PR instead.
 *
 * <p>The silence assertion matters as much as the failure ones. A check that speaks up on ordinary
 * PRs gets routed around within a week, and then guards nothing.
 */
class ContractVersionGuardTest {

	private static final Path CI = Path.of(".github/workflows/continuous-integration.yml");
	private static final Path PACKAGE_JSON = Path.of("contract/package.json");

	private static String guardJob() throws IOException {
		String ci = Files.readString(CI);
		int start = ci.indexOf("\n  contract-version:");
		assertTrue(start >= 0,
				"continuous-integration.yml must define the contract-version job. Without it a bump to "
						+ "an already-tagged or backwards version reaches main, where the release lane can "
						+ "only skip it — silently, which is the whole TEN-345 failure.");
		int next = ci.indexOf("\n  security-scan:", start);
		return next >= 0 ? ci.substring(start, next) : ci.substring(start);
	}

	@Test
	void theGuardRunsOnPullRequests() throws IOException {
		String job = guardJob();

		assertTrue(job.contains("github.event_name == 'pull_request'"),
				"The guard's whole point is to fail the PR, while the diff is still in front of "
						+ "someone. Running it only on main would report the problem after it landed.");
		assertTrue(job.contains("github.base_ref"),
				"The guard must read the base ref to compare the base branch's contract version "
						+ "against the head's.");
	}

	@Test
	void theGuardIsSilentWhenTheVersionIsUnchanged() throws IOException {
		String job = guardJob();

		assertTrue(job.contains("Contract version unchanged"),
				"The ordinary PR does not touch the contract version and must pass with one line.");
		int unchanged = job.indexOf("Contract version unchanged");
		int exitEarly = job.indexOf("exit 0", unchanged);
		assertTrue(exitEarly > unchanged,
				"The unchanged case must exit 0 immediately, before any of the failure checks below "
						+ "it. A check that flags the ordinary case is routed around within a week.");
	}

	@Test
	void theGuardRejectsAnAlreadyPublishedVersion() throws IOException {
		String job = guardJob();

		assertTrue(job.contains("refs/tags/contract-v$HEAD_VERSION"),
				"The guard must check whether the proposed version is already tagged — a tag means it "
						+ "is already on GitHub Packages and the republish would be rejected.");
		assertTrue(job.contains("already exists"),
				"The failure must say the version already exists, not merely that a check failed.");
	}

	@Test
	void theGuardRejectsABackwardsBump() throws IOException {
		String job = guardJob();

		assertTrue(job.contains("sort -V"),
				"The guard must compare the two versions by order, not equality.");
		assertTrue(job.contains("is not newer than"),
				"A backwards bump must fail with a message naming both versions: clients pin exact "
						+ "versions, so a reused number changes what an existing pin resolves to.");
	}

	@Test
	void theContractPackageDeclaresASemverVersionTheLaneCanPublish() throws IOException {
		String packageJson = Files.readString(PACKAGE_JSON);

		int versionAt = packageJson.indexOf("\"version\"");
		assertTrue(versionAt >= 0, "contract/package.json must declare a version.");
		String version = packageJson
				.substring(packageJson.indexOf('"', packageJson.indexOf(':', versionAt)) + 1)
				.split("\"")[0];

		assertTrue(version.matches("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$"),
				"contract/package.json declares '" + version + "', which the release lane's semver "
						+ "check would reject — the bump would fail instead of publishing.");
		assertEquals(3, version.split("-")[0].split("\\.").length,
				"The contract version is X.Y.Z; the tag the lane cuts is contract-v" + version + ".");
	}
}
