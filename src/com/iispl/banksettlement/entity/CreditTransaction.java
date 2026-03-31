package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;

/**
 * CreditTransaction — money coming into an account.
 */
public class CreditTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    @Override
    public void process() {
        validate();
        setStatus(TransactionStatus.PENDING_SETTLEMENT);
    }

    @Override
    public String toString() {
        return "CreditTransaction{" +
                "txnId=" + getTxnId() +
                ", amount=" + getAmount() +
                ", currency='" + getCurrency() + '\'' +
                ", referenceNumber='" + getReferenceNumber() + '\'' +
                ", status=" + getStatus() +
                '}';
    }
}