package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;

/**
 * InterBankTransaction — transaction involving another bank
 * such as RTGS or SWIFT settlement flows.
 */
public class InterBankTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    private String correspondentBank;

    public String getCorrespondentBank() {
        return correspondentBank;
    }

    public void setCorrespondentBank(String correspondentBank) {
        this.correspondentBank = correspondentBank;
    }

    @Override
    public void process() {
        validate();
        setStatus(TransactionStatus.PENDING_SETTLEMENT);
    }

    @Override
    public String toString() {
        return "InterBankTransaction{" +
                "txnId=" + getTxnId() +
                ", correspondentBank='" + correspondentBank + '\'' +
                ", amount=" + getAmount() +
                ", currency='" + getCurrency() + '\'' +
                ", referenceNumber='" + getReferenceNumber() + '\'' +
                ", status=" + getStatus() +
                '}';
    }
}