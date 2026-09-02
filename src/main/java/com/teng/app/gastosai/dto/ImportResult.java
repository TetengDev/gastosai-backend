package com.teng.app.gastosai.dto;

import java.util.List;

/**
 * The outcome of a CSV import.
 *
 * <p>{@code limitReached} names the plan entitlement that stopped some rows — today only
 * {@code CUSTOM_CATEGORIES}, when the file named categories the account has no headroom to create
 * (TEN-329). It is {@code null} on every other import. A non-strict import finishes the file rather
 * than truncating at the first refusal: the rows that needed a new category are listed in
 * {@code errors}, the rows that named an existing one still import, and this field is what tells a
 * client the reason is an entitlement rather than a defect in the file — a plain error string is
 * what TEN-327 rejected, because a client cannot route it to an upgrade prompt.
 *
 * <p>Strict mode never reaches this field: there the refusal rolls the whole file back and answers
 * {@code 402} naming {@code CUSTOM_CATEGORIES}.
 */
public record ImportResult(int imported, int skipped, List<String> errors, String limitReached) {

	public ImportResult(int imported, int skipped, List<String> errors) {
		this(imported, skipped, errors, null);
	}
}