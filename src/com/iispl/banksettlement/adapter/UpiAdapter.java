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
 * RAW PAYLOAD FORMAT (JSON — 14 fields):
 * ───────────────────────────────────────────────────────────────────
 *  JSON Key        Example Value             Notes
 * ───────────────────────────────────────────────────────────────────
 *  upiTxnId        UPI20260402XYZ9988        ← sourceRef
 *  payerVpa        ramesh@okicici            ← debitAccountNumber (VPA)
 *  payeeVpa        merchant@ybl              ← creditAccountNumber (VPA)
 *  payerName       Ramesh Kumar              ← extra field
 *  payeeName       SuperMart Pvt Ltd         ← extra field
 *  amount          4999.00
 *  currency        INR
 *  txnTimestamp    2026-04-02T11:45:22       ← used as valueDate (date part only)
 *  remarks         Groceries April           ← extra field
 *  pspCode         ICICI                     ← extra field
 *  deviceId        ANDR-UUID-88821           ← extra field
 *  mcc             5411                      ← extra field (Merchant Category Code)
 *  status          SUCCESS                   ← extra field
 *  rrn             RRN20260402001            ← extra field (Retrieval Reference Number)
 * ───────────────────────────────────────────────────────────────────
 *
 * DATE FORMAT NOTE:
 *   UPI sends txnTimestamp as ISO format: 2026-04-02T11:45:22
 *   We extract only the date part (2026-04-02) as the valueDate.
 *
 * IMPORTANT — NO ACCOUNT VALIDATION:
 *   UPI uses VPA addresses (Virtual Payment Addresses) like "ramesh@okicici"
 *   instead of bank account numbers. These VPAs do NOT exist in our accounts
 *   table in the database. Therefore:
 *     - payerVpa is stored as debitAccountNumber
 *     - payeeVpa is stored as creditAccountNumber
 *     - requiresAccountValidation is set to FALSE
 *   IngestionWorker will skip the account and customer KYC DB checks for UPI.
 *
 * TXNTYPE:
 *   UPI transactions are always CREDIT (money moves from payer to payee).
 *   The payee always receives a credit. We set txnType = CREDIT for all UPI.
 *
 * CANONICAL FIELDS → IncomingTransaction fields:
 *   upiTxnId      → sourceRef
 *   payerVpa      → debitAccountNumber  (VPA — not a real account number)
 *   payeeVpa      → creditAccountNumber (VPA — not a real account number)
 *   amount        → amount
 *   currency      → currency
 *   txnTimestamp  → valueDate (date portion only)
 *   (always)      → txnType = CREDIT
 *
 * EXTRA FIELDS → normalizedPayload JSON:
 *   payerName, payeeName, remarks, pspCode, deviceId, mcc, status, rrn
 */
public class UpiAdapter implements TransactionAdapter {

    // UPI sends timestamp as ISO datetime — we extract the date part
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

        // Skip comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("UpiAdapter: Skipping comment line");
        }

        // ---- Extract canonical fields ----
        String sourceRef      = extractJsonField(rawPayload, "upiTxnId");
        String payerVpa       = extractJsonField(rawPayload, "payerVpa");
        String payeeVpa       = extractJsonField(rawPayload, "payeeVpa");
        String amountStr      = extractJsonField(rawPayload, "amount");
        String currency       = extractJsonField(rawPayload, "currency");
        String txnTimestamp   = extractJsonField(rawPayload, "txnTimestamp");

        if (sourceRef == null || payerVpa == null || payeeVpa == null
                || amountStr == null || currency == null || txnTimestamp == null) {
            throw new IllegalArgumentException(
                "UpiAdapter: Missing required fields. " +
                "Expected: upiTxnId, payerVpa, payeeVpa, amount, currency, txnTimestamp. " +
                "Got: " + rawPayload
            );
        }

        // ---- Extract extra fields (go into normalizedPayload) ----
        String payerName = extractJsonField(rawPayload, "payerName");
        String payeeName = extractJsonField(rawPayload, "payeeName");
        String remarks   = extractJsonField(rawPayload, "remarks");
        String pspCode   = extractJsonField(rawPayload, "pspCode");
        String deviceId  = extractJsonField(rawPayload, "deviceId");
        String mcc       = extractJsonField(rawPayload, "mcc");
        String status    = extractJsonField(rawPayload, "status");
        String rrn       = extractJsonField(rawPayload, "rrn");

        // ---- Type conversions ----
        BigDecimal amount = new BigDecimal(amountStr);

        // Parse txnTimestamp → extract date part only for valueDate
        // Format: 2026-04-02T11:45:22 → LocalDateTime → toLocalDate() → 2026-04-02
        LocalDate valueDate = LocalDateTime.parse(txnTimestamp, UPI_TIMESTAMP_FORMAT).toLocalDate();

        // UPI transactions are always CREDIT (payee always receives a credit)
        TransactionType txnType = TransactionType.CREDIT;

        // ---- Build normalizedPayload — canonical + all UPI-specific extra fields ----
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            payerVpa, payeeVpa,
            payerName, payeeName, remarks,
            pspCode, deviceId, mcc, status, rrn
        );

        IncomingTransaction txn = new IncomingTransaction(
            upiSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        // Store VPA addresses as account identifiers
        txn.setDebitAccountNumber(payerVpa);
        txn.setCreditAccountNumber(payeeVpa);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("UPI_ADAPTER");

        // IMPORTANT: UPI uses VPA addresses, NOT real bank account numbers.
        // Setting this to false tells IngestionWorker to skip account/customer DB validation.
        txn.setRequiresAccountValidation(false);

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
     *
     * Example: extractJsonField("{\"amount\":\"4999.00\"}", "amount") → "4999.00"
     *
     * @param json      The raw JSON string
     * @param fieldName The key name to find
     * @return          The string value, or null if not found
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

        // Check if value is a quoted string or a bare value (number/boolean)
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

    /**
     * Builds the normalizedPayload JSON string.
     * Contains all canonical fields + all UPI-specific extra fields.
     * Note: payerVpa and payeeVpa are stored as-is (VPA addresses).
     */
    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String payerVpa, String payeeVpa,
                                          String payerName, String payeeName,
                                          String remarks, String pspCode,
                                          String deviceId, String mcc,
                                          String status, String rrn) {
        return "{"
            + "\"source\":\"UPI\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"payerVpa\":\"" + payerVpa + "\","
            + "\"payeeVpa\":\"" + payeeVpa + "\","
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

    /** Returns the value if not null, otherwise returns empty string. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}