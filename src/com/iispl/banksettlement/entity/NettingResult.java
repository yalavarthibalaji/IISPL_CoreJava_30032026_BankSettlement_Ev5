package com.iispl.banksettlement.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * NettingResult — Holds the final bilateral payment obligation between two banks.
 *
 * After the netting engine finishes, it produces a list of NettingResult objects.
 * Each NettingResult says:
 *   "fromBank has to PAY netAmount to toBank"
 *
 * This is what the NPCI or RBI clearing house uses to actually move money
 * between banks at the end of a settlement cycle.
 */
public class NettingResult {

    // The bank that has to PAY (net debtor)
    private String fromBank;

    // The bank that has to RECEIVE the payment (net creditor)
    private String toBank;

    // The net amount that fromBank must pay to toBank
    // This is: total credits toBank received from fromBank  -  total debits toBank sent to fromBank
    private BigDecimal netAmount;

    // The date for which this netting applies
    private LocalDate settlementDate;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public NettingResult() {
    }

    public NettingResult(String fromBank, String toBank, BigDecimal netAmount, LocalDate settlementDate) {
        this.fromBank = fromBank;
        this.toBank = toBank;
        this.netAmount = netAmount;
        this.settlementDate = settlementDate;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getFromBank() {
        return fromBank;
    }

    public void setFromBank(String fromBank) {
        this.fromBank = fromBank;
    }

    public String getToBank() {
        return toBank;
    }

    public void setToBank(String toBank) {
        this.toBank = toBank;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = netAmount;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public void setSettlementDate(LocalDate settlementDate) {
        this.settlementDate = settlementDate;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "NettingResult{"
                + "fromBank='" + fromBank + '\''
                + ", toBank='" + toBank + '\''
                + ", netAmount=" + netAmount
                + ", settlementDate=" + settlementDate
                + '}';
    }
}
