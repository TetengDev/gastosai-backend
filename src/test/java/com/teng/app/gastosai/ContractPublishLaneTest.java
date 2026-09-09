package com.teng.app.gastosai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the TEN-345 release lane: a contract version bump that lands on {@code main} publishes
 * itself and is tagged afterwards.
 *
 * <p>The failure this replaces was silent by construction. {@code publish-contract.yml} used to
 * trigger only on a {@code contract-v*} tag, so a bump that merged without a hand-cut tag made no
 * job fail — none ran. Ten bumps landed that way between 2026-07-28 and 2026-09-04 and none were
 * published; the symptom appeared five weeks later as a client unable to resolve its pin. Nothing
 * else in the build would notice if a future edit dropped the {@code push: branches: [main]}
 * trigger and put the workflow back behind the tag, which is why these assertions exist.
 *
 * <p>The ordering assertion is not cosmetic: the tag must be written after {@code npm publish}
 * succeeds, so that a tag always means a published package. A tag cut first would be the same
 * failure in mirror image — a version that looks published and is not.
 */
class ContractPublishLaneTest {

	private static final Path WORKFLOW = Path.of(".github/workflows/publish-contract.yml");
	private static final Path README = Path.of("contract/README.md");

	@Test
	void publishWorkflowRunsOnAPushToMainAndNotOnlyOnATag() throws IOException {
		String workflow = Files.readString(WORKFLOW);

		int onSection = workflow.indexOf("\non:");
		assertTrue(onSection >= 0, "publish-contract.yml must declare its triggers.");
		int jobs = workflow.indexOf("\njobs:");
		assertTrue(jobs > onSection, "publish-contract.yml must declare jobs after its triggers.");
		String triggers = workflow.substring(onSection, jobs);

		assertTrue(triggers.contains("branches:") && triggers.contains("- main"),
				"publish-contract.yml must trigger on a push to main. Without it the workflow only "
						+ "runs when a human remembers to cut a contract-v* tag, which is the TEN-345 "
						+ "failure: a missing trigger fails silently, because nothing runs.");
		assertTrue(triggers.contains("'contract-v*'"),
				"The manual contract-v* tag lane must stay reachable for backfills.");
	}

	@Test
	void aBumpIsDetectedFromTheVersionFieldItself() throws IOException {
		String workflow = Files.readString(WORKFLOW);

		assertTrue(workflow.contains("contract/package.json"),
				"The main lane must read contract/package.json to decide whether a publish is due — "
						+ "the version field is the trigger, not a separate step a human performs.");
		assertTrue(workflow.contains("github.event.before") || workflow.contains("BEFORE"),
				"The main lane must compare against the previous commit's version, so an ordinary "
						+ "push to main that does not touch the contract stays silent.");
		assertTrue(workflow.contains("publish=false"),
				"The workflow must have a green, publishing-nothing outcome for the ordinary push. A "
						+ "lane that runs the full suite on every main push gets routed around.");
	}

	@Test
	void theTagIsCutOnlyAfterThePublishSucceeds() throws IOException {
		String workflow = Files.readString(WORKFLOW);

		int publish = workflow.indexOf("run: npm publish");
		assertTrue(publish >= 0, "The workflow must still publish to GitHub Packages.");

		int tag = workflow.indexOf("git tag \"contract-v$VERSION\"");
		assertTrue(tag >= 0,
				"The main lane must cut the contract-v* tag itself, so no human has to remember it.");
		assertTrue(tag > publish,
				"The tag must be written after npm publish succeeds. A tag cut first would mean a "
						+ "version that looks published and is not — the TEN-345 failure in mirror image.");
		assertTrue(workflow.contains("git push origin \"contract-v$VERSION\""),
				"The tag must be pushed, or it exists only on the runner and records nothing.");
		assertTrue(workflow.contains("contents: write"),
				"The publish job needs contents: write to push the tag it cuts.");
	}

	@Test
	void readmeRecordsTheMechanismBesideTheBumpRule() throws IOException {
		String readme = Files.readString(README);

		int publishing = readme.indexOf("## Publishing");
		assertTrue(publishing >= 0, "contract/README.md must keep its Publishing section.");
		String section = readme.substring(publishing);

		assertTrue(section.contains("receipt"),
				"The Publishing section must record that the tag is the receipt of a publish rather "
						+ "than its trigger — the next reader needs the mechanism, not just the instruction.");
		assertTrue(section.contains("TEN-345"),
				"The Publishing section must name the issue that changed the mechanism, so the "
						+ "reasoning is traceable.");
	}
}
