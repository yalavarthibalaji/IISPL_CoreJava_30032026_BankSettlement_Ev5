package com.iispl.banksettlement.entity;

import com.iispl.banksettlement.enums.TransactionStatus;

/**
 * ReversalTransaction — rollback / reversal of an earlier transaction.
 */
public class ReversalTransaction extends Transaction {

    private static final long serialVersionUID = 1L;

    private String originalReference;

    public String getOriginalReference() {
        return originalReference;
    }

    public void setOriginalReference(String originalReference) {
        this.originalReference = originalReference;
    }

    @Override
    public void process() {
        validate();
        setStatus(TransactionStatus.REVERSED);
    }

    @Override
    public String toString() {
        return "ReversalTransaction{" +
                "txnId=" + getTxnId() +
                ", originalReference='" + originalReference + '\'' +
                ", amount=" + getAmount() +
                ", currency='" + getCurrency() + '\'' +
                ", referenceNumber='" + getReferenceNumber() + '\'' +
                ", status=" + getStatus() +
                '}';
    }
}