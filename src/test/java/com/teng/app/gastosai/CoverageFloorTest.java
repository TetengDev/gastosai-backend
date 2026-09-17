package com.teng.app.gastosai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the coverage gate itself (TEN-396, ../docs/coverage.md).
 *
 * <p>The gate is configuration, so the thing that can go wrong is configuration being removed or
 * weakened: a {@code check} execution deleted, a floor lowered, a wildcard added to the exclusion
 * list so the denominator quietly shrinks, or CI dropped back to {@code mvn test} — which produces
 * the JaCoCo report and reads nothing from it. None of those break a test that exercises
 * application code, and every one of them makes the coverage number stop meaning what it says.
 */
class CoverageFloorTest {

	private static final Path POM = Path.of("pom.xml");
	private static final Path CI = Path.of(".github/workflows/continuous-integration.yml");

	private static String read(Path path) throws IOException {
		return Files.readString(path);
	}

	private static double property(String pom, String name) {
		Matcher matcher = Pattern.compile("<" + name + ">([^<]+)</" + name + ">").matcher(pom);
		assertThat(matcher.find())
				.as("pom.xml declares the <%s> property — the floor is a one-line diff, not a literal buried in plugin config", name)
				.isTrue();
		return Double.parseDouble(matcher.group(1).trim());
	}

	@Test
	void jacocoCheckIsBoundToVerify() throws IOException {
		String pom = read(POM);

		assertThat(pom).contains("<goal>check</goal>");
		// The check execution and its phase, in that order, inside the jacoco plugin block.
		assertThat(pom)
				.as("jacoco:check runs at verify, so ./mvnw verify fails below the floor")
				.containsPattern(
						Pattern.compile(
								"<id>check-coverage-floor</id>\\s*<phase>verify</phase>", Pattern.DOTALL));
	}

	@Test
	void bothFloorsAreRatiosReferencedByTheCheckRule() throws IOException {
		String pom = read(POM);

		double line = property(pom, "coverage.line.min");
		double branch = property(pom, "coverage.branch.min");

		assertThat(line).isBetween(0.0, 1.0);
		assertThat(branch).isBetween(0.0, 1.0);

		// Measured on a clean `./mvnw clean verify` on 2026-09-17: 81.4% lines, 69.3% branches,
		// over the denominator pinned below. The floor only ever rises, so anything under the
		// value this issue installed is a regression of the gate rather than of the code.
		assertThat(line).isGreaterThanOrEqualTo(0.81);
		assertThat(branch).isGreaterThanOrEqualTo(0.69);

		assertThat(pom).contains("<minimum>${coverage.line.min}</minimum>");
		assertThat(pom).contains("<minimum>${coverage.branch.min}</minimum>");
		assertThat(pom).contains("<counter>LINE</counter>");
		assertThat(pom).contains("<counter>BRANCH</counter>");
	}

	@Test
	void everyExclusionIsNamedExplicitly() throws IOException {
		String pom = read(POM);

		Matcher matcher =
				Pattern.compile("<exclude>(com/teng/app/gastosai/[^<]+)</exclude>").matcher(pom);
		int found = 0;
		while (matcher.find()) {
			String excluded = matcher.group(1);
			found++;
			assertThat(excluded)
					.as("'%s' is a wildcard: the denominator would grow silently as classes are added", excluded)
					.doesNotContain("*");
			assertThat(excluded).endsWith(".class");
			assertThat(Path.of("src/main/java", excluded.replace(".class", ".java")))
					.as("'%s' names a class that exists — a stale exclusion hides a file nobody is measuring", excluded)
					.exists();
		}
		assertThat(found).as("the entry point and the bean-wiring config classes are excluded").isPositive();
	}

	@Test
	void continuousIntegrationRunsVerifyNotTest() throws IOException {
		String ci = read(CI);

		assertThat(ci)
				.as("CI runs verify, so the coverage floor is enforced on every PR")
				.contains("./mvnw --batch-mode verify");
		assertThat(ci)
				.as("a bare `mvnw test` step would produce the report and never read it")
				.doesNotContain("run: ./mvnw --batch-mode test");
	}
}
