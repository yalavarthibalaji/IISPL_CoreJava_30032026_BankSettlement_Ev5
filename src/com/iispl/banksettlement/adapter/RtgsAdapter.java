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
 * RAW PAYLOAD FORMAT (XML — 16 fields, v3 adds SenderBank and ReceiverBank):
 * ─────────────────────────────────────────────────────────────────────────
 * <RTGSMessage>
 *   <MsgId>RTGS20260402001234</MsgId>               ← sourceRef
 *   <SenderIFSC>SBIN0001234</SenderIFSC>            ← extra field
 *   <ReceiverIFSC>HDFC0005678</ReceiverIFSC>        ← extra field
 *   <SenderAcct>RTGS-ACC-DR-001</SenderAcct>        ← debitAccount
 *   <ReceiverAcct>RTGS-ACC-CR-001</ReceiverAcct>    ← creditAccount
 *   <Amount>5000000.00</Amount>
 *   <Currency>INR</Currency>
 *   <ValueDate>2026-04-02</ValueDate>
 *   <TxnType>CREDIT</TxnType>
 *   <Priority>HIGH</Priority>
 *   <RBIRefNo>RBI20260402AAA</RBIRefNo>
 *   <Purpose>TRADE_SETTLEMENT</Purpose>
 *   <SubmittedAt>2026-04-02T10:30:00</SubmittedAt>
 *   <BatchWindow>W1</BatchWindow>
 *   <SenderBank>SBI Bank</SenderBank>               ← NEW (v3) — fromBank
 *   <ReceiverBank>HDFC Bank</ReceiverBank>           ← NEW (v3) — toBank
 * </RTGSMessage>
 *
 * RTGS MINIMUM AMOUNT RULE: Rs. 2,00,000 minimum for INR transactions.
 *
 * CHANGE LOG (v3 — fromBank / toBank):
 *   - <SenderBank> and <ReceiverBank> tags added to RTGS XML format.
 *   - Parsed using existing extractXmlTag() helper (pure Core Java, no library).
 *   - Stored as txn.fromBank / txn.toBank AND inside normalizedPayload JSON.
 *   - rtgs_transactions.xml updated with <SenderBank> and <ReceiverBank> tags.
 */
public class RtgsAdapter implements TransactionAdapter {

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

        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("RtgsAdapter: Skipping comment line");
        }

        if (!rawPayload.contains("<RTGSMessage>")) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Payload missing <RTGSMessage> root tag. Got: " + rawPayload
            );
        }

        // ---- Parse canonical fields ----
        String sourceRef    = extractXmlTag(rawPayload, "MsgId");
        String senderAcct   = extractXmlTag(rawPayload, "SenderAcct");
        String receiverAcct = extractXmlTag(rawPayload, "ReceiverAcct");
        String amountStr    = extractXmlTag(rawPayload, "Amount");
        String currency     = extractXmlTag(rawPayload, "Currency");
        String valueDateStr = extractXmlTag(rawPayload, "ValueDate");
        String txnTypeStr   = extractXmlTag(rawPayload, "TxnType");

        // ---- Parse fromBank / toBank ---- (NEW v3)
        String senderBank   = extractXmlTag(rawPayload, "SenderBank");
        String receiverBank = extractXmlTag(rawPayload, "ReceiverBank");

        if (sourceRef == null || senderAcct == null || receiverAcct == null
                || amountStr == null || currency == null
                || valueDateStr == null || txnTypeStr == null) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing required XML fields. " +
                "Expected: MsgId, SenderAcct, ReceiverAcct, Amount, Currency, ValueDate, TxnType. " +
                "Got: " + rawPayload
            );
        }

        if (senderBank == null || senderBank.isEmpty()) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing <SenderBank> tag. " +
                "All RTGS messages must include <SenderBank> and <ReceiverBank>. " +
                "Got: " + rawPayload
            );
        }

        if (receiverBank == null || receiverBank.isEmpty()) {
            throw new IllegalArgumentException(
                "RtgsAdapter: Missing <ReceiverBank> tag. " +
                "All RTGS messages must include <SenderBank> and <ReceiverBank>. " +
                "Got: " + rawPayload
            );
        }

        // ---- Parse extra fields ----
        String senderIFSC   = extractXmlTag(rawPayload, "SenderIFSC");
        String receiverIFSC = extractXmlTag(rawPayload, "ReceiverIFSC");
        String priority     = extractXmlTag(rawPayload, "Priority");
        String rbiRefNo     = extractXmlTag(rawPayload, "RBIRefNo");
        String purpose      = extractXmlTag(rawPayload, "Purpose");
        String submittedAt  = extractXmlTag(rawPayload, "SubmittedAt");
        String batchWindow  = extractXmlTag(rawPayload, "BatchWindow");

        // ---- Type conversions ----
        BigDecimal amount       = new BigDecimal(amountStr);
        LocalDate valueDate     = LocalDate.parse(valueDateStr, RTGS_DATE_FORMAT);
        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // RTGS minimum amount rule — Rs. 2,00,000 for INR
        if ("INR".equalsIgnoreCase(currency) && amount.compareTo(new BigDecimal("200000")) < 0) {
            throw new IllegalArgumentException(
                "RtgsAdapter: RTGS minimum is Rs. 2,00,000. Got: " + amount
            );
        }

        // ---- Build normalizedPayload — includes fromBank and toBank ----
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            senderAcct, receiverAcct,
            senderIFSC, receiverIFSC, priority,
            rbiRefNo, purpose, submittedAt, batchWindow,
            senderBank, receiverBank
        );

        // ---- Build IncomingTransaction ----
        IncomingTransaction txn = new IncomingTransaction(
            rtgsSourceSystem, sourceRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

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
     * Extracts text between <tagName> and </tagName>.
     * Pure Core Java — no XML library needed.
     * Example: extractXmlTag("<Amount>5000000.00</Amount>", "Amount") → "5000000.00"
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

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String senderIFSC, String receiverIFSC,
                                          String priority, String rbiRefNo,
                                          String purpose, String submittedAt,
                                          String batchWindow,
                                          String fromBank, String toBank) {
        return "{"
            + "\"source\":\"RTGS\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"fromBank\":\"" + fromBank + "\","
            + "\"toBank\":\"" + toBank + "\","
            + "\"senderIFSC\":\"" + nullSafe(senderIFSC) + "\","
            + "\"receiverIFSC\":\"" + nullSafe(receiverIFSC) + "\","
            + "\"priority\":\"" + nullSafe(priority) + "\","
            + "\"rbiRefNo\":\"" + nullSafe(rbiRefNo) + "\","
            + "\"purpose\":\"" + nullSafe(purpose) + "\","
            + "\"submittedAt\":\"" + nullSafe(submittedAt) + "\","
            + "\"batchWindow\":\"" + nullSafe(batchWindow) + "\""
            + "}";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}