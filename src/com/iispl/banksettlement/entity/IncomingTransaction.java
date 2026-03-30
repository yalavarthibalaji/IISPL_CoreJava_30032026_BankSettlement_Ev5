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
 *   CBS sends XML, SWIFT sends MT103 messages, NEFT sends flat files —
 *   each format is different. The adapter classes (CbsAdapter, SwiftAdapter etc.)
 *   convert each raw format into this single standard class.
 *   After adaptation, all downstream processing works only with IncomingTransaction.
 *
 * FLOW:
 *   Raw Payload → Adapter → IncomingTransaction → BlockingQueue → Settlement Engine
 *
 * HAS-A relationship: contains a SourceSystem object to know its origin.
 * Extends BaseEntity: inherits id, createdAt, updatedAt, createdBy, version.
 */
public class IncomingTransaction extends BaseEntity {

    private static final long serialVersionUID = 1L;

    // Unique identifier for this incoming transaction record
    private Long incomingTxnId;

    // HAS-A: which source system sent this transaction
    // (CBS, RTGS, SWIFT, NEFT, UPI, FINTECH)
    private SourceSystem sourceSystem;

    // The original reference number from the source system
    // e.g. CBS sends "CBS-TXN-20240101-001" — we store it here for traceability
    private String sourceRef;

    // The raw original payload as received — stored for audit/replay purposes
    // Could be XML, JSON, flat-file row etc. depending on the source system
    private String rawPayload;

    // What kind of transaction is this? (CREDIT, DEBIT, REVERSAL etc.)
    private TransactionType txnType;

    // Transaction amount — using BigDecimal because financial amounts
    // must NEVER use double or float (precision loss causes money errors!)
    private BigDecimal amount;

    // ISO 4217 currency code e.g. "INR", "USD", "EUR"
    private String currency;

    // The date on which this transaction should be settled/valued
    private LocalDate valueDate;

    // Current processing stage of this transaction in the pipeline
    private ProcessingStatus processingStatus;

    // Exact timestamp when this transaction was received by our system
    private LocalDateTime ingestTimestamp;

    // The cleaned/standardised payload after adapter processing
    // (always JSON format regardless of original source format)
    private String normalizedPayload;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor.
     */
    public IncomingTransaction() {
        super();
        // New transactions always start with RECEIVED status
        this.processingStatus = ProcessingStatus.RECEIVED;
        this.ingestTimestamp  = LocalDateTime.now();
    }

    /**
     * Parameterized constructor — used by adapter classes to build
     * a fully populated IncomingTransaction from a raw payload.
     *
     * @param sourceSystem      The source system that sent this transaction
     * @param sourceRef         Original reference from the source system
     * @param rawPayload        The raw payload as received
     * @param txnType           Type of transaction (CREDIT/DEBIT etc.)
     * @param amount            Transaction amount (BigDecimal — no float/double!)
     * @param currency          ISO currency code
     * @param valueDate         Settlement value date
     * @param normalizedPayload Cleaned JSON payload after normalisation
     */
    public IncomingTransaction(SourceSystem sourceSystem, String sourceRef,
                               String rawPayload, TransactionType txnType,
                               BigDecimal amount, String currency,
                               LocalDate valueDate, String normalizedPayload) {
        super();
        this.sourceSystem      = sourceSystem;
        this.sourceRef         = sourceRef;
        this.rawPayload        = rawPayload;
        this.txnType           = txnType;
        this.amount            = amount;
        this.currency          = currency;
        this.valueDate         = valueDate;
        this.normalizedPayload = normalizedPayload;
        this.processingStatus  = ProcessingStatus.RECEIVED;
        this.ingestTimestamp   = LocalDateTime.now();
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

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
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
               ", currency='" + currency + '\'' +
               ", valueDate=" + valueDate +
               ", processingStatus=" + processingStatus +
               ", ingestTimestamp=" + ingestTimestamp +
               '}';
    }
}
