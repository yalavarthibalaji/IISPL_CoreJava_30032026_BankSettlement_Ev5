package com.iispl.entity;

import java.math.BigDecimal;

/**
 * NpciMemberAccount — Represents a bank's settlement account held by NPCI.
 *
 * This is DIFFERENT from the customer Account table.
 *
 * WHAT IS THIS?
 *   NPCI (National Payments Corporation of India) is the central clearing house.
 *   Every member bank has ONE settlement account at NPCI.
 *   After netting is done, NPCI debits and credits these accounts.
 *
 * Example:
 *   HDFC Bank NPCI account → opening balance: Rs. 1,00,00,000
 *   After netting (HDFC pays SBI Rs. 3,00,000):
 *     HDFC NPCI balance → Rs. 97,00,000
 *     SBI  NPCI balance → Rs. 1,03,00,000
 *
 * RELATIONSHIPS:
 *   NpciMemberAccount HAS-A Bank (via bankId FK)
 *   Npci entity HAS-A List<NpciMemberAccount>
 *
 * TABLE: npci_bank_account
 */
public class NpciMemberAccount {

    // Primary key
    private Long npciAccountId;

    // FK to bank table — which bank owns this NPCI account
    private Long bankId;

    // The bank name (loaded via JOIN — not stored as FK in code)
    private String bankName;

    // Balance BEFORE the current netting cycle started.
    // This is saved at the start of each netting run so reconciliation
    // can compare what was expected vs what actually happened.
    private BigDecimal openingBalance;

    // Current balance AFTER netting positions are applied.
    private BigDecimal currentBalance;

    // Always "INR" for NPCI domestic settlement accounts
    private String currency;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public NpciMemberAccount() {
        this.currency       = "INR";
        this.openingBalance = BigDecimal.ZERO;
        this.currentBalance = BigDecimal.ZERO;
    }

    public NpciMemberAccount(Long bankId, String bankName, BigDecimal openingBalance) {
        this.bankId         = bankId;
        this.bankName       = bankName;
        this.openingBalance = openingBalance;
        this.currentBalance = openingBalance;
        this.currency       = "INR";
    }

    // -----------------------------------------------------------------------
    // Helper methods for balance update
    // -----------------------------------------------------------------------

    /**
     * Adds amount to current balance.
     * Called when this bank is a net creditor (receives money from another bank).
     */
    public void credit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.add(amount);
    }

    /**
     * Subtracts amount from current balance.
     * Called when this bank is a net debtor (pays money to another bank).
     */
    public void debit(BigDecimal amount) {
        this.currentBalance = this.currentBalance.subtract(amount);
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getNpciAccountId() { return npciAccountId; }
    public void setNpciAccountId(Long npciAccountId) { this.npciAccountId = npciAccountId; }

    public Long getBankId() { return bankId; }
    public void setBankId(Long bankId) { this.bankId = bankId; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }

    public BigDecimal getCurrentBalance() { return currentBalance; }
    public void setCurrentBalance(BigDecimal currentBalance) { this.currentBalance = currentBalance; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "NpciMemberAccount{npciAccountId=" + npciAccountId
                + ", bankName='" + bankName + '\''
                + ", openingBalance=" + openingBalance
                + ", currentBalance=" + currentBalance
                + ", currency='" + currency + '\'' + '}';
    }
}