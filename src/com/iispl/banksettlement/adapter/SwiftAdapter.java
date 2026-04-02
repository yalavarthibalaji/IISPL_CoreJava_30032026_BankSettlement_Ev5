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
 * SWIFT messages arrive via Message Queue (MQ).
 *
 * EXPECTED RAW PAYLOAD FORMAT (one line per transaction in swift.txt):
 *   :20:SWIFTREF:32A:YYMMDDCCYamt:23B:opcode:50K:debitAcc:59:creditAcc
 *
 * EXAMPLE:
 *   :20:SWIFT-REF-001:32A:240615USD75000.00:23B:CRED:50K:NOSTRO-001:59:ACC002
 *
 *   :20: = Transaction Reference Number (sourceRef)
 *   :32A: = YYMMDD + 3-char currency + amount
 *   :23B: = Operation code (CRED / DEBT / RVSL)
 *   :50K: = Ordering account (debit account number)
 *   :59:  = Beneficiary account (credit account number)
 */
public class SwiftAdapter implements TransactionAdapter {

    private SourceSystem swiftSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SwiftAdapter() {
        this.swiftSourceSystem = new SourceSystem(
            "SWIFT",
            ProtocolType.MESSAGE_QUEUE,
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

        String sourceRef        = extractSwiftField(rawPayload, ":20:");
        String field32A         = extractSwiftField(rawPayload, ":32A:");
        String opCode           = extractSwiftField(rawPayload, ":23B:");
        String debitAccountNum  = extractSwiftField(rawPayload, ":50K:");
        String creditAccountNum = extractSwiftField(rawPayload, ":59:");

        if (sourceRef == null || field32A == null || opCode == null
                || debitAccountNum == null || creditAccountNum == null) {
            throw new IllegalArgumentException(
                "SwiftAdapter: Missing required MT103 fields (:20:,:32A:,:23B:,:50K:,:59:). Got: " + rawPayload
            );
        }

        // Parse :32A: field — format is YYMMDD + 3-letter currency + amount
        if (field32A.length() < 10) {
            throw new IllegalArgumentException(
                "SwiftAdapter: :32A: field too short: " + field32A
            );
        }

        String yymmdd    = field32A.substring(0, 6);
        String currency  = field32A.substring(6, 9);
        String amountStr = field32A.substring(9);

        int year  = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day   = Integer.parseInt(yymmdd.substring(4, 6));
        LocalDate valueDate = LocalDate.of(year, month, day);

        BigDecimal amount       = new BigDecimal(amountStr);
        TransactionType txnType = parseSwiftOpCode(opCode);

        String normalizedPayload = buildNormalizedPayload(
            sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum
        );

        IncomingTransaction txn = new IncomingTransaction(
            swiftSourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
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

    private String extractSwiftField(String message, String fieldTag) {
        int startIndex = message.indexOf(fieldTag);
        if (startIndex == -1) return null;

        int valueStart = startIndex + fieldTag.length();
        int valueEnd   = message.length();

        // Find next field tag
        for (int i = valueStart + 1; i < message.length() - 1; i++) {
            if (message.charAt(i) == ':' && i + 1 < message.length()
                    && Character.isLetterOrDigit(message.charAt(i + 1))) {
                // Check if this looks like a field tag (colon at start)
                // We find the next occurrence of ":" that starts a new field
                valueEnd = i;
                break;
            }
        }

        return message.substring(valueStart, valueEnd).trim();
    }

    private TransactionType parseSwiftOpCode(String opCode) {
        if (opCode == null) return TransactionType.CREDIT;
        switch (opCode.toUpperCase().trim()) {
            case "CRED": case "CREDIT": case "SPAY":
                return TransactionType.CREDIT;
            case "DEBT": case "DEBIT":
                return TransactionType.DEBIT;
            case "RVSL": case "REVERSAL":
                return TransactionType.REVERSAL;
            default:
                return TransactionType.CREDIT;
        }
    }

    private String buildNormalizedPayload(String sourceRef, TransactionType txnType,
                                           BigDecimal amount, String currency,
                                           LocalDate valueDate,
                                           String debitAcc, String creditAcc) {
        return "{" +
               "\"source\":\"SWIFT\"," +
               "\"sourceRef\":\"" + sourceRef + "\"," +
               "\"txnType\":\"" + txnType.name() + "\"," +
               "\"amount\":" + amount + "," +
               "\"currency\":\"" + currency + "\"," +
               "\"valueDate\":\"" + valueDate + "\"," +
               "\"debitAccount\":\"" + debitAcc + "\"," +
               "\"creditAccount\":\"" + creditAcc + "\"" +
               "}";
    }
}