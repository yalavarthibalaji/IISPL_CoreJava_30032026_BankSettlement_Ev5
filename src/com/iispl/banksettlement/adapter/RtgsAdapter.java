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
 * RtgsAdapter — Adapter for the Real-Time Gross Settlement (RTGS) system.
 *
 * RTGS sends transactions as XML messages over Message Queue (MQ).
 *
 * RAW PAYLOAD FORMAT (XML — 14 fields):
 * ──────────────────────────────────────
 * <RTGSMessage>
 *   <MsgId>RTGS20260402001234</MsgId>           ← sourceRef
 *   <SenderIFSC>SBIN0001234</SenderIFSC>        ← extra field
 *   <ReceiverIFSC>HDFC0005678</ReceiverIFSC>    ← extra field
 *   <SenderAcct>CA90012345</SenderAcct>          ← debit account
 *   <ReceiverAcct>CA90067890</ReceiverAcct>      ← credit account
 *   <Amount>5000000.00</Amount>
 *   <Currency>INR</Currency>
 *   <ValueDate>2026-04-02</ValueDate>            ← format: yyyy-MM-dd
 *   <TxnType>CREDIT</TxnType>
 *   <Priority>HIGH</Priority>                    ← extra field
 *   <RBIRefNo>RBI20260402XYZ</RBIRefNo>         ← extra field
 *   <Purpose>TRADE_SETTLEMENT</Purpose>          ← extra field
 *   <SubmittedAt>2026-04-02T10:30:00</SubmittedAt> ← extra field
 *   <BatchWindow>W1</BatchWindow>               ← extra field
 * </RTGSMessage>
 *
 * DATE FORMAT NOTE:
 *   RTGS sends ValueDate in yyyy-MM-dd format (e.g. 2026-04-02).
 *   This is already ISO standard so LocalDate.parse() works directly.
 *
 * CANONICAL FIELDS → IncomingTransaction fields:
 *   MsgId        → sourceRef
 *   SenderAcct   → debitAccountNumber
 *   ReceiverAcct → creditAccountNumber
 *   Amount       → amount
 *   Currency     → currency
 *   ValueDate    → valueDate
 *   TxnType      → txnType
 *
 * EXTRA FIELDS → normalizedPayload JSON:
 *   SenderIFSC, ReceiverIFSC, Priority, RBIRefNo, Purpose, SubmittedAt, BatchWindow
 *
 * NOTE ON PARSING:
 *   No external XML library used — pure Core Java String parsing.
 *   We use extractXmlTag() to pull values from between XML tags.
 *
 * RTGS MINIMUM AMOUNT RULE:
 *   RTGS is for high-value transactions only. Minimum is Rs. 2,00,000 for INR.
 */
public class RtgsAdapter implements TransactionAdapter {

    // RTGS sends ValueDate as yyyy-MM-dd — standard ISO format
    private static final DateTimeFormatter RTGS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SourceSystem rtgsSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public RtgsAdapter() {
        this.rtgsSourceSystem = new SourceSystem(
            "RTGS",
            ProtocolType.MESSAGE_QUEUE,
            "{\"mq\":\"RBI_MQ\",\"queue\":\"RTGS.INBOUND\",\"endpoint\":\"rtgs.rbi.org.in\"}",
            true,
            "rtgs-support@rbi.org.in"
        );
        this.rtgsSourceSystem.setSourceSystemId(2L);
        this.rtgsSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "RtgsAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("RtgsAdapter: Skipping comment line");
        }

        // Verify this looks like an RTGS XML message
        if (!rawPayload.contains("<RTGSMessage>")) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Payload does not look like an RTGS XML message " +
                "(missing <RTGSMessage> root tag). Got: " + rawPayload
            );
        }

        // ---- Parse canonical fields ----
        String sourceRef        = extractXmlTag(rawPayload, "MsgId");
        String senderAcct       = extractXmlTag(rawPayload, "SenderAcct");
        String receiverAcct     = extractXmlTag(rawPayload, "ReceiverAcct");
        String amountStr        = extractXmlTag(rawPayload, "Amount");
        String currency         = extractXmlTag(rawPayload, "Currency");
        String valueDateStr     = extractXmlTag(rawPayload, "ValueDate");
        String txnTypeStr       = extractXmlTag(rawPayload, "TxnType");

        // Validate all canonical fields are present
        if (sourceRef == null || senderAcct == null || receiverAcct == null
                || amountStr == null || currency == null
                || valueDateStr == null || txnTypeStr == null) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing required XML fields. " +
                "Expected: MsgId, SenderAcct, ReceiverAcct, Amount, Currency, ValueDate, TxnType. " +
                "Got: " + rawPayload
            );
        }

        // ---- Parse extra fields (go into normalizedPayload) ----
        String senderIFSC   = extractXmlTag(rawPayload, "SenderIFSC");
        String receiverIFSC = extractXmlTag(rawPayload, "ReceiverIFSC");
        String priority     = extractXmlTag(rawPayload, "Priority");
        String rbiRefNo     = extractXmlTag(rawPayload, "RBIRefNo");
        String purpose      = extractXmlTag(rawPayload, "Purpose");
        String submittedAt  = extractXmlTag(rawPayload, "SubmittedAt");
        String batchWindow  = extractXmlTag(rawPayload, "BatchWindow");

        // ---- Type conversions ----
        BigDecimal amount       = new BigDecimal(amountStr);
        // RTGS sends ValueDate as yyyy-MM-dd — parse directly
        LocalDate valueDate     = LocalDate.parse(valueDateStr, RTGS_DATE_FORMAT);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // RTGS minimum amount rule — only for INR transactions
        if ("INR".equalsIgnoreCase(currency) && amount.compareTo(new BigDecimal("200000")) < 0) {
            throw new IllegalArgumentException(
                "RtgsAdapter: RTGS transactions must be minimum Rs. 2,00,000. Got: " + amount
            );
        }

        // ---- Build normalizedPayload — canonical + all RTGS extra fields ----
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            senderAcct, receiverAcct,
            senderIFSC, receiverIFSC, priority,
            rbiRefNo, purpose, submittedAt, batchWindow
        );

        IncomingTransaction txn = new IncomingTransaction(
            rtgsSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(senderAcct);
        txn.setCreditAccountNumber(receiverAcct);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("RTGS_ADAPTER");
        // requiresAccountValidation stays true (default) — RTGS sends real account numbers

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
     * Extracts the text content between <tagName> and </tagName> from an XML string.
     * Pure Core Java — no external XML library needed.
     *
     * Example: extractXmlTag("<Amount>5000000.00</Amount>", "Amount") → "5000000.00"
     *
     * @param xml     The full XML string
     * @param tagName The tag name to extract (without angle brackets)
     * @return        The text inside the tag, or null if the tag is not found
     */
    private String extractXmlTag(String xml, String tagName) {
        String openTag  = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";

        int startIndex = xml.indexOf(openTag);
        if (startIndex == -1) return null;

        int valueStart = startIndex + openTag.length();
        int valueEnd   = xml.indexOf(closeTag, valueStart);
        if (valueEnd == -1) return null;

        return xml.substring(valueStart, valueEnd).trim();
    }

    /**
     * Builds the normalizedPayload JSON string.
     * Contains all canonical fields + all RTGS-specific extra fields.
     * Null extra fields are stored as empty string to keep JSON valid.
     */
    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String senderIFSC, String receiverIFSC,
                                          String priority, String rbiRefNo,
                                          String purpose, String submittedAt,
                                          String batchWindow) {
        return "{"
            + "\"source\":\"RTGS\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"senderIFSC\":\"" + nullSafe(senderIFSC) + "\","
            + "\"receiverIFSC\":\"" + nullSafe(receiverIFSC) + "\","
            + "\"priority\":\"" + nullSafe(priority) + "\","
            + "\"rbiRefNo\":\"" + nullSafe(rbiRefNo) + "\","
            + "\"purpose\":\"" + nullSafe(purpose) + "\","
            + "\"submittedAt\":\"" + nullSafe(submittedAt) + "\","
            + "\"batchWindow\":\"" + nullSafe(batchWindow) + "\""
            + "}";
    }

    /** Returns the value if not null, otherwise returns empty string. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}