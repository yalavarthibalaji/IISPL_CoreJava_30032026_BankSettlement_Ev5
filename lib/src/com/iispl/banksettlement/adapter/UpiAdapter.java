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
 *  payerVpa        ramesh@okicici            ← debitAccount in normalizedPayload
 *  payeeVpa        merchant@ybl              ← creditAccount in normalizedPayload
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
 * IMPORTANT — VPA ADDRESSES vs ACCOUNT NUMBERS:
 *   UPI uses Virtual Payment Addresses (payerVpa, payeeVpa) like "ramesh@okicici"
 *   instead of bank account numbers. These VPAs are stored as "debitAccount" and
 *   "creditAccount" inside normalizedPayload. The settlement engine will interpret
 *   the "accountValidationSkipped":true flag and handle VPA resolution separately.
 *
 * CHANGE LOG (v2):
 *   - currency removed from IncomingTransaction field (INR-only system).
 *   - payerVpa / payeeVpa no longer set as debitAccountNumber / creditAccountNumber
 *     (those fields removed). They now live ONLY in normalizedPayload.
 *   - requiresAccountValidation removed from IncomingTransaction.
 *     The "accountValidationSkipped":true flag in normalizedPayload signals to
 *     the settlement engine that these are VPA addresses, not real account numbers.
 *   - IncomingTransaction constructor no longer takes currency parameter.
 */
public class UpiAdapter implements TransactionAdapter {

    private static final DateTimeFormatter UPI_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SourceSystem upiSourceSystem;

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

        String sourceRef    = extractJsonField(rawPayload, "upiTxnId");
        String payerVpa     = extractJsonField(rawPayload, "payerVpa");
        String payeeVpa     = extractJsonField(rawPayload, "payeeVpa");
        String amountStr    = extractJsonField(rawPayload, "amount");
        String currency     = extractJsonField(rawPayload, "currency");
        String txnTimestamp = extractJsonField(rawPayload, "txnTimestamp");

        if (sourceRef == null || payerVpa == null || payeeVpa == null
                || amountStr == null || currency == null || txnTimestamp == null) {
            throw new IllegalArgumentException(
                "UpiAdapter: Missing required fields. " +
                "Expected: upiTxnId, payerVpa, payeeVpa, amount, currency, txnTimestamp. " +
                "Got: " + rawPayload
            );
        }

        String payerName = extractJsonField(rawPayload, "payerName");
        String payeeName = extractJsonField(rawPayload, "payeeName");
        String remarks   = extractJsonField(rawPayload, "remarks");
        String pspCode   = extractJsonField(rawPayload, "pspCode");
        String deviceId  = extractJsonField(rawPayload, "deviceId");
        String mcc       = extractJsonField(rawPayload, "mcc");
        String status    = extractJsonField(rawPayload, "status");
        String rrn       = extractJsonField(rawPayload, "rrn");

        BigDecimal amount = new BigDecimal(amountStr);
        LocalDate valueDate = LocalDateTime.parse(txnTimestamp, UPI_TIMESTAMP_FORMAT).toLocalDate();
        TransactionType txnType = TransactionType.CREDIT;

        // VPA addresses stored as debitAccount / creditAccount in normalizedPayload.
        // The settlement engine reads "accountValidationSkipped":true and handles
        // VPA resolution instead of direct account DB lookup.
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            payerVpa, payeeVpa,
            payerName, payeeName, remarks,
            pspCode, deviceId, mcc, status, rrn
        );

        IncomingTransaction txn = new IncomingTransaction(
            upiSourceSystem, sourceRef, rawPayload,
            txnType, amount, valueDate, normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("UPI_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.UPI;
    }

    private String extractJsonField(String json, String fieldName) {
        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;
        int valueStart = keyIndex + searchKey.length();
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
                                          String status, String rrn) {
        return "{"
            + "\"source\":\"UPI\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + payerVpa + "\","
            + "\"creditAccount\":\"" + payeeVpa + "\","
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
