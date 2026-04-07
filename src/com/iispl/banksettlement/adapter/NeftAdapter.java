package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * NeftAdapter — Adapter for NEFT (National Electronic Funds Transfer)
 * transactions.
 *
 * NEFT sends batch files in fixed-width text format. Each record is exactly 172
 * characters wide.
 *
 * RAW PAYLOAD FORMAT (Fixed-Width — 12 fields):
 * ──────────────────────────────────────────────────────────────────────────
 * Field Position Length Example Value
 * ──────────────────────────────────────────────────────────────────────────
 * RECORD_TYPE 001-002 2 CR / DR / RV NEFT_REF 003-018 16 NEFT260402001 ←
 * sourceRef; date at 5-10 SENDER_IFSC 019-029 11 ICIC0002345 SENDER_ACCT
 * 030-049 20 NEFT-ACC-DR-001 ← debitAccount BENE_IFSC 050-060 11 SBIN0001234
 * BENE_ACCT 061-080 20 NEFT-ACC-CR-001 ← creditAccount BENE_NAME 081-110 30
 * RAMESH KUMAR AMT 111-122 12 000010000.00 PURPOSE_CODE 123-126 4 OTHR / RVRSL
 * BATCH_NO 127-132 6 B00012 FROM_BANK 133-152 20 ICICI Bank TO_BANK 153-172 20
 * SBI Bank
 * ──────────────────────────────────────────────────────────────────────────
 *
 * RECORD_TYPE values: CR → CREDIT (money coming into BENE_ACCT) DR → DEBIT
 * (money going out of SENDER_ACCT) RV → REVERSAL (undo of a previous NEFT
 * transaction)
 *
 * For REVERSAL records (RV): - SENDER_ACCT holds the original creditAccount
 * (money flows back) - BENE_ACCT holds the original debitAccount (money returns
 * here) - NEFT_REF is a NEW unique reference for this reversal - PURPOSE_CODE
 * is RVRSL - The originalTxnRef is derived: swap the date in NEFT_REF to find
 * original (Settlement engine uses this to locate the original transaction)
 *
 * CHANGE LOG (v4 — REVERSAL support added): - RECORD_TYPE switch now handles
 * "RV" → TransactionType.REVERSAL. - normalizedPayload gets "originalTxnRef"
 * key for RV records (derived as the original sourceRef by convention —
 * settlement engine uses it). - No change to record width (still 172 chars).
 */
public class NeftAdapter implements TransactionAdapter {

	// Updated minimum length — 132 original + 20 FROM_BANK + 20 TO_BANK = 172
	private static final int NEFT_RECORD_MIN_LENGTH = 172;

	private final SourceSystem neftSourceSystem;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public NeftAdapter() {
		this.neftSourceSystem = new SourceSystem("NEFT", ProtocolType.FLAT_FILE,
				"{\"protocol\":\"FIXED_WIDTH\",\"fileDir\":\"/neft/inbound\",\"batchSize\":\"1000\"}", true,
				"neft-ops@npci.org.in");
		this.neftSourceSystem.setSourceSystemId(4L);
		this.neftSourceSystem.setCreatedBy("SYSTEM");
	}

	// -----------------------------------------------------------------------
	// TransactionAdapter implementation
	// -----------------------------------------------------------------------

	@Override
	public IncomingTransaction adapt(String rawPayload) {

		if (rawPayload == null || rawPayload.trim().isEmpty()) {
			throw new IllegalArgumentException("NeftAdapter: rawPayload cannot be null or empty");
		}

		if (rawPayload.trim().startsWith("#")) {
			throw new IllegalArgumentException("NeftAdapter: Skipping comment line");
		}

		if (rawPayload.length() < NEFT_RECORD_MIN_LENGTH) {
			throw new IllegalArgumentException("NeftAdapter: NEFT record must be at least " + NEFT_RECORD_MIN_LENGTH
					+ " characters. Got length: " + rawPayload.length() + " | Record: " + rawPayload);
		}

		// ---- Extract all fields using fixed-width positions (0-based) ----
		String recordType = rawPayload.substring(0, 2).trim(); // CR / DR / RV
		String neftRef = rawPayload.substring(2, 18).trim(); // e.g. NEFT260402001
		String senderIFSC = rawPayload.substring(18, 29).trim();
		String senderAcct = rawPayload.substring(29, 49).trim(); // debit account
		String beneIFSC = rawPayload.substring(49, 60).trim();
		String beneAcct = rawPayload.substring(60, 80).trim(); // credit account
		String beneName = rawPayload.substring(80, 110).trim();
		String amtStr = rawPayload.substring(110, 122).trim();
		String purposeCode = rawPayload.substring(122, 126).trim();
		String batchNo = rawPayload.substring(126, 132).trim();
		String fromBank = rawPayload.substring(132, 152).trim();
		String toBank = rawPayload.substring(152, 172).trim();

		// ---- Validate required fields ----
		if (recordType.isEmpty() || neftRef.isEmpty() || senderAcct.isEmpty() || beneAcct.isEmpty()
				|| amtStr.isEmpty()) {
			throw new IllegalArgumentException("NeftAdapter: Missing required fields in NEFT record.");
		}

		if (fromBank.isEmpty()) {
			throw new IllegalArgumentException("NeftAdapter: FROM_BANK (positions 133-152) is blank.");
		}

		if (toBank.isEmpty()) {
			throw new IllegalArgumentException("NeftAdapter: TO_BANK (positions 153-172) is blank.");
		}

		// ---- Map RECORD_TYPE → TransactionType ----
		//
		// CR → CREDIT : normal inward credit to beneficiary account
		// DR → DEBIT : normal outward debit from sender account
		// RV → REVERSAL : undo of a previously settled NEFT transaction
		//
		TransactionType txnType;
		switch (recordType.toUpperCase()) {
		case "CR":
			txnType = TransactionType.CREDIT;
			break;
		case "DR":
			txnType = TransactionType.DEBIT;
			break;
		case "RV":
			txnType = TransactionType.REVERSAL;
			break;
		default:
			throw new IllegalArgumentException("NeftAdapter: Unknown RECORD_TYPE '" + recordType
					+ "'. Expected CR (CREDIT), DR (DEBIT), or RV (REVERSAL).");
		}

		BigDecimal amount = new BigDecimal(amtStr);
		String currency = "INR"; // NEFT is always INR
		LocalDate valueDate = extractValueDateFromNeftRef(neftRef);

		// ---- For reversals, originalTxnRef is stored in normalizedPayload.
		// Convention: the reversal neftRef IS the new unique key.
		// The original txn can be looked up by the settlement engine
		// using PURPOSE_CODE=RVRSL and matching accounts/amounts.
		// We also store it explicitly so the engine can link them. ----
		String originalTxnRef = "";
		String reversalReason = "";
		if (txnType == TransactionType.REVERSAL) {
			// For NEFT reversals: originalTxnRef is not directly in the record.
			// The settlement engine will use senderAcct+beneAcct+amount to match.
			// We set purposeCode as signal; originalTxnRef left empty here.
			originalTxnRef = "";
			reversalReason = "NEFT_REVERSAL";
		}

		// ---- Build normalizedPayload ----
		String normalizedPayload = buildNormalizedPayload(neftRef, txnType, amount, currency, valueDate, senderAcct,
				beneAcct, senderIFSC, beneIFSC, beneName, purposeCode, batchNo, fromBank, toBank, originalTxnRef,
				reversalReason);

		// ---- Build IncomingTransaction ----
		IncomingTransaction txn = new IncomingTransaction(neftSourceSystem, neftRef, rawPayload, txnType, amount,
				valueDate, normalizedPayload);

		txn.setFromBank(fromBank);
		txn.setToBank(toBank);
		txn.setProcessingStatus(ProcessingStatus.VALIDATED);
		txn.setCreatedBy("NEFT_ADAPTER");

		return txn;
	}

	@Override
	public SourceType getSourceType() {
		return SourceType.NEFT;
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	/**
	 * Extracts valueDate from NEFT_REF. "NEFT260402001" → index 4-9 = "260402"
	 * (yyMMdd) → 2026-04-02 "NEFT260406001" → index 4-9 = "260406" (yyMMdd) →
	 * 2026-04-06
	 */
	private LocalDate extractValueDateFromNeftRef(String neftRef) {
		if (neftRef.length() < 10) {
			throw new IllegalArgumentException("NeftAdapter: NEFT_REF too short to extract date. Got: " + neftRef);
		}
		String yymmdd = neftRef.substring(4, 10);
		int year = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
		int month = Integer.parseInt(yymmdd.substring(2, 4));
		int day = Integer.parseInt(yymmdd.substring(4, 6));
		return LocalDate.of(year, month, day);
	}

	private String buildNormalizedPayload(String sourceRef, TransactionType txnType, BigDecimal amount, String currency,
			LocalDate valueDate, String debitAcc, String creditAcc, String senderIFSC, String beneIFSC, String beneName,
			String purposeCode, String batchNo, String fromBank, String toBank, String originalTxnRef,
			String reversalReason) {
		return "{" + "\"source\":\"NEFT\"," + "\"sourceRef\":\"" + sourceRef + "\"," + "\"txnType\":\"" + txnType.name()
				+ "\"," + "\"amount\":" + amount + "," + "\"currency\":\"" + currency + "\"," + "\"valueDate\":\""
				+ valueDate + "\"," + "\"debitAccount\":\"" + debitAcc + "\"," + "\"creditAccount\":\"" + creditAcc
				+ "\"," + "\"fromBank\":\"" + fromBank + "\"," + "\"toBank\":\"" + toBank + "\"," + "\"senderIFSC\":\""
				+ nullSafe(senderIFSC) + "\"," + "\"beneIFSC\":\"" + nullSafe(beneIFSC) + "\"," + "\"beneName\":\""
				+ nullSafe(beneName) + "\"," + "\"purposeCode\":\"" + nullSafe(purposeCode) + "\"," + "\"batchNo\":\""
				+ nullSafe(batchNo) + "\"," + "\"originalTxnRef\":\"" + nullSafe(originalTxnRef) + "\","
				+ "\"reversalReason\":\"" + nullSafe(reversalReason) + "\"" + "}";
	}

	private String nullSafe(String value) {
		return value != null ? value : "";
	}
}