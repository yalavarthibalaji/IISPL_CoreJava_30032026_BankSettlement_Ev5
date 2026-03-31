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
 * CbsAdapter — Adapter for the Core Banking System (CBS).
 *
 * CBS sends transactions as pipe-delimited plain text strings.
 *
 * EXPECTED RAW PAYLOAD FORMAT FROM CBS:
 * "CBS-REF-001|CREDIT|50000.00|INR|2024-06-15|ACC001|ACC002" [0]sourceRef
 * [1]txnType [2]amount [3]currency [4]valueDate [5]debitAcc [6]creditAcc
 *
 * This adapter parses that string and builds a canonical IncomingTransaction.
 *
 * Implements: TransactionAdapter (Strategy Pattern)
 */
public class CbsAdapter implements TransactionAdapter {

	// The SourceSystem object representing CBS — set once, reused for every
	// transaction
	private SourceSystem cbsSourceSystem;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public CbsAdapter() {
		// Build the SourceSystem once and reuse it for all CBS transactions
		this.cbsSourceSystem = new SourceSystem("CBS", // systemCode
				ProtocolType.FLAT_FILE, // CBS uses file-drop / direct DB
				"{\"protocol\":\"PIPE_DELIMITED\"}", // connectionConfig as JSON
				true, // isActive
				"cbs-support@bank.com" // contactEmail
		);
		this.cbsSourceSystem.setSourceSystemId(1L);
		this.cbsSourceSystem.setCreatedBy("SYSTEM");
	}

	// -----------------------------------------------------------------------
	// TransactionAdapter implementation
	// -----------------------------------------------------------------------

	/**
	 * Parses a CBS pipe-delimited payload and returns a canonical
	 * IncomingTransaction.
	 *
	 * Example input: "CBS-REF-001|CREDIT|50000.00|INR|2024-06-15"
	 */
	@Override
	public IncomingTransaction adapt(String rawPayload) {

		// Guard: never process a null or blank payload
		if (rawPayload == null || rawPayload.trim().isEmpty()) {
			throw new IllegalArgumentException("CbsAdapter: rawPayload cannot be null or empty");
		}

		// Split by pipe delimiter
		String[] parts = rawPayload.split("\\|");

		// Guard: CBS format must have at least 5 fields
		if (parts.length < 5) {
			throw new IllegalArgumentException(
					"CbsAdapter: Invalid CBS payload format. Expected at least 5 pipe-separated fields. Got: "
							+ rawPayload);
		}

		// Parse each field from the pipe-delimited string
		String sourceRef = parts[0].trim();
		String txnTypeStr = parts[1].trim();
		BigDecimal amount = new BigDecimal(parts[2].trim());
		String currency = parts[3].trim();
		LocalDate valueDate = LocalDate.parse(parts[4].trim());

		// Convert the string txnType to the enum value
		TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

		// Build the normalized JSON payload for downstream processing
		String normalizedPayload = buildNormalizedPayload(sourceRef, txnType, amount, currency, valueDate);

		// Build and return the canonical IncomingTransaction
		IncomingTransaction txn = new IncomingTransaction(cbsSourceSystem, sourceRef, rawPayload, txnType, amount,
				currency, valueDate, normalizedPayload);

		// Mark as VALIDATED since CBS payloads are pre-validated at source
		txn.setProcessingStatus(ProcessingStatus.VALIDATED);
		txn.setCreatedBy("CBS_ADAPTER");

		return txn;
	}

	/**
	 * This adapter handles CBS source type.
	 */
	@Override
	public SourceType getSourceType() {
		return SourceType.CBS;
	}

	// -----------------------------------------------------------------------
	// Private helper
	// -----------------------------------------------------------------------

	/**
	 * Builds a standardised JSON string from parsed CBS fields. All adapters
	 * produce the same JSON structure so downstream code doesn't need to know the
	 * original format.
	 */
	private String buildNormalizedPayload(String sourceRef, TransactionType txnType, BigDecimal amount, String currency,
			LocalDate valueDate) {
		return "{" + "\"source\":\"CBS\"," + "\"sourceRef\":\"" + sourceRef + "\"," + "\"txnType\":\"" + txnType.name()
				+ "\"," + "\"amount\":" + amount + "," + "\"currency\":\"" + currency + "\"," + "\"valueDate\":\""
				+ valueDate + "\"" + "}";
	}
}
