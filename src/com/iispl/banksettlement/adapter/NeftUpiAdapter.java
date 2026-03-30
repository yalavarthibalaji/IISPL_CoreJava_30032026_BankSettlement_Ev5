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
 * NEFT (National Electronic Funds Transfer):
 *   - Batch settlement system operated by RBI
 *   - Available 24x7 (since December 2019)
 *   - No minimum/maximum amount limit
 *   - Files dropped in SFTP location in fixed-width format
 *
 * UPI (Unified Payments Interface):
 *   - Instant real-time payment system by NPCI
 *   - Used for P2P, P2M transactions (PhonePe, GPay, Paytm etc.)
 *   - Sends data via REST API in CSV format
 *
 * EXPECTED RAW PAYLOAD FORMAT (CSV — same for both NEFT and UPI):
 *   "NEFT,NEFT-REF-20240615-001,CREDIT,25000.00,INR,2024-06-15"
 *   "UPI,UPI-REF-20240615-999,DEBIT,500.00,INR,2024-06-15"
 *
 *   Position: [0]=source [1]=ref [2]=txnType [3]=amount [4]=currency [5]=valueDate
 *
 * NOTE: One adapter handles both because NEFT and UPI share the same
 * CSV format in this system. The source field in the payload tells us which one.
 *
 * Implements: TransactionAdapter (Strategy Pattern)
 */
public class NeftUpiAdapter implements TransactionAdapter {

    private SourceSystem neftSourceSystem;
    private SourceSystem upiSourceSystem;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public NeftUpiAdapter() {
        // NEFT uses SFTP file drop
        this.neftSourceSystem = new SourceSystem(
            "NEFT",
            ProtocolType.SFTP,
            "{\"sftpHost\":\"sftp.npci.org.in\",\"directory\":\"/neft/inbound\"}",
            true,
            "neft-ops@npci.org.in"
        );
        this.neftSourceSystem.setSourceSystemId(4L);
        this.neftSourceSystem.setCreatedBy("SYSTEM");

        // UPI uses REST API
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

    /**
     * Parses a NEFT/UPI CSV payload and returns a canonical IncomingTransaction.
     *
     * Example inputs:
     *   "NEFT,NEFT-REF-20240615-001,CREDIT,25000.00,INR,2024-06-15"
     *   "UPI,UPI-REF-20240615-999,DEBIT,500.00,INR,2024-06-15"
     */
    @Override
    public IncomingTransaction adapt(String rawPayload) {

        if (rawPayload == null || rawPayload.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "NeftUpiAdapter: rawPayload cannot be null or empty"
            );
        }

        // Split the CSV row
        String[] parts = rawPayload.split(",");

        if (parts.length < 6) {
            throw new IllegalArgumentException(
                "NeftUpiAdapter: Invalid CSV format. Expected 6 comma-separated fields. Got: " + rawPayload
            );
        }

        String sourceCode  = parts[0].trim().toUpperCase(); // "NEFT" or "UPI"
        String sourceRef   = parts[1].trim();
        String txnTypeStr  = parts[2].trim();
        BigDecimal amount  = new BigDecimal(parts[3].trim());
        String currency    = parts[4].trim();
        LocalDate valueDate = LocalDate.parse(parts[5].trim());

        TransactionType txnType = TransactionType.valueOf(txnTypeStr.toUpperCase());

        // Choose the appropriate SourceSystem based on payload content
        SourceSystem sourceSystem;
        if ("UPI".equals(sourceCode)) {
            sourceSystem = upiSourceSystem;
        } else {
            sourceSystem = neftSourceSystem; // default to NEFT
        }

        String normalizedPayload = buildNormalizedPayload(
            sourceCode, sourceRef, txnType, amount, currency, valueDate
        );

        IncomingTransaction txn = new IncomingTransaction(
            sourceSystem,
            sourceRef,
            rawPayload,
            txnType,
            amount,
            currency,
            valueDate,
            normalizedPayload
        );

        txn.setProcessingStatus(ProcessingStatus.VALIDATED);
        txn.setCreatedBy("NEFT_UPI_ADAPTER");

        return txn;
    }

    /**
     * This adapter is registered for NEFT.
     * (AdapterRegistry will use this for both NEFT and UPI — see AdapterRegistry).
     */
    @Override
    public SourceType getSourceType() {
        return SourceType.NEFT;
    }

    // -----------------------------------------------------------------------
    // Private helper
    // -----------------------------------------------------------------------

    private String buildNormalizedPayload(String sourceCode, String sourceRef,
                                           TransactionType txnType, BigDecimal amount,
                                           String currency, LocalDate valueDate) {
        return "{" +
               "\"source\":\"" + sourceCode + "\"," +
               "\"sourceRef\":\"" + sourceRef + "\"," +
               "\"txnType\":\"" + txnType.name() + "\"," +
               "\"amount\":" + amount + "," +
               "\"currency\":\"" + currency + "\"," +
               "\"valueDate\":\"" + valueDate + "\"" +
               "}";
    }
}
