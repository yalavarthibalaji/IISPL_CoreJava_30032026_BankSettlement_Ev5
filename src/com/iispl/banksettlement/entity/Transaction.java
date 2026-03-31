package com.iispl.banksettlement.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;

/**
 * Transaction — Abstract parent class for all business-ready transactions.
 *
 * DIFFERENCE:
 * IncomingTransaction = source-normalized raw input
 * Transaction         = domain/business transaction used by settlement engine
 *
 * All concrete transaction types (Credit, Debit, InterBank, Reversal)
 * extend this class.
 */
public abstract class Transaction extends BaseEntity implements Processable, Validatable {

    private static final long serialVersionUID = 1L;

    // Business transaction ID
    private Long txnId;

    // Account references
    private Long debitAccountId;
    private Long creditAccountId;

    // Financial details
    private BigDecimal amount;
    private String currency;

    // Dates
    private LocalDateTime txnDate;
    private LocalDate valueDate;

    // Lifecycle status
    private TransactionStatus status;

    // External / business reference
    private String referenceNumber;

    // CREDIT / DEBIT / REVERSAL / etc.
    private TransactionType transactionType;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public Transaction() {
        super();
        this.txnDate = LocalDateTime.now();
        this.status = TransactionStatus.INITIATED;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getTxnId() {
        return txnId;
    }

    public void setTxnId(Long txnId) {
        this.txnId = txnId;
    }

    public Long getDebitAccountId() {
        return debitAccountId;
    }

    public void setDebitAccountId(Long debitAccountId) {
        this.debitAccountId = debitAccountId;
    }

    public Long getCreditAccountId() {
        return creditAccountId;
    }

    public void setCreditAccountId(Long creditAccountId) {
        this.creditAccountId = creditAccountId;
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

    public LocalDateTime getTxnDate() {
        return txnDate;
    }

    public void setTxnDate(LocalDateTime txnDate) {
        this.txnDate = txnDate;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public void setValueDate(LocalDate valueDate) {
        this.valueDate = valueDate;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    // -----------------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------------

    @Override
    public boolean isValid() {
        return amount != null
                && amount.compareTo(BigDecimal.ZERO) > 0
                && currency != null
                && !currency.trim().isEmpty()
                && valueDate != null
                && referenceNumber != null
                && !referenceNumber.trim().isEmpty()
                && transactionType != null;
    }

    @Override
    public String getValidationError() {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return "Invalid amount";
        }
        if (currency == null || currency.trim().isEmpty()) {
            return "Currency is required";
        }
        if (valueDate == null) {
            return "Value date is required";
        }
        if (referenceNumber == null || referenceNumber.trim().isEmpty()) {
            return "Reference number is required";
        }
        if (transactionType == null) {
            return "Transaction type is required";
        }
        return null;
    }

    @Override
    public void validate() {
        if (!isValid()) {
            throw new IllegalStateException(getValidationError());
        }
        this.status = TransactionStatus.VALIDATED;
    }

    // process() remains abstract — subclasses must implement it
}