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
 * SWIFT (Society for Worldwide Interbank Financial Telecommunication) is the
 * global messaging network used for international money transfers.
 * The standard message type for payments is MT103 (Single Customer Credit Transfer).
 *
 * SWIFT sends messages via Message Queue (MQ).
 *
 * EXPECTED RAW PAYLOAD FORMAT (simplified MT103-style colon-delimited):
 *   ":20:SWIFT-REF-2024001:32A:240615USD75000.00:23B:CREDIT"
 *
 *   Field codes:
 *     :20: = Transaction Reference Number (sourceRef)
 *     :32A:= Value Date + Currency + Amount  (YYMMDD + 3-char currency + amount)
 *     :23B:= Bank Operation Code             (txnType)
 *
 * Implements: TransactionAdapter (Strategy Pattern)
 */
public class SwiftAdapter implements TransactionAdapter {

    private SourceSystem swiftSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SwiftAdapter() {
        this.swiftSourceSystem = new SourceSystem(
            "SWIFT",
            ProtocolType.MESSAGE_QUEUE,        // SWIFT messages arrive via MQ
            "{\"mq\":\"SWIFT_MQ\",\"queue\":\"SWIFT.INBOUND\"}",
            true,
            "swift-ops@bank.com"
        );
        this.swiftSourceSystem.setSourceSystemId(3L);
        this.swiftSourceSystem.setCreatedBy("SYSTEM");
    }

    // -----------------------------------------------------------------------
    // TransactionAdapter implementation
    // -----------------------------------------------------------------------

    /**
     * Parses a simplified SWIFT MT103 payload and returns a canonical IncomingTransaction.
     *
     * Example input:
     *   ":20:SWIFT-REF-2024001:32A:240615USD75000.00:23B:CREDIT"
     */
    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "SwiftAdapter: rawPayload cannot be null or empty"
            );
        }

        // Extract field :20: — Transaction Reference Number
        String sourceRef = extractSwiftField(rawPayload, ":20:");

        // Extract field :32A: — ValueDate + Currency + Amount (e.g. "240615USD75000.00")
        String field32A  = extractSwiftField(rawPayload, ":32A:");

        // Extract field :23B: — Operation code maps to our TransactionType
        String opCode    = extractSwiftField(rawPayload, ":23B:");

        if (sourceRef == null || field32A == null || opCode == null) {
            throw new IllegalArgumentException(
                "SwiftAdapter: Missing required MT103 fields (:20:, :32A:, :23B:) in payload: " + rawPayload
            );
        }

        // Parse :32A: field — format is YYMMDD + 3-letter currency + amount
        // Example: "240615USD75000.00"
        //           ^^^^^^ = date (YYMMDD)
        //                 ^^^ = currency
        //                    ^^^^^^^^ = amount
        if (field32A.length() < 10) {
            throw new IllegalArgumentException(
                "SwiftAdapter: :32A: field too short to parse: " + field32A
            );
        }

        String yymmdd    = field32A.substring(0, 6);    // "240615"
        String currency  = field32A.substring(6, 9);    // "USD"
        String amountStr = field32A.substring(9);       // "75000.00"

        // Convert YYMMDD to LocalDate (prefix "20" to make it YYYY)
        int year  = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day   = Integer.parseInt(yymmdd.substring(4, 6));
        LocalDate valueDate = LocalDate.of(year, month, day);

        BigDecimal amount   = new BigDecimal(amountStr);
        TransactionType txnType = parseSwiftOpCode(opCode);

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate
        );

        IncomingTransaction txn = new IncomingTransaction(
            swiftSourceSystem,
            sourceRef,
            rawPayload,
            txnType,
            amount,
            currency,
            valueDate,
            normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("SWIFT_ADAPTER");

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
     * Extracts the value of a SWIFT field tag from the message.
     *
     * Example: extractSwiftField(":20:SWIFTREF001:32A:...", ":20:")
     *          returns "SWIFTREF001"
     *
     * @param message  Full SWIFT message string
     * @param fieldTag The field tag to search for (e.g. ":20:", ":32A:")
     * @return The value after the tag until the next colon-tag or end of string
     */
    private String extractSwiftField(String message, String fieldTag) {
        int startIndex = message.indexOf(fieldTag);
        if (startIndex == -1) {
            return null;
        }

        // Skip past the field tag itself
        int valueStart = startIndex + fieldTag.length();

        // Find next field tag (starts with ":" after current position)
        int valueEnd = message.length();
        for (int i = valueStart + 1; i < message.length() - 1; i++) {
            // A new field starts with ":" followed by alphanumeric chars and another ":"
            if (message.charAt(i) == ':') {
                valueEnd = i;
                break;
            }
        }

        return message.substring(valueStart, valueEnd).trim();
    }

    /**
     * Maps a SWIFT bank operation code to our internal TransactionType enum.
     *
     * Common SWIFT operation codes:
     *   CRED = Customer Credit Transfer → CREDIT
     *   DEBT = Customer Debit           → DEBIT
     *   RVSL = Reversal                 → REVERSAL
     *   SPAY = Standing Payment         → CREDIT
     */
    private TransactionType parseSwiftOpCode(String opCode) {
        if (opCode == null) {
            return TransactionType.CREDIT; // default fallback
        }
        switch (opCode.toUpperCase().trim()) {
            case "CRED":
            case "CREDIT":
            case "SPAY":
                return TransactionType.CREDIT;
            case "DEBT":
            case "DEBIT":
                return TransactionType.DEBIT;
            case "RVSL":
            case "REVERSAL":
                return TransactionType.REVERSAL;
            default:
                return TransactionType.CREDIT;
        }
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                           BigDecimal amount, String currency,
                                           LocalDate valueDate) {
        return "{" +
               "\"source\":\"SWIFT\"," +
               "\"sourceRef\":\"" + sourceRef + "\"," +
               "\"txnType\":\"" + txnType.name() + "\"," +
               "\"amount\":" + amount + "," +
               "\"currency\":\"" + currency + "\"," +
               "\"valueDate\":\"" + valueDate + "\"" +
               "}";
    }
}
