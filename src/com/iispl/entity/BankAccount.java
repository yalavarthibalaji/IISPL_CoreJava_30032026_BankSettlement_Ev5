package com.iispl.entity;

import java.math.BigDecimal;

/**
 * BankAccount — Represents a bank's settlement account held by NPCI.
 *
 * NPCI (National Payments Corporation of India) maintains a settlement account
 * for each member bank. After netting is done, NPCI debits/credits these
 * accounts to reflect the final net obligations.
 *
 * For example:
 *   HDFC Bank's NPCI settlement account balance: Rs. 50,00,000
 *   After settling: Rs. 50,00,000 - Rs. 2,00,000 (net payment to SBI) = Rs. 48,00,000
 *
 * This class is different from Account (which is a customer account in a bank).
 * BankAccount is the bank's own clearing account at NPCI.
 */
public class BankAccount {

    // The name of the bank that owns this settlement account
    // Examples: "HDFC Bank", "SBI Bank", "ICICI Bank", "Axis Bank", "Kotak Bank"
    private String bankName;

    // Current balance in this bank's NPCI settlement account
    // This is updated after each netting cycle
    private BigDecimal balance;

    // ISO 4217 currency code — always "INR" for NPCI settlement accounts
    private String currency;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public BankAccount() {
        this.currency = "INR";
        this.balance = BigDecimal.ZERO;
    }

    public BankAccount(String bankName, BigDecimal balance) {
        this.bankName = bankName;
        this.balance = balance;
        this.currency = "INR";
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Adds the given amount to this bank's balance (credit).
     * Called when this bank is the NET CREDITOR (receives money).
     */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /**
     * Subtracts the given amount from this bank's balance (debit).
     * Called when this bank is the NET DEBTOR (pays money).
     */
    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "BankAccount{"
                + "bankName='" + bankName + '\''
                + ", balance=" + balance
                + ", currency='" + currency + '\''
                + '}';
    }
}
