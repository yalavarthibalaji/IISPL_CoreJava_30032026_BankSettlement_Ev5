package com.iispl.adapter;

import com.iispl.entity.IncomingTransaction;
import com.iispl.entity.SourceSystem;
import com.iispl.enums.ProcessingStatus;
import com.iispl.enums.ProtocolType;
import com.iispl.enums.SourceType;
import com.iispl.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * RtgsAdapter — Adapter for the Real-Time Gross Settlement (RTGS) system.
 *
 * RTGS sends transactions as XML messages over Message Queue (MQ).
 *
 * RAW PAYLOAD FORMAT (XML — 18 fields):
 * ─────────────────────────────────────────────────────────────────────────
 * <RTGSMessage> <MsgId>RTGS20260402001234</MsgId>
 * <SenderIFSC>SBIN0001234</SenderIFSC> <ReceiverIFSC>HDFC0005678</ReceiverIFSC>
 * <SenderAcct>RTGS-ACC-DR-001</SenderAcct>
 * <ReceiverAcct>RTGS-ACC-CR-001</ReceiverAcct> <Amount>5000000.00</Amount>
 * <Currency>INR</Currency> <ValueDate>2026-04-02</ValueDate> <TxnType>CREDIT /
 * DEBIT / INTRABANK / REVERSAL</TxnType> <Priority>HIGH</Priority>
 * <RBIRefNo>RBI20260402AAA</RBIRefNo> <Purpose>TRADE_SETTLEMENT</Purpose>
 * <SubmittedAt>2026-04-02T10:30:00</SubmittedAt> <BatchWindow>W1</BatchWindow>
 * <SenderBank>SBI Bank</SenderBank> <ReceiverBank>HDFC Bank</ReceiverBank>
 * <OriginalTxnRef></OriginalTxnRef> ← populated only for REVERSAL
 * <ReversalReason></ReversalReason> ← populated only for REVERSAL
 * </RTGSMessage>
 *
 * TxnType values: CREDIT → Standard credit transfer DEBIT → Standard debit
 * transfer INTRABANK → Settlement between two different banks via RTGS rail
 * (maps to TransactionType.INTRABANK in Java) REVERSAL → Undo of a previous
 * RTGS transaction (requires OriginalTxnRef and ReversalReason tags)
 *
 * RTGS MINIMUM AMOUNT RULE: Rs. 2,00,000 minimum for INR transactions.
 *
 * CHANGE LOG (v4 — INTRABANK and REVERSAL support): - TxnType switch expanded:
 * CREDIT, DEBIT, INTRABANK → TransactionType.INTRABANK, REVERSAL →
 * TransactionType.REVERSAL. - <OriginalTxnRef> and <ReversalReason> XML tags
 * added. - Both are parsed and stored in normalizedPayload for the settlement
 * engine. - Reversal validation: if TxnType=REVERSAL, OriginalTxnRef must not
 * be empty. - rtgs_transactions.xml updated with INTRABANK and REVERSAL
 * records.
 */
public class RtgsAdapter implements TransactionAdapter {

	private static final DateTimeFormatter RTGS_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final SourceSystem rtgsSourceSystem;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public RtgsAdapter() {
		this.rtgsSourceSystem = new SourceSystem("RTGS", ProtocolType.MESSAGE_QUEUE,
				"{\"mq\":\"RBI_MQ\",\"queue\":\"RTGS.INBOUND\",\"endpoint\":\"rtgs.rbi.org.in\"}", true,
				"rtgs-support@rbi.org.in");
		this.rtgsSourceSystem.setSourceSystemId(2L);
		this.rtgsSourceSystem.setCreatedBy("SYSTEM");
	}

	// -----------------------------------------------------------------------
	// TransactionAdapter implementation
	// -----------------------------------------------------------------------

	@Override
	public IncomingTransaction adapt(String rawPayload) {

		if (rawPayload == null || rawPayload.trim().isEmpty()) {
			throw new IllegalArgumentException("RtgsAdapter: rawPayload cannot be null or empty");
		}

		if (rawPayload.trim().startsWith("#")) {
			throw new IllegalArgumentException("RtgsAdapter: Skipping comment line");
		}

		if (!rawPayload.contains("<RTGSMessage>")) {
			throw new IllegalArgumentException(
					"RtgsAdapter: Payload missing <RTGSMessage> root tag. Got: " + rawPayload);
		}

		// ---- Parse canonical fields ----
		String sourceRef = extractXmlTag(rawPayload, "MsgId");
		String senderAcct = extractXmlTag(rawPayload, "SenderAcct");
		String receiverAcct = extractXmlTag(rawPayload, "ReceiverAcct");
		String amountStr = extractXmlTag(rawPayload, "Amount");
		String currency = extractXmlTag(rawPayload, "Currency");
		String valueDateStr = extractXmlTag(rawPayload, "ValueDate");
		String txnTypeStr = extractXmlTag(rawPayload, "TxnType");

		// ---- Parse bank fields ----
		String senderBank = extractXmlTag(rawPayload, "SenderBank");
		String receiverBank = extractXmlTag(rawPayload, "ReceiverBank");

		// ---- Parse reversal-specific fields (empty string if tag absent) ----
		String originalTxnRef = nullSafe(extractXmlTag(rawPayload, "OriginalTxnRef"));
		String reversalReason = nullSafe(extractXmlTag(rawPayload, "ReversalReason"));

		if (sourceRef == null || senderAcct == null || receiverAcct == null || amountStr == null || currency == null
				|| valueDateStr == null || txnTypeStr == null) {
			throw new IllegalArgumentException("RtgsAdapter: Missing required XML fields in: " + rawPayload);
		}

		if (senderBank == null || senderBank.isEmpty()) {
			throw new IllegalArgumentException("RtgsAdapter: Missing <SenderBank> tag.");
		}

		if (receiverBank == null || receiverBank.isEmpty()) {
			throw new IllegalArgumentException("RtgsAdapter: Missing <ReceiverBank> tag.");
		}

		// ---- Parse extra fields ----
		String senderIFSC = extractXmlTag(rawPayload, "SenderIFSC");
		String receiverIFSC = extractXmlTag(rawPayload, "ReceiverIFSC");
		String priority = extractXmlTag(rawPayload, "Priority");
		String rbiRefNo = extractXmlTag(rawPayload, "RBIRefNo");
		String purpose = extractXmlTag(rawPayload, "Purpose");
		String submittedAt = extractXmlTag(rawPayload, "SubmittedAt");
		String batchWindow = extractXmlTag(rawPayload, "BatchWindow");

		// ---- Map TxnType string → TransactionType enum ----
		//
		// CREDIT → standard inward credit
		// DEBIT → standard outward debit
		// INTRABANK → inter-bank bilateral settlement via RTGS
		// maps to TransactionType.INTRABANK (same as InterBankTransaction)
		// REVERSAL → undo of a previous RTGS transaction
		//
		TransactionType txnType;
		switch (txnTypeStr.toUpperCase()) {
		case "CREDIT":
			txnType = TransactionType.CREDIT;
			break;
		case "DEBIT":
			txnType = TransactionType.DEBIT;
			break;
		case "INTRABANK":
			txnType = TransactionType.INTRABANK;
			break;
		case "REVERSAL":
			txnType = TransactionType.REVERSAL;
			break;
		default:
			throw new IllegalArgumentException("RtgsAdapter: Unknown TxnType '" + txnTypeStr
					+ "'. Expected CREDIT, DEBIT, INTRABANK, or REVERSAL.");
		}

		// ---- Validate: REVERSAL must have OriginalTxnRef ----
		if (txnType == TransactionType.REVERSAL && originalTxnRef.isEmpty()) {
			throw new IllegalArgumentException(
					"RtgsAdapter: REVERSAL transaction must have a non-empty <OriginalTxnRef>. " + "Got: "
							+ rawPayload);
		}

		// ---- Type conversions ----
		BigDecimal amount = new BigDecimal(amountStr);
		LocalDate valueDate = LocalDate.parse(valueDateStr, RTGS_DATE_FORMAT);

		// RTGS minimum amount rule — Rs. 2,00,000 for INR
		if ("INR".equalsIgnoreCase(currency) && amount.compareTo(new BigDecimal("200000")) < 0) {
			throw new IllegalArgumentException("RtgsAdapter: RTGS minimum is Rs. 2,00,000. Got: " + amount);
		}

		// ---- Build normalizedPayload ----
		String normalizedPayload = buildNormalizedPayload(sourceRef, txnType, amount, currency, valueDate, senderAcct,
				receiverAcct, senderIFSC, receiverIFSC, priority, rbiRefNo, purpose, submittedAt, batchWindow,
				senderBank, receiverBank, originalTxnRef, reversalReason);

		// ---- Build IncomingTransaction ----
		IncomingTransaction txn = new IncomingTransaction(rtgsSourceSystem, sourceRef, rawPayload, txnType, amount,
				valueDate, normalizedPayload);

		txn.setFromBank(senderBank);
		txn.setToBank(receiverBank);
		txn.setProcessingStatus(ProcessingStatus.VALIDATED);
		txn.setCreatedBy("RTGS_ADAPTER");

		return txn;
	}

	@Override
	public SourceType getSourceType() {
		return SourceType.RTGS;
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	/**
	 * Extracts text between <tagName> and </tagName>. Pure Core Java — no XML
	 * library needed.
	 */
	private String extractXmlTag(String xml, String tagName) {
		String openTag = "<" + tagName + ">";
		String closeTag = "</" + tagName + ">";
		int startIndex = xml.indexOf(openTag);
		if (startIndex == -1)
			return null;
		int valueStart = startIndex + openTag.length();
		int valueEnd = xml.indexOf(closeTag, valueStart);
		if (valueEnd == -1)
			return null;
		return xml.substring(valueStart, valueEnd).trim();
	}

	private String buildNormalizedPayload(String sourceRef, TransactionType txnType, BigDecimal amount, String currency,
			LocalDate valueDate, String debitAcc, String creditAcc, String senderIFSC, String receiverIFSC,
			String priority, String rbiRefNo, String purpose, String submittedAt, String batchWindow, String fromBank,
			String toBank, String originalTxnRef, String reversalReason) {
		return "{" + "\"source\":\"RTGS\"," + "\"sourceRef\":\"" + sourceRef + "\"," + "\"txnType\":\"" + txnType.name()
				+ "\"," + "\"amount\":" + amount + "," + "\"currency\":\"" + currency + "\"," + "\"valueDate\":\""
				+ valueDate + "\"," + "\"debitAccount\":\"" + debitAcc + "\"," + "\"creditAccount\":\"" + creditAcc
				+ "\"," + "\"fromBank\":\"" + fromBank + "\"," + "\"toBank\":\"" + toBank + "\"," + "\"senderIFSC\":\""
				+ nullSafe(senderIFSC) + "\"," + "\"receiverIFSC\":\"" + nullSafe(receiverIFSC) + "\","
				+ "\"priority\":\"" + nullSafe(priority) + "\"," + "\"rbiRefNo\":\"" + nullSafe(rbiRefNo) + "\","
				+ "\"purpose\":\"" + nullSafe(purpose) + "\"," + "\"submittedAt\":\"" + nullSafe(submittedAt) + "\","
				+ "\"batchWindow\":\"" + nullSafe(batchWindow) + "\"," + "\"originalTxnRef\":\"" + originalTxnRef
				+ "\"," + "\"reversalReason\":\"" + reversalReason + "\"" + "}";
	}

	private String nullSafe(String value) {
		return value != null ? value : "";
	}
}