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
 * FintechAdapter — Adapter for third-party Fintech API transactions.
 *
 * Fintech partners (e.g., Razorpay, PayU, BillDesk, Stripe) integrate with our
 * bank via REST webhooks and send JSON payloads.
 *
 * EXPECTED RAW PAYLOAD FORMAT (JSON string from Fintech partner): {
 * "partnerRef" : "RAZORPAY-TXN-8829", "type" : "CREDIT", "value" : "12500.75",
 * "ccy" : "INR", "settlDate" : "2024-06-15", "partnerCode" : "RAZORPAY" }
 *
 * Note: Fintech partners use different JSON key names than our internal system.
 * - "partnerRef" instead of "sourceRef" - "type" instead of "txnType" - "value"
 * instead of "amount" - "ccy" instead of "currency" - "settlDate" instead of
 * "valueDate"
 *
 * This adapter's job is to bridge that naming difference.
 *
 * Implements: TransactionAdapter (Strategy Pattern)
 */
public class FintechAdapter implements TransactionAdapter {

	private SourceSystem fintechSourceSystem;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public FintechAdapter() {
		this.fintechSourceSystem = new SourceSystem("FINTECH", ProtocolType.REST_API,
				"{\"endpoint\":\"https://api.bank.com/fintech/webhook\",\"authType\":\"API_KEY\"}", true,
				"fintech-integration@bank.com");
		this.fintechSourceSystem.setSourceSystemId(6L);
		this.fintechSourceSystem.setCreatedBy("SYSTEM");
	}

	// -----------------------------------------------------------------------
	// TransactionAdapter implementation
	// -----------------------------------------------------------------------

	/**
	 * Parses a Fintech partner JSON payload and returns a canonical
	 * IncomingTransaction.
	 *
	 * Example input:
	 * {"partnerRef":"RAZORPAY-TXN-8829","type":"CREDIT","value":"12500.75","ccy":"INR","settlDate":"2024-06-15","partnerCode":"RAZORPAY"}
	 */
	@Override
	public IncomingTransaction adapt(String rawPayload) {

		if (rawPayload == null || rawPayload.trim().isEmpty()) {
			throw new IllegalArgumentException("FintechAdapter: rawPayload cannot be null or empty");
		}

		// Note: Fintech uses different key names — we map them to our standard names
		String sourceRef = extractJsonField(rawPayload, "partnerRef");
		String txnTypeStr = extractJsonField(rawPayload, "type");
		String amountStr = extractJsonField(rawPayload, "value");
		String currency = extractJsonField(rawPayload, "ccy");
		String valueDateStr = extractJsonField(rawPayload, "settlDate");
		String partnerCode = extractJsonField(rawPayload, "partnerCode");

		// Validate required fields
		if (sourceRef == null || txnTypeStr == null || amountStr == null || currency == null || valueDateStr == null) {
			throw new IllegalArgumentException(
					"FintechAdapter: Missing required fields in Fintech payload: " + rawPayload);
		}

		BigDecimal amount = new BigDecimal(amountStr);
		LocalDate valueDate = LocalDate.parse(valueDateStr);
		TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

		// Prefix the sourceRef with partner code for better traceability
		// e.g. "RAZORPAY-TXN-8829" → "FINTECH-RAZORPAY-RAZORPAY-TXN-8829"
		String fullSourceRef = (partnerCode != null) ? "FINTECH-" + partnerCode + "-" + sourceRef
				: "FINTECH-" + sourceRef;

		String normalizedPayload = buildNormalizedPayload(fullSourceRef, partnerCode, txnType, amount, currency,
				valueDate);

		IncomingTransaction txn = new IncomingTransaction(fintechSourceSystem, fullSourceRef, rawPayload, txnType,
				amount, currency, valueDate, normalizedPayload);

		// Fintech transactions need extra validation — mark as RECEIVED not VALIDATED
		// (The settlement engine will validate them before processing)
		txn.setProcessingStatus(ProcessingStatus.RECEIVED);
		txn.setCreatedBy("FINTECH_ADAPTER");

		return txn;
	}

	@Override
	public SourceType getSourceType() {
		return SourceType.FINTECH;
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	/**
	 * Extracts a value from a simple flat JSON string by field name. Same logic as
	 * RtgsAdapter — handles quoted string values and bare numbers.
	 */
	private String extractJsonField(String json, String fieldName) {
		String searchKey = "\"" + fieldName + "\":";
		int keyIndex = json.indexOf(searchKey);
		if (keyIndex == -1) {
			return null;
		}

		int valueStart = keyIndex + searchKey.length();

		// Skip any whitespace after colon
		while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
			valueStart++;
		}

		boolean isQuoted = (valueStart < json.length() && json.charAt(valueStart) == '"');

		if (isQuoted) {
			int openQuote = valueStart;
			int closeQuote = json.indexOf('"', openQuote + 1);
			if (closeQuote == -1)
				return null;
			return json.substring(openQuote + 1, closeQuote);
		} else {
			int endIndex = json.indexOf(',', valueStart);
			if (endIndex == -1)
				endIndex = json.indexOf('}', valueStart);
			if (endIndex == -1)
				endIndex = json.length();
			return json.substring(valueStart, endIndex).trim();
		}
	}

	private String buildNormalizedPayload(String sourceRef, String partnerCode, TransactionType txnType,
			BigDecimal amount, String currency, LocalDate valueDate) {
		return "{" + "\"source\":\"FINTECH\"," + "\"partnerCode\":\"" + (partnerCode != null ? partnerCode : "UNKNOWN")
				+ "\"," + "\"sourceRef\":\"" + sourceRef + "\"," + "\"txnType\":\"" + txnType.name() + "\","
				+ "\"amount\":" + amount + "," + "\"currency\":\"" + currency + "\"," + "\"valueDate\":\"" + valueDate
				+ "\"" + "}";
	}
}
