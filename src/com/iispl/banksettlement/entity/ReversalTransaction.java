package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ReversalTransaction — Represents an undo/reversal of a previously settled transaction.
 *
 * WHY REVERSALS EXIST:
 *   Sometimes a transaction is settled but then found to be wrong —
 *   wrong amount, wrong account, duplicate entry, fraud etc.
 *   Instead of modifying the original transaction (bad practice in banking),
 *   a NEW reversal transaction is created that undoes the original.
 *   This keeps a complete audit trail.
 *
 * Examples:
 *   - A duplicate NEFT payment is reversed
 *   - A fraudulent SWIFT wire is reversed
 *   - A wrongly credited account is corrected via reversal
 *
 * IS-A Transaction (extends Transaction)
 * IS-A BaseEntity  (inherits through Transaction)
 *
 * Extra field in this subclass:
 *   - originalTxnRef : The reference number of the ORIGINAL transaction
 *                      that this reversal is undoing.
 *                      This links the reversal back to what it is reversing.
 */
public class ReversalTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    // Reference number of the original transaction being reversed.
    // Example: if original transaction had referenceNumber "NEFT-2024-001",
    // then this field stores "NEFT-2024-001" to link them together.
    private String originalTxnRef;

    // The reason why this reversal was initiated
    // Example: "DUPLICATE_PAYMENT", "WRONG_ACCOUNT", "FRAUD_DETECTED"
    private String reversalReason;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor.
     * txnType is automatically set to REVERSAL.
     */
    public ReversalTransaction() {
        super();
        // A ReversalTransaction is always of type REVERSAL
        setTxnType(TransactionType.REVERSAL);
    }

    /**
     * Parameterized constructor — use this to create a fully populated
     * ReversalTransaction in one shot.
     *
     * @param debitAccountId   ID of the account being debited (mirror of original credit)
     * @param creditAccountId  ID of the account being credited (mirror of original debit)
     * @param amount           Amount to be reversed — same as original transaction
     * @param currency         ISO currency code
     * @param valueDate        Date on which the reversal should be applied
     * @param referenceNumber  New reference number for this reversal transaction
     * @param originalTxnRef   Reference number of the ORIGINAL transaction being reversed
     * @param reversalReason   Why is this being reversed?
     */
    public ReversalTransaction(Long debitAccountId, Long creditAccountId,
                               BigDecimal amount, String currency,
                               LocalDate valueDate, String referenceNumber,
                               String originalTxnRef, String reversalReason) {
        super(debitAccountId, creditAccountId, amount, currency,
              valueDate, referenceNumber, TransactionType.REVERSAL);
        this.originalTxnRef  = originalTxnRef;
        this.reversalReason  = reversalReason;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getOriginalTxnRef() {
        return originalTxnRef;
    }

    public void setOriginalTxnRef(String originalTxnRef) {
        this.originalTxnRef = originalTxnRef;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public void setReversalReason(String reversalReason) {
        this.reversalReason = reversalReason;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "ReversalTransaction{" +
               "txnId=" + getTxnId() +
               ", originalTxnRef='" + originalTxnRef + '\'' +
               ", reversalReason='" + reversalReason + '\'' +
               ", debitAccountId=" + getDebitAccountId() +
               ", creditAccountId=" + getCreditAccountId() +
               ", amount=" + getAmount() +
               ", currency='" + getCurrency() + '\'' +
               ", valueDate=" + getValueDate() +
               ", status=" + getStatus() +
               ", referenceNumber='" + getReferenceNumber() + '\'' +
               '}';
    }
}
