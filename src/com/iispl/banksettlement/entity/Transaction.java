package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Transaction — Abstract parent class for ALL transaction subtypes.
 *
 * WHY ABSTRACT?
 * You will never create a plain "Transaction" in real banking. Every real
 * transaction is always one of:
 *   - CreditTransaction   (money coming IN)
 *   - DebitTransaction    (money going OUT)
 *   - InterBankTransaction (transfer between two banks)
 *   - ReversalTransaction  (undo of a previous transaction)
 *
 * CHANGES in this version:
 *   - fromBank      : name of the bank that owns the DEBIT account (sender)
 *   - toBank        : name of the bank that owns the CREDIT account (receiver)
 *   - incomingTxnId : FK linking this transaction back to the original
 *                     IncomingTransaction row that was ingested
 *
 * These three fields are set by TransactionDispatcher when it creates each
 * subclass object from an IncomingTransaction.
 * They are also stored in separate columns in the DB table of each subclass.
 */
public abstract class Transaction extends BaseEntity {

    private static final long serialVersionUID = 1L;

    // Unique identifier for this transaction (PK in the subclass table)
    private Long txnId;

    // The account from which money is debited (source account DB id)
    private Long debitAccountId;

    // The account to which money is credited (destination account DB id)
    private Long creditAccountId;

    // Transaction amount — ALWAYS use BigDecimal for money
    private BigDecimal amount;

    // ISO 4217 currency code e.g. "INR", "USD", "EUR"
    private String currency;

    // Exact date and time this transaction was created in the system
    private LocalDateTime txnDate;

    // The date on which this transaction should actually be settled
    private LocalDate valueDate;

    // Current lifecycle status of this transaction
    private TransactionStatus status;

    // Business reference number — used for matching with external systems
    private String referenceNumber;

    // What kind of transaction is this?
    private TransactionType txnType;

    // -----------------------------------------------------------------------
    // NEW FIELDS — fromBank, toBank, incomingTxnId
    // -----------------------------------------------------------------------

    // Name of the bank that owns the DEBIT (sending) account.
    // Examples: "HDFC Bank", "SBI Bank", "ICICI Bank"
    // Source: parsed from IncomingTransaction.normalizedPayload by Dispatcher.
    private String fromBank;

    // Name of the bank that owns the CREDIT (receiving) account.
    // Examples: "SBI Bank", "Axis Bank", "Kotak Bank"
    // Source: parsed from IncomingTransaction.normalizedPayload by Dispatcher.
    private String toBank;

    // FK back to incoming_transaction.incoming_txn_id
    // This links this credit/debit/interbank/reversal row back to the
    // original IncomingTransaction that was ingested from the source system.
    private Long incomingTxnId;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public Transaction() {
        super();
        this.txnDate = LocalDateTime.now();
        this.status  = TransactionStatus.INITIATED;
    }

    public Transaction(Long debitAccountId, Long creditAccountId, BigDecimal amount,
                       String currency, LocalDate valueDate, String referenceNumber,
                       TransactionType txnType) {
        super();
        this.debitAccountId  = debitAccountId;
        this.creditAccountId = creditAccountId;
        this.amount          = amount;
        this.currency        = currency;
        this.valueDate       = valueDate;
        this.referenceNumber = referenceNumber;
        this.txnType         = txnType;
        this.txnDate         = LocalDateTime.now();
        this.status          = TransactionStatus.INITIATED;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getTxnId() { return txnId; }
    public void setTxnId(Long txnId) { this.txnId = txnId; }

    public Long getDebitAccountId() { return debitAccountId; }
    public void setDebitAccountId(Long debitAccountId) { this.debitAccountId = debitAccountId; }

    public Long getCreditAccountId() { return creditAccountId; }
    public void setCreditAccountId(Long creditAccountId) { this.creditAccountId = creditAccountId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public LocalDateTime getTxnDate() { return txnDate; }
    public void setTxnDate(LocalDateTime txnDate) { this.txnDate = txnDate; }

    public LocalDate getValueDate() { return valueDate; }
    public void setValueDate(LocalDate valueDate) { this.valueDate = valueDate; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public TransactionType getTxnType() { return txnType; }
    public void setTxnType(TransactionType txnType) { this.txnType = txnType; }

    public String getFromBank() { return fromBank; }
    public void setFromBank(String fromBank) { this.fromBank = fromBank; }

    public String getToBank() { return toBank; }
    public void setToBank(String toBank) { this.toBank = toBank; }

    public Long getIncomingTxnId() { return incomingTxnId; }
    public void setIncomingTxnId(Long incomingTxnId) { this.incomingTxnId = incomingTxnId; }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "Transaction{"
                + "txnId=" + txnId
                + ", debitAccountId=" + debitAccountId
                + ", creditAccountId=" + creditAccountId
                + ", amount=" + amount
                + ", currency='" + currency + '\''
                + ", txnDate=" + txnDate
                + ", valueDate=" + valueDate
                + ", status=" + status
                + ", referenceNumber='" + referenceNumber + '\''
                + ", txnType=" + txnType
                + ", fromBank='" + fromBank + '\''
                + ", toBank='" + toBank + '\''
                + ", incomingTxnId=" + incomingTxnId
                + '}';
    }
}