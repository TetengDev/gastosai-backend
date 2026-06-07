package com.teng.app.gastosai.ai;

import java.util.regex.Pattern;

/**
 * Validates AI-generated SQL before execution: single SELECT, {@code expenses} only, no obvious mutating or exfil patterns.
 */
public final class SqlGuard {

	private static final Pattern FORBIDDEN = Pattern.compile(
			"(?is)\\b(insert|update|delete|drop|truncate|alter|create|grant|revoke|merge|call|execute|union|intersect|except|into\\s+outfile|load\\s+file|copy\\s+\\(|pg_sleep|information_schema|pg_catalog)\\b");

	private static final Pattern FROM_EXPENSES = Pattern.compile(
			"(?is)from\\s+(?:[\\w]+\\.)?[\"`]?expenses[\"`]?(\\s|,|$|\\))");

	private SqlGuard() {
	}

	public static String validateAndNormalize(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new IllegalArgumentException("Generated SQL is empty");
		}
		String s = sql.trim();
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (s.contains(";")) {
			throw new IllegalArgumentException("Multiple SQL statements are not allowed");
		}
		if (s.length() < 10 || !s.regionMatches(true, 0, "SELECT", 0, 6)
				|| !Character.isWhitespace(s.charAt(6))) {
			throw new IllegalArgumentException("Only a single SELECT statement is allowed");
		}
		if (FORBIDDEN.matcher(s).find()) {
			throw new IllegalArgumentException("SQL uses forbidden constructs");
		}
		if (!FROM_EXPENSES.matcher(s).find()) {
			throw new IllegalArgumentException("SQL must query the expenses table");
		}
		return s;
	}
}
