package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * IncomingTransaction — The CANONICAL (standardised) form of every transaction,
 * regardless of which source system it came from.
 *
 * WHY THIS EXISTS:
 *   CBS sends pipe-delimited flat files, RTGS sends XML over MQ,
 *   NEFT sends fixed-width batch files, UPI sends JSON webhooks,
 *   Fintech sends proprietary JSON — each format is completely different.
 *   The adapter classes convert each raw format into this single standard class.
 *   After adaptation, all downstream processing works only with IncomingTransaction.
 *
 * FLOW:
 *   Raw Payload → Adapter → IncomingTransaction → BlockingQueue → Settlement Engine
 *
 * HAS-A relationship: contains a SourceSystem object to know its origin.
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 *
 * DESIGN DECISIONS (v2):
 *
 *   1. debitAccountNumber / creditAccountNumber REMOVED:
 *      Account numbers are embedded inside normalizedPayload JSON as
 *      "debitAccount" and "creditAccount" keys. The settlement engine
 *      will parse them from there when building Transaction sub-objects.
 *      Keeping them only in normalizedPayload avoids duplication and
 *      makes IncomingTransaction a clean transport object.
 *
 *   2. failureReason REMOVED:
 *      Validation has moved to the settlement phase. The ingestion phase
 *      no longer fails transactions — it only receives, adapts, and queues.
 *      Any failure reason will be stored in SettlementRecord.failureReason
 *      at the settlement layer.
 *
 *   3. requiresAccountValidation REMOVED:
 *      No validation happens during ingestion now (see point 2).
 *      UPI VPA handling is documented in UpiAdapter and will be handled
 *      by the settlement engine when it reads the normalizedPayload.
 *
 *   4. currency REMOVED:
 *      All transactions in this system are in INR (Indian Rupee).
 *      Currency is not stored as a separate field. If any adapter
 *      historically set a currency field, that information may still
 *      appear inside the normalizedPayload JSON for audit purposes.
 *
 * NOTE ON SWIFT:
 *   SWIFT (cross-border) has been removed as a source system. This system
 *   now handles Indian domestic bank accounts only.
 */
public class IncomingTransaction extends BaseEntity {

    // Unique identifier for this incoming transaction record
    private Long incomingTxnId;

    // HAS-A: which source system sent this transaction
    // (CBS, RTGS, NEFT, UPI, FINTECH)
    private SourceSystem sourceSystem;

    // The original reference number from the source system
    // CBS  → CBS_TXN_ID,  RTGS → MsgId
    // NEFT → NEFT_REF,    UPI  → upiTxnId,  Fintech → ft_ref
    private String sourceRef;

    // The raw original payload as received — stored for audit/replay purposes
    // CBS=pipe-delimited, RTGS=XML, NEFT=fixed-width, UPI=JSON, Fintech=JSON
    private String rawPayload;

    // What kind of transaction is this? (CREDIT, DEBIT, REVERSAL etc.)
    private TransactionType txnType;

    // Transaction amount — using BigDecimal because financial amounts
    // must NEVER use double or float (precision loss causes money errors!)
    private BigDecimal amount;

    // The date on which this transaction should be settled/valued
    // All adapters normalise their date formats to LocalDate (dd-MM-yyyy internally)
    private LocalDate valueDate;

    // Current processing stage of this transaction in the pipeline
    private ProcessingStatus processingStatus;

    // Exact timestamp when this transaction was received by our system
    private LocalDateTime ingestTimestamp;

    // The cleaned/standardised payload after adapter processing.
    // Always JSON format regardless of original source format.
    //
    // IMPORTANT: account numbers are stored HERE, not as separate fields.
    // Keys in this JSON:
    //   "debitAccount"  — the sending/debiting account number (or VPA for UPI)
    //   "creditAccount" — the receiving/crediting account number (or VPA for UPI)
    //   "source"        — source system name
    //   "sourceRef"     — original reference
    //   "txnType"       — transaction type
    //   "amount"        — amount as number
    //   "valueDate"     — settlement date
    //   + all source-specific extra fields (NARRATION, RBIRefNo, IFSC codes etc.)
    //
    // The settlement engine reads debitAccount and creditAccount from here
    // to look up Account records and build CreditTransaction / DebitTransaction.
    private String normalizedPayload;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor.
     * Sets processingStatus to RECEIVED and ingestTimestamp to now.
     */
    public IncomingTransaction() {
        super();
        this.processingStatus = ProcessingStatus.RECEIVED;
        this.ingestTimestamp  = LocalDateTime.now();
    }

    /**
     * Parameterized constructor — used by adapter classes to build
     * a fully populated IncomingTransaction from a raw payload.
     *
     * NOTE: currency parameter has been removed. All transactions are INR.
     *
     * @param sourceSystem      The source system that sent this transaction
     * @param sourceRef         Original reference from the source system
     * @param rawPayload        The raw payload as received
     * @param txnType           Type of transaction (CREDIT/DEBIT etc.)
     * @param amount            Transaction amount (BigDecimal — no float/double!)
     * @param valueDate         Settlement value date
     * @param normalizedPayload Cleaned JSON payload after normalisation
     *                          (must contain "debitAccount" and "creditAccount" keys)
     */
    public IncomingTransaction(SourceSystem sourceSystem, String sourceRef,
                               String rawPayload, TransactionType txnType,
                               BigDecimal amount, LocalDate valueDate,
                               String normalizedPayload) {
        super();
        this.sourceSystem     = sourceSystem;
        this.sourceRef        = sourceRef;
        this.rawPayload       = rawPayload;
        this.txnType          = txnType;
        this.amount           = amount;
        this.valueDate        = valueDate;
        this.normalizedPayload = normalizedPayload;
        this.processingStatus = ProcessingStatus.RECEIVED;
        this.ingestTimestamp  = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getIncomingTxnId() {
        return incomingTxnId;
    }

    public void setIncomingTxnId(Long incomingTxnId) {
        this.incomingTxnId = incomingTxnId;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(SourceSystem sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public TransactionType getTxnType() {
        return txnType;
    }

    public void setTxnType(TransactionType txnType) {
        this.txnType = txnType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    public ProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(ProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public LocalDateTime getIngestTimestamp() {
        return ingestTimestamp;
    }

    public void setIngestTimestamp(LocalDateTime ingestTimestamp) {
        this.ingestTimestamp = ingestTimestamp;
    }

    public String getNormalizedPayload() {
        return normalizedPayload;
    }

    public void setNormalizedPayload(String normalizedPayload) {
        this.normalizedPayload = normalizedPayload;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "IncomingTransaction{" +
               "incomingTxnId=" + incomingTxnId +
               ", sourceSystem=" + (sourceSystem != null ? sourceSystem.getSystemCode() : "null") +
               ", sourceRef='" + sourceRef + '\'' +
               ", txnType=" + txnType +
               ", amount=" + amount +
               ", valueDate=" + valueDate +
               ", processingStatus=" + processingStatus +
               ", ingestTimestamp=" + ingestTimestamp +
               '}';
    }
}
