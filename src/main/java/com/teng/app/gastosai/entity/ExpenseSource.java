package com.teng.app.gastosai.entity;

/**
 * How an expense came to exist.
 *
 * <p>Recorded once, at creation, and never rewritten: an edit changes what the expense says, not
 * where it came from. That is the whole value of the field — "which route produces the rows the
 * user later corrects" is only answerable if the answer survives the correction.
 *
 * <p>{@link #isClientDeclarable()} splits the two kinds of value here. {@code MANUAL} and
 * {@code RECEIPT_SCAN} are indistinguishable from the server's side — both arrive as a plain
 * {@code POST /expenses} body, and only the client knows whether a human typed the amount or a
 * camera did — so the client declares them. The rest are decided by the route that writes the row
 * and are refused on a request body, because a client that could claim {@code IMPORT} could make
 * the field lie about the one thing it exists to record.
 */
public enum ExpenseSource {

	/** Typed into a form by a human. The default for anything that does not say otherwise. */
	MANUAL(true),

	/** Read out of free text by the parser — {@code POST /expenses/quick-add} or the assistant. */
	QUICK_ADD(false),

	/** Saved from a receipt photo the vision model read ({@code POST /ai/vision}, then a save). */
	RECEIPT_SCAN(true),

	/**
	 * Materialised from a recurring expense.
	 *
	 * <p>Nothing writes this today: recurring expenses are schedules that raise due alerts, and no
	 * code path turns one into an {@code expenses} row. The value is defined because it is part of
	 * the vocabulary clients filter on, and because the day that path is built it must not be a
	 * contract change. See the note in KNOWN-GAPS when that lands.
	 */
	RECURRING(false),

	/** Written by a CSV import. */
	IMPORT(false);

	private final boolean clientDeclarable;

	ExpenseSource(boolean clientDeclarable) {
		this.clientDeclarable = clientDeclarable;
	}

	/** Whether a client may name this source on a create request. */
	public boolean isClientDeclarable() {
		return clientDeclarable;
	}
}
