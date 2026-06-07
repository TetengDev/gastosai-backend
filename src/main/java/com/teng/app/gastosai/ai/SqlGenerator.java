package com.teng.app.gastosai.ai;

/**
 * Interface for SQL generators that work with different AI providers.
 */
public interface SqlGenerator {

	/**
	 * Generate a PostgreSQL SELECT query based on natural language question.
	 *
	 * @param question the natural language question
	 * @return the generated SQL query
	 */
	String generateSql(String question);
}

