package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * InterBankTransaction — Represents a transfer between two different banks.
 *
 * Examples:
 *   - An RTGS transfer from our bank to another bank
 *   - A SWIFT cross-border wire transfer
 *   - An interbank settlement instruction through RBI
 *
 * IS-A Transaction (extends Transaction)
 * IS-A BaseEntity  (inherits through Transaction)
 *
 * Extra field in this subclass:
 *   - correspondentBankCode : The IFSC or BIC/SWIFT code of the other bank
 *                             involved in this interbank transaction.
 *                             "correspondent" means the partner/counterparty bank.
 *
 * FLOW:
 *   Incoming RTGS/SWIFT message → adapt → InterBankTransaction
 *   → NettingEngine calculates bilateral positions
 *   → SettlementEngine settles via RTGS/SWIFT rail
 */
public class InterBankTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    // IFSC code (domestic) or BIC/SWIFT code (international) of the
    // partner bank. This identifies WHICH other bank is involved.
    // Example: "HDFC0001234" (IFSC) or "HDFCINBBXXX" (BIC/SWIFT)
    private String correspondentBankCode;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor.
     * txnType is automatically set to INTRABANK.
     * (INTRABANK in enums covers interbank transfers — between banks)
     */
    public InterBankTransaction() {
        super();
        // InterBankTransaction uses INTRABANK type from the enum
        setTxnType(TransactionType.INTRABANK);
    }

    /**
     * Parameterized constructor — use this to create a fully populated
     * InterBankTransaction in one shot.
     *
     * @param debitAccountId       ID of the account being debited at our bank
     * @param creditAccountId      ID of the account being credited (could be at other bank)
     * @param correspondentBankCode IFSC or BIC/SWIFT code of the partner bank
     * @param amount               Transfer amount (BigDecimal — no float!)
     * @param currency             ISO currency code e.g. "INR", "USD"
     * @param valueDate            Date on which interbank settlement should happen
     * @param referenceNumber      External reference (e.g. RTGS UTR number)
     */
    public InterBankTransaction(Long debitAccountId, Long creditAccountId,
                                String correspondentBankCode,
                                BigDecimal amount, String currency,
                                LocalDate valueDate, String referenceNumber) {
        super(debitAccountId, creditAccountId, amount, currency,
              valueDate, referenceNumber, TransactionType.INTRABANK);
        this.correspondentBankCode = correspondentBankCode;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getCorrespondentBankCode() {
        return correspondentBankCode;
    }

    public void setCorrespondentBankCode(String correspondentBankCode) {
        this.correspondentBankCode = correspondentBankCode;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "InterBankTransaction{" +
               "txnId=" + getTxnId() +
               ", correspondentBankCode='" + correspondentBankCode + '\'' +
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
