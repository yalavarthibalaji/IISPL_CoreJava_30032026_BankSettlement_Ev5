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
 * NeftUpiAdapter — Adapter for NEFT and UPI domestic retail transactions.
 *
 * EXPECTED RAW PAYLOAD FORMAT (one line per transaction in neft.csv):
 *   source,sourceRef,txnType,amount,currency,valueDate,debitAccount,creditAccount
 *
 * EXAMPLE LINES:
 *   NEFT,NEFT-REF-001,CREDIT,25000.00,INR,2024-06-15,ACC001,ACC003
 *   UPI,UPI-REF-001,DEBIT,500.00,INR,2024-06-15,ACC002,ACC004
 *
 *   [0] source         — NEFT or UPI
 *   [1] sourceRef      — reference number
 *   [2] txnType        — CREDIT / DEBIT
 *   [3] amount         — transaction amount
 *   [4] currency       — INR
 *   [5] valueDate      — yyyy-MM-dd
 *   [6] debitAccount   — account being debited
 *   [7] creditAccount  — account being credited
 */
public class NeftUpiAdapter implements TransactionAdapter {

    private SourceSystem neftSourceSystem;
    private SourceSystem upiSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public NeftUpiAdapter() {
        this.neftSourceSystem = new SourceSystem(
            "NEFT",
            ProtocolType.SFTP,
            "{\"sftpHost\":\"sftp.npci.org.in\",\"directory\":\"/neft/inbound\"}",
            true,
            "neft-ops@npci.org.in"
        );
        this.neftSourceSystem.setSourceSystemId(4L);
        this.neftSourceSystem.setCreatedBy("SYSTEM");

        this.upiSourceSystem = new SourceSystem(
            "UPI",
            ProtocolType.REST_API,
            "{\"endpoint\":\"https://upi.npci.org.in/webhook\"}",
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
                "NeftUpiAdapter: rawPayload cannot be null or empty"
            );
        }

        // Skip header and comment lines
        if (rawPayload.trim().startsWith("#") || rawPayload.trim().startsWith("source")) {
            throw new IllegalArgumentException(
                "NeftUpiAdapter: Skipping header/comment line"
            );
        }

        String[] parts = rawPayload.split(",");

        // Now requires 8 fields (added debitAccount and creditAccount)
        if (parts.length < 8) {
            throw new IllegalArgumentException(
                "NeftUpiAdapter: Invalid CSV. Expected 8 comma-separated fields: " +
                "source,sourceRef,txnType,amount,currency,valueDate,debitAccount,creditAccount. Got: " + rawPayload
            );
        }

        String sourceCode       = parts[0].trim().toUpperCase();
        String sourceRef        = parts[1].trim();
        String txnTypeStr       = parts[2].trim();
        BigDecimal amount       = new BigDecimal(parts[3].trim());
        String currency         = parts[4].trim();
        LocalDate valueDate     = LocalDate.parse(parts[5].trim());
        String debitAccountNum  = parts[6].trim();
        String creditAccountNum = parts[7].trim();

        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        SourceSystem sourceSystem = "UPI".equals(sourceCode) ? upiSourceSystem : neftSourceSystem;

        String normalizedPayload = buildNormalizedPayload(
            sourceCode, sourceRef, txnType, amount, currency, valueDate,
            debitAccountNum, creditAccountNum
        );

        IncomingTransaction txn = new IncomingTransaction(
            sourceSystem, sourceRef, rawPayload,
            txnType, amount, currency, valueDate, normalizedPayload
        );

        txn.setDebitAccountNumber(debitAccountNum);
        txn.setCreditAccountNumber(creditAccountNum);
        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("NEFT_UPI_ADAPTER");

        return txn;
    }

    @Override
    public SourceType getSourceType() {
        return SourceType.NEFT;
    }

    // -----------------------------------------------------------------------
    // Private helper
    // -----------------------------------------------------------------------

    private String buildNormalizedPayload(String sourceCode, String sourceRef,
                                           TransactionType txnType, BigDecimal amount,
                                           String currency, LocalDate valueDate,
                                           String debitAcc, String creditAcc) {
        return "{" +
               "\"source\":\"" + sourceCode + "\"," +
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