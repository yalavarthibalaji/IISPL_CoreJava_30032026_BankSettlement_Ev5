package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;

/**
 * DebitTransaction — money going out from an account.
 */
public class DebitTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    @Override
    public void process() {
        validate();
        setStatus(TransactionStatus.PENDING_SETTLEMENT);
    }

    @Override
    public String toString() {
        return "DebitTransaction{" +
                "txnId=" + getTxnId() +
                ", amount=" + getAmount() +
                ", currency='" + getCurrency() + '\'' +
                ", referenceNumber='" + getReferenceNumber() + '\'' +
                ", status=" + getStatus() +
                '}';
    }
}