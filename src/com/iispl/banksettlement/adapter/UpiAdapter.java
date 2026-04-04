package com.iispl.banksettlement.adapter;

import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * UpiAdapter — Adapter for UPI (Unified Payments Interface) transactions.
 *
 * UPI sends real-time payment notifications as JSON via REST webhooks.
 *
 * RAW PAYLOAD FORMAT (JSON — 16 fields, v3 adds payerBank and payeeBank):
 * ──────────────────────────────────────────────────────────────────────────
 *  JSON Key        Example Value             Notes
 * ──────────────────────────────────────────────────────────────────────────
 *  upiTxnId        UPI20260402XYZ9988        ← sourceRef
 *  payerVpa        ramesh@okicici            ← debitAccount
 *  payeeVpa        merchant@ybl              ← creditAccount
 *  payerName       Ramesh Kumar
 *  payeeName       SuperMart Pvt Ltd
 *  amount          4999.00
 *  currency        INR
 *  txnTimestamp    2026-04-02T11:45:22       ← valueDate (date part only)
 *  remarks         Groceries April
 *  pspCode         ICICI
 *  deviceId        ANDR-UUID-88821
 *  mcc             5411
 *  status          SUCCESS
 *  rrn             RRN20260402001
 *  payerBank       ICICI Bank                ← NEW (v3) — fromBank
 *  payeeBank       Yes Bank                  ← NEW (v3) — toBank
 * ──────────────────────────────────────────────────────────────────────────
 *
 * CHANGE LOG (v3 — fromBank / toBank):
 *   - payerBank and payeeBank added as explicit JSON fields in the payload.
 *   - Parsed using existing extractJsonField() helper (pure Core Java, no library).
 *   - Stored as txn.fromBank / txn.toBank AND inside normalizedPayload JSON.
 *   - upi_transactions.json updated with payerBank and payeeBank fields.
 *
 * NOTE ON VPA ADDRESSES:
 *   UPI still uses VPA addresses (payerVpa, payeeVpa) instead of real account
 *   numbers. These are stored as debitAccount / creditAccount in normalizedPayload.
 *   The "accountValidationSkipped":true flag signals the settlement engine to
 *   handle VPA resolution rather than direct DB account lookup.
 */
public class UpiAdapter implements TransactionAdapter {

    private static final DateTimeFormatter UPI_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SourceSystem upiSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public UpiAdapter() {
        this.upiSourceSystem = new SourceSystem(
            "UPI",
            ProtocolType.REST_API,
            "{\"endpoint\":\"https://upi.npci.org.in/webhook\",\"protocol\":\"JSON_REST\"}",
            true,
            "upi-ops@npci.org.in"
        );
        this.upiSourceSystem.setSourceSystemId(5L);
        this.upiSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "UpiAdapter: rawPayload cannot be null or empty"
            );
        }

        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("UpiAdapter: Skipping comment line");
        }

        // ---- Extract canonical fields ----
        String sourceRef    = extractJsonField(rawPayload, "upiTxnId");
        String payerVpa     = extractJsonField(rawPayload, "payerVpa");
        String payeeVpa     = extractJsonField(rawPayload, "payeeVpa");
        String amountStr    = extractJsonField(rawPayload, "amount");
        String currency     = extractJsonField(rawPayload, "currency");
        String txnTimestamp = extractJsonField(rawPayload, "txnTimestamp");

        // ---- Extract fromBank / toBank ---- (NEW v3)
        String payerBank    = extractJsonField(rawPayload, "payerBank");
        String payeeBank    = extractJsonField(rawPayload, "payeeBank");

        if (sourceRef == null || payerVpa == null || payeeVpa == null
                || amountStr == null || currency == null || txnTimestamp == null) {
            throw new IllegalArgumentException(
                "UpiAdapter: Missing required fields. " +
                "Expected: upiTxnId, payerVpa, payeeVpa, amount, currency, txnTimestamp. " +
                "Got: " + rawPayload
            );
        }

        if (payerBank == null || payerBank.isEmpty()) {
            throw new IllegalArgumentException(
                "UpiAdapter: Missing 'payerBank' field. " +
                "All UPI payloads must include payerBank and payeeBank. " +
                "Got: " + rawPayload
            );
        }

        if (payeeBank == null || payeeBank.isEmpty()) {
            throw new IllegalArgumentException(
                "UpiAdapter: Missing 'payeeBank' field. " +
                "All UPI payloads must include payerBank and payeeBank. " +
                "Got: " + rawPayload
            );
        }

        // ---- Extract extra fields ----
        String payerName = extractJsonField(rawPayload, "payerName");
        String payeeName = extractJsonField(rawPayload, "payeeName");
        String remarks   = extractJsonField(rawPayload, "remarks");
        String pspCode   = extractJsonField(rawPayload, "pspCode");
        String deviceId  = extractJsonField(rawPayload, "deviceId");
        String mcc       = extractJsonField(rawPayload, "mcc");
        String status    = extractJsonField(rawPayload, "status");
        String rrn       = extractJsonField(rawPayload, "rrn");

        // ---- Type conversions ----
        BigDecimal amount   = new BigDecimal(amountStr);
        LocalDate valueDate = LocalDateTime.parse(txnTimestamp, UPI_TIMESTAMP_FORMAT).toLocalDate();
        TransactionType txnType = TransactionType.CREDIT; // UPI is always CREDIT

        // ---- Build normalizedPayload — includes fromBank and toBank ----
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            payerVpa, payeeVpa,
            payerName, payeeName, remarks,
            pspCode, deviceId, mcc, status, rrn,
            payerBank, payeeBank
        );

        // ---- Build IncomingTransaction ----
        IncomingTransaction txn = new IncomingTransaction(
            upiSourceSystem, sourceRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

        txn.setFromBank(payerBank);
        txn.setToBank(payeeBank);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("UPI_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.UPI;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts a value from a JSON string by field name.
     * Pure Core Java — no external JSON library needed.
     * Example: extractJsonField("{\"amount\":\"4999.00\"}", "amount") → "4999.00"
     */
    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();

        // Skip whitespace after colon
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }

        boolean isQuoted = (valueStart < json.length() && json.charAt(valueStart) == '"');

        if (isQuoted) {
            int openQuote  = valueStart;
            int closeQuote = json.indexOf('"', openQuote + 1);
            if (closeQuote == -1) return null;
            return json.substring(openQuote + 1, closeQuote);
        } else {
            int endIndex = json.indexOf(',', valueStart);
            if (endIndex == -1) endIndex = json.indexOf('}', valueStart);
            if (endIndex == -1) endIndex = json.length();
            return json.substring(valueStart, endIndex).trim();
        }
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String payerVpa, String payeeVpa,
                                          String payerName, String payeeName,
                                          String remarks, String pspCode,
                                          String deviceId, String mcc,
                                          String status, String rrn,
                                          String fromBank, String toBank) {
        return "{"
            + "\"source\":\"UPI\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + payerVpa + "\","
            + "\"creditAccount\":\"" + payeeVpa + "\","
            + "\"fromBank\":\"" + fromBank + "\","
            + "\"toBank\":\"" + toBank + "\","
            + "\"payerName\":\"" + nullSafe(payerName) + "\","
            + "\"payeeName\":\"" + nullSafe(payeeName) + "\","
            + "\"remarks\":\"" + nullSafe(remarks) + "\","
            + "\"pspCode\":\"" + nullSafe(pspCode) + "\","
            + "\"deviceId\":\"" + nullSafe(deviceId) + "\","
            + "\"mcc\":\"" + nullSafe(mcc) + "\","
            + "\"status\":\"" + nullSafe(status) + "\","
            + "\"rrn\":\"" + nullSafe(rrn) + "\","
            + "\"accountValidationSkipped\":true"
            + "}";
    }

    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}