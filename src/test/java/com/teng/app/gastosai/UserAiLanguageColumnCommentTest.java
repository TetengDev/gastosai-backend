package com.teng.app.gastosai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.teng.app.gastosai.ai.AiLanguage;
import com.teng.app.gastosai.ai.AiLanguageRegistry;
import com.teng.app.gastosai.support.PostgresBackedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V31 created {@code users.insight_language} / {@code users.chat_language} and described them, in a
 * script comment, as allow-listed "to 'en' and 'fil'". Since TEN-388 the allow-list is application
 * configuration, so that sentence names a constraint the database does not have and the application
 * no longer applies. V32 attaches the correct description to the columns themselves.
 *
 * <p>What is pinned here is the property that made the old wording go stale: the comment must not
 * enumerate language codes. Adding a language to {@code gastos.ai.language.supported} is meant to be
 * a configuration change and nothing else (TEN-388) — a schema comment that lists codes turns it
 * back into a migration, silently, by going wrong the moment nobody writes one.
 */
@SpringBootTest
class UserAiLanguageColumnCommentTest extends PostgresBackedTest {

	private static final List<String> LANGUAGE_COLUMNS = List.of("insight_language", "chat_language");

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	AiLanguageRegistry languageRegistry;

	@Test
	void bothLanguageColumnsCarryACommentThatNamesNoLanguageCode() {
		for (String column : LANGUAGE_COLUMNS) {
			String comment = columnComment(column);

			assertThat(comment)
					.as("comment on users.%s", column)
					.isNotBlank()
					.contains("BCP-47")
					.containsIgnoringCase("configuration");

			// Every configured code, not just the two V31 named: the point is that no code belongs
			// in the comment, so the assertion has to fail for the eleventh language as well as the
			// first. Word boundaries because "en" and "id" occur inside ordinary English words.
			for (AiLanguage language : languageRegistry.supported()) {
				Pattern standaloneCode =
						Pattern.compile("\\b" + Pattern.quote(language.code()) + "\\b",
								Pattern.CASE_INSENSITIVE);
				assertThat(standaloneCode.matcher(comment).find())
						.as("comment on users.%s names the configured code '%s'", column,
								language.code())
						.isFalse();
			}
		}
	}

	@Test
	void theMigrationThatSetsThoseCommentsMovesNoSchemaAndNoData() throws IOException {
		String migration = new ClassPathResource("db/migration/V32__user_ai_language_column_comments.sql")
				.getContentAsString(StandardCharsets.UTF_8);

		List<String> statements = migration.lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("--"))
				.toList();

		assertThat(statements).isNotEmpty();
		assertThat(String.join(" ", statements))
				.as("V32 is an expand-only step: COMMENT ON COLUMN and nothing else")
				.doesNotContainIgnoringCase("ALTER ")
				.doesNotContainIgnoringCase("CREATE ")
				.doesNotContainIgnoringCase("DROP ")
				.doesNotContainIgnoringCase("INSERT ")
				.doesNotContainIgnoringCase("UPDATE ")
				.doesNotContainIgnoringCase("DELETE ");
		assertThat(statements.stream().filter(line -> line.startsWith("COMMENT ON COLUMN")))
				.as("one COMMENT ON COLUMN per language column")
				.hasSize(LANGUAGE_COLUMNS.size());
	}

	private String columnComment(String column) {
		return jdbcTemplate.queryForObject(
				"""
				SELECT col_description(a.attrelid, a.attnum)
				FROM pg_attribute a
				WHERE a.attrelid = 'public.users'::regclass
				  AND a.attname = ?
				""",
				String.class, column);
	}
}
