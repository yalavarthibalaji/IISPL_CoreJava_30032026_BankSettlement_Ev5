package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * CbsAdapter — Adapter for the Core Banking System (CBS).
 *
 * CBS sends transactions as pipe-delimited plain text flat files.
 *
 * RAW PAYLOAD FORMAT (12 pipe-separated fields — v3 adds FROM_BANK, TO_BANK):
 * ─────────────────────────────────────────────────────────────────────────────
 * Field Position Example Value
 * ─────────────────────────────────────────────────────────────────────────────
 * CBS_TXN_ID [0] C001 ACCT_DR [1] ACC-HDFC-001 ← debit account ACCT_CR [2]
 * ACC-HDFC-002 ← credit account AMT [3] 25000.00 CCY [4] INR TXN_DT [5]
 * 20260402 ← format: yyyyMMdd TXN_TYPE [6] CREDIT / DEBIT NARRATION [7] Salary
 * credit March 2026 BRANCH_CODE [8] BLR001 MAKER_ID [9] EMP4521 FROM_BANK [10]
 * HDFC Bank ← NEW (v3) — bank owning ACCT_DR TO_BANK [11] SBI Bank ← NEW (v3) —
 * bank owning ACCT_CR
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * EXAMPLE LINE:
 * C001|ACC-HDFC-001|ACC-HDFC-002|25000.00|INR|20260402|CREDIT|Salary credit
 * March 2026|BLR001|EMP4521|HDFC Bank|SBI Bank
 *
 * CHANGE LOG (v3 — fromBank / toBank): - FROM_BANK [10] and TO_BANK [11] added
 * as new pipe-delimited fields. - Both are parsed and stored as: (a)
 * txn.fromBank / txn.toBank fields on IncomingTransaction. (b) "fromBank" /
 * "toBank" keys inside normalizedPayload JSON. - CsvFileReader updated to read
 * 12 columns instead of 10. - cbs_transactions.csv updated with FROM_BANK and
 * TO_BANK columns.
 */
public class CbsAdapter implements TransactionAdapter {

	private static final DateTimeFormatter CBS_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final SourceSystem cbsSourceSystem;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public CbsAdapter() {
		this.cbsSourceSystem = new SourceSystem("CBS", ProtocolType.FLAT_FILE,
				"{\"protocol\":\"PIPE_DELIMITED\",\"fileDir\":\"/cbs/inbound\"}", true, "cbs-support@bank.com");
		this.cbsSourceSystem.setSourceSystemId(1L);
		this.cbsSourceSystem.setCreatedBy("SYSTEM");
	}

	// -----------------------------------------------------------------------
	// TransactionAdapter implementation
	// -----------------------------------------------------------------------

	@Override
	public IncomingTransaction adapt(String rawPayload) {

		if (rawPayload == null || rawPayload.trim().isEmpty()) {
			throw new IllegalArgumentException("CbsAdapter: rawPayload cannot be null or empty");
		}

		if (rawPayload.trim().startsWith("#")) {
			throw new IllegalArgumentException("CbsAdapter: Skipping comment line");
		}

		String[] parts = rawPayload.split("\\|");

		// CBS now sends 12 pipe-separated fields (added FROM_BANK, TO_BANK)
		if (parts.length < 12) {
			throw new IllegalArgumentException("CbsAdapter: Invalid CBS payload. Expected 12 pipe-separated fields: "
					+ "CBS_TXN_ID|ACCT_DR|ACCT_CR|AMT|CCY|TXN_DT|TXN_TYPE|NARRATION"
					+ "|BRANCH_CODE|MAKER_ID|FROM_BANK|TO_BANK" + ". Got " + parts.length + " fields: " + rawPayload);
		}

		// ---- Parse all 12 fields ----
		String sourceRef = parts[0].trim(); // CBS_TXN_ID
		String debitAccountNum = parts[1].trim(); // ACCT_DR
		String creditAccountNum = parts[2].trim(); // ACCT_CR
		BigDecimal amount = new BigDecimal(parts[3].trim()); // AMT
		String currency = parts[4].trim(); // CCY — for normalizedPayload audit
		String txnDtStr = parts[5].trim(); // TXN_DT — yyyyMMdd
		String txnTypeStr = parts[6].trim(); // TXN_TYPE
		String narration = parts[7].trim(); // NARRATION
		String branchCode = parts[8].trim(); // BRANCH_CODE
		String makerId = parts[9].trim(); // MAKER_ID
		String fromBank = parts[10].trim(); // FROM_BANK ← NEW
		String toBank = parts[11].trim(); // TO_BANK ← NEW

		LocalDate valueDate = LocalDate.parse(txnDtStr, CBS_DATE_FORMAT);
		TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

		// ---- Build normalizedPayload — now includes fromBank and toBank ----
		String normalizedPayload = buildNormalizedPayload(sourceRef, txnType, amount, currency, valueDate,
				debitAccountNum, creditAccountNum, narration, branchCode, makerId, fromBank, toBank);

		// ---- Build IncomingTransaction ----
		IncomingTransaction txn = new IncomingTransaction(cbsSourceSystem, sourceRef, rawPayload, txnType, amount,
				valueDate, normalizedPayload);

		// Set fromBank and toBank on the entity field as well
		txn.setFromBank(fromBank);
		txn.setToBank(toBank);

		txn.setProcessingStatus(ProcessingStatus.VALIDATED);
		txn.setCreatedBy("CBS_ADAPTER");

		return txn;
	}

	@Override
	public SourceType getSourceType() {
		return SourceType.CBS;
	}

	// -----------------------------------------------------------------------
	// Private helper — builds normalizedPayload JSON
	// -----------------------------------------------------------------------

	private String buildNormalizedPayload(String sourceRef, TransactionType txnType, BigDecimal amount, String currency,
			LocalDate valueDate, String debitAcc, String creditAcc, String narration, String branchCode, String makerId,
			String fromBank, String toBank) {
		return "{" + "\"source\":\"CBS\"," + "\"sourceRef\":\"" + sourceRef + "\"," + "\"txnType\":\"" + txnType.name()
				+ "\"," + "\"amount\":" + amount + "," + "\"currency\":\"" + currency + "\"," + "\"valueDate\":\""
				+ valueDate + "\"," + "\"debitAccount\":\"" + debitAcc + "\"," + "\"creditAccount\":\"" + creditAcc
				+ "\"," + "\"fromBank\":\"" + fromBank + "\"," + "\"toBank\":\"" + toBank + "\"," + "\"narration\":\""
				+ narration + "\"," + "\"branchCode\":\"" + branchCode + "\"," + "\"makerId\":\"" + makerId + "\""
				+ "}";
	}
}