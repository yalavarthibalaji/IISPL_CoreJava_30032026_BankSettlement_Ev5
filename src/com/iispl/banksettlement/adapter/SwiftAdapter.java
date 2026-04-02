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
 * SwiftAdapter — Adapter for SWIFT cross-border international transactions.
 *
 * SWIFT messages arrive via SFTP or Message Queue (MQ) in MT103 format (ISO 15022).
 *
 * RAW PAYLOAD FORMAT (MT103 — 14 tagged fields):
 * ─────────────────────────────────────────────────────────────────
 *  Tag    Field Name               Example Value
 * ─────────────────────────────────────────────────────────────────
 *  :20:   Transaction Reference    SWFT20260402ABC123        ← sourceRef
 *  :23B:  Bank Operation Code      CRED                      ← txnType indicator
 *  :32A:  Value Date/Ccy/Amount    260402USD150000,00        ← date+currency+amount
 *  :33B:  Currency/Amount          USD150000,00              ← extra field
 *  :36:   Exchange Rate            83,50                     ← extra field (FX rate)
 *  :50K:  Ordering Customer Acct   /US12345678901            ← debit account
 *  :52A:  Ordering Institution     CITIUSNYX                 ← extra field (BIC)
 *  :56A:  Intermediary Bank        DEUTDEDBFRA               ← extra field (BIC)
 *  :57A:  Account With Institution HDFCINBB                  ← extra field (BIC)
 *  :59:   Beneficiary Account      /IN90HDFC0001234567       ← credit account
 *  :70:   Remittance Info          INVOICE INV-2026-00456    ← extra field
 *  :71A:  Charge Allocation        SHA                       ← extra field
 *  :72:   Sender to Receiver Info  /ACC/URGENT               ← extra field
 *  :77B:  Regulatory Reporting     /ORDERRES/IN//            ← extra field
 * ─────────────────────────────────────────────────────────────────
 *
 * EXAMPLE PAYLOAD (single-line MT103):
 *   :20:SWFT20260402ABC123:23B:CRED:32A:260402USD150000,00:33B:USD150000,00
 *   :36:83,50:50K:/US12345678901:52A:CITIUSNYX:56A:DEUTDEDBFRA:57A:HDFCINBB
 *   :59:/IN90HDFC0001234567:70:INVOICE INV-2026-00456:71A:SHA:72:/ACC/URGENT
 *   :77B:/ORDERRES/IN//
 *
 * DATE FORMAT NOTE:
 *   :32A: contains date as YYMMDD (e.g. 260402 = 2nd April 2026).
 *   Year is 2000 + YY. This adapter converts it to LocalDate.
 *
 * AMOUNT FORMAT NOTE:
 *   SWIFT uses comma as decimal separator (e.g. 150000,00).
 *   This adapter replaces comma with dot before parsing to BigDecimal.
 *
 * ACCOUNT FORMAT NOTE:
 *   :50K: and :59: accounts are prefixed with "/" (e.g. /US12345678901).
 *   The "/" prefix is stripped to get the clean account number.
 *
 * CANONICAL FIELDS → IncomingTransaction fields:
 *   :20:  → sourceRef
 *   :50K: → debitAccountNumber  (stripped of leading "/")
 *   :59:  → creditAccountNumber (stripped of leading "/")
 *   :32A: → amount, currency, valueDate
 *   :23B: → txnType (CRED→CREDIT, DEBT→DEBIT, RVSL→REVERSAL)
 *
 * EXTRA FIELDS → normalizedPayload JSON:
 *   :33B:, :36:, :52A:, :56A:, :57A:, :70:, :71A:, :72:, :77B:
 */
public class SwiftAdapter implements TransactionAdapter {

    private final SourceSystem swiftSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SwiftAdapter() {
        this.swiftSourceSystem = new SourceSystem(
            "SWIFT",
            ProtocolType.MESSAGE_QUEUE,
            "{\"mq\":\"SWIFT_MQ\",\"queue\":\"SWIFT.INBOUND\",\"protocol\":\"MT103\"}",
            true,
            "swift-ops@bank.com"
        );
        this.swiftSourceSystem.setSourceSystemId(3L);
        this.swiftSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "SwiftAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip comment lines
        if (rawPayload.trim().startsWith("#")) {
            throw new IllegalArgumentException("SwiftAdapter: Skipping comment line");
        }

        // ---- Extract canonical fields ----
        String sourceRef  = extractSwiftField(rawPayload, ":20:");
        String opCode     = extractSwiftField(rawPayload, ":23B:");
        String field32A   = extractSwiftField(rawPayload, ":32A:");
        String field50K   = extractSwiftField(rawPayload, ":50K:");
        String field59    = extractSwiftField(rawPayload, ":59:");

        if (sourceRef == null || opCode == null || field32A == null
                || field50K == null || field59 == null) {
            throw new IllegalArgumentException(
                "SwiftAdapter: Missing required MT103 fields " +
                "(:20:, :23B:, :32A:, :50K:, :59:). Got: " + rawPayload
            );
        }

        // ---- Parse :32A: — format is YYMMDD + 3-letter currency + amount ----
        // Example: 260402USD150000,00
        if (field32A.length() < 10) {
            throw new IllegalArgumentException(
                "SwiftAdapter: :32A: field is too short to parse. Got: " + field32A
            );
        }

        String yymmdd    = field32A.substring(0, 6);   // e.g. "260402"
        String currency  = field32A.substring(6, 9);   // e.g. "USD"
        String amountStr = field32A.substring(9);       // e.g. "150000,00"

        // Parse YYMMDD date — year is 2000 + YY
        int year  = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day   = Integer.parseInt(yymmdd.substring(4, 6));
        LocalDate valueDate = LocalDate.of(year, month, day);

        // SWIFT uses comma as decimal separator — replace with dot for BigDecimal
        BigDecimal amount = new BigDecimal(amountStr.replace(",", "."));

        // Map :23B: operation code → TransactionType enum
        TransactionType txnType = parseSwiftOpCode(opCode);

        // ---- Clean account numbers — strip leading "/" ----
        // :50K: /US12345678901  →  US12345678901
        // :59:  /IN90HDFC...    →  IN90HDFC...
        String debitAccountNum  = stripLeadingSlash(field50K);
        String creditAccountNum = stripLeadingSlash(field59);

        // ---- Extract extra fields (go into normalizedPayload) ----
        String field33B = extractSwiftField(rawPayload, ":33B:");  // Currency/Instructed Amount
        String field36  = extractSwiftField(rawPayload, ":36:");   // Exchange Rate
        String field52A = extractSwiftField(rawPayload, ":52A:");  // Ordering Institution BIC
        String field56A = extractSwiftField(rawPayload, ":56A:");  // Intermediary Bank BIC
        String field57A = extractSwiftField(rawPayload, ":57A:");  // Account With Institution BIC
        String field70  = extractSwiftField(rawPayload, ":70:");   // Remittance Info
        String field71A = extractSwiftField(rawPayload, ":71A:");  // Charge Allocation (SHA/OUR/BEN)
        String field72  = extractSwiftField(rawPayload, ":72:");   // Sender to Receiver Info
        String field77B = extractSwiftField(rawPayload, ":77B:");  // Regulatory Reporting

        // ---- Build normalizedPayload — canonical + all extra SWIFT fields ----
        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum,
            field33B, field36, field52A, field56A,
            field57A, field70, field71A, field72, field77B
        );

        IncomingTransaction txn = new IncomingTransaction(
            swiftSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("SWIFT_ADAPTER");
        // requiresAccountValidation stays true (default) — SWIFT sends real account numbers

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.SWIFT;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Extracts the value after a SWIFT field tag (:20:, :32A: etc.)
     * and before the next field tag.
     *
     * MT103 fields are separated by the pattern ":<chars>:" where chars
     * are alphanumeric. We find where the next tag starts to get the end boundary.
     *
     * Example: extractSwiftField(":20:SWFT123:32A:260402USD...", ":20:") → "SWFT123"
     *
     * @param message  The full MT103 message string
     * @param fieldTag The tag to extract e.g. ":20:", ":32A:"
     * @return         The value after the tag up to the next tag, or null if not found
     */
    private String extractSwiftField(String message, String fieldTag) {
        int startIndex = message.indexOf(fieldTag);
        if (startIndex == -1) return null;

        int valueStart = startIndex + fieldTag.length();
        int valueEnd   = message.length();

        // Search for the next SWIFT tag starting from valueStart
        // A SWIFT tag looks like ":XY:" or ":XXX:" — colon, 2-3 alphanumeric chars, colon
        for (int i = valueStart + 1; i < message.length() - 2; i++) {
            if (message.charAt(i) == ':') {
                // Check if this is a tag: colon followed by alphanumeric chars then colon
                int j = i + 1;
                while (j < message.length() && Character.isLetterOrDigit(message.charAt(j))) {
                    j++;
                }
                // j should now point to the closing colon of the tag
                if (j > i + 1 && j < message.length() && message.charAt(j) == ':') {
                    valueEnd = i;
                    break;
                }
            }
        }

        return message.substring(valueStart, valueEnd).trim();
    }

    /**
     * Maps SWIFT :23B: operation codes to our TransactionType enum.
     *
     * CRED / SPAY → CREDIT
     * DEBT        → DEBIT
     * RVSL        → REVERSAL
     * Default     → CREDIT (most SWIFT transactions are credits)
     */
    private TransactionType parseSwiftOpCode(String opCode) {
        if (opCode == null) return TransactionType.CREDIT;
        switch (opCode.toUpperCase().trim()) {
            case "CRED":
            case "SPAY":
                return TransactionType.CREDIT;
            case "DEBT":
                return TransactionType.DEBIT;
            case "RVSL":
                return TransactionType.REVERSAL;
            default:
                return TransactionType.CREDIT;
        }
    }

    /**
     * Strips the leading "/" from SWIFT account fields.
     * :50K: /US12345678901 → US12345678901
     * :59:  /IN90HDFC...   → IN90HDFC...
     * If no leading slash, returns the value as-is.
     */
    private String stripLeadingSlash(String accountField) {
        if (accountField == null) return null;
        // Account field may have multiple lines — take only first line (account number line)
        String firstLine = accountField.split("\n")[0].trim();
        return firstLine.startsWith("/") ? firstLine.substring(1) : firstLine;
    }

    /**
     * Builds the normalizedPayload JSON string.
     * Contains all canonical fields + all SWIFT-specific extra fields.
     * Null extra fields are stored as empty string to keep JSON valid.
     */
    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                          BigDecimal amount, String currency,
                                          LocalDate valueDate,
                                          String debitAcc, String creditAcc,
                                          String field33B, String exchangeRate,
                                          String orderingBic, String intermediaryBic,
                                          String accountWithBic, String remittanceInfo,
                                          String chargeType, String senderToReceiver,
                                          String regulatoryRef) {
        return "{"
            + "\"source\":\"SWIFT\","
            + "\"sourceRef\":\"" + sourceRef + "\","
            + "\"txnType\":\"" + txnType.name() + "\","
            + "\"amount\":" + amount + ","
            + "\"currency\":\"" + currency + "\","
            + "\"valueDate\":\"" + valueDate + "\","
            + "\"debitAccount\":\"" + debitAcc + "\","
            + "\"creditAccount\":\"" + creditAcc + "\","
            + "\"instructedAmount\":\"" + nullSafe(field33B) + "\","
            + "\"exchangeRate\":\"" + nullSafe(exchangeRate) + "\","
            + "\"orderingInstitutionBic\":\"" + nullSafe(orderingBic) + "\","
            + "\"intermediaryBankBic\":\"" + nullSafe(intermediaryBic) + "\","
            + "\"accountWithInstitutionBic\":\"" + nullSafe(accountWithBic) + "\","
            + "\"remittanceInfo\":\"" + nullSafe(remittanceInfo) + "\","
            + "\"chargeAllocation\":\"" + nullSafe(chargeType) + "\","
            + "\"senderToReceiverInfo\":\"" + nullSafe(senderToReceiver) + "\","
            + "\"regulatoryReporting\":\"" + nullSafe(regulatoryRef) + "\""
            + "}";
    }

    /** Returns the value if not null, otherwise returns empty string. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}