package com.iispl.banksettlement.factory;

import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.banksettlement.entity.Transaction;
import com.iispl.banksettlement.enums.SourceType;
import com.iispl.banksettlement.enums.TransactionType;

/**
 * TransactionFactory — Converts IncomingTransaction (canonical source input)
 * into a business-ready Transaction subclass.
 *
 * WHY THIS EXISTS:
 * Adapters only normalize external payloads into IncomingTransaction.
 * But the settlement engine should work with domain objects like:
 *   - CreditTransaction
 *   - DebitTransaction
 *   - InterBankTransaction
 *   - ReversalTransaction
 *
 * This class is the bridge between T1 and T2.
 */
public class TransactionFactory {

    /**
     * Converts IncomingTransaction into the correct Transaction subclass.
     *
     * @param incomingTxn IncomingTransaction from adapter layer
     * @return Concrete Transaction subclass
     */
    public static Transaction fromIncomingTransaction(IncomingTransaction incomingTxn) {

        if (incomingTxn == null) {
            throw new IllegalArgumentException("TransactionFactory: IncomingTransaction cannot be null");
        }

        if (incomingTxn.getTxnType() == null) {
            throw new IllegalArgumentException("TransactionFactory: Transaction type cannot be null");
        }

        if (incomingTxn.getSourceSystem() == null || incomingTxn.getSourceSystem().getSystemCode() == null) {
            throw new IllegalArgumentException("TransactionFactory: Source system cannot be null");
        }

        Transaction txn;
        TransactionType txnType = incomingTxn.getTxnType();

        // Convert systemCode (e.g. "CBS", "RTGS", "SWIFT") → SourceType enum
        SourceType sourceType = SourceType.valueOf(
                incomingTxn.getSourceSystem().getSystemCode().toUpperCase()
        );

        // -------------------------------------------------------------------
        // Decide which subclass to create
        // -------------------------------------------------------------------

        if (txnType == TransactionType.CREDIT) {

            // RTGS and SWIFT are typically interbank settlement flows
            if (sourceType == SourceType.RTGS || sourceType == SourceType.SWIFT) {
                txn = new InterBankTransaction();
            } else {
                txn = new CreditTransaction();
            }

        } else if (txnType == TransactionType.DEBIT) {

            txn = new DebitTransaction();

        } else if (txnType == TransactionType.REVERSAL) {

            txn = new ReversalTransaction();

        } else {

            throw new IllegalArgumentException(
                    "TransactionFactory: Unsupported transaction type: " + txnType
            );
        }

        // -------------------------------------------------------------------
        // Copy common fields from IncomingTransaction → Transaction
        // -------------------------------------------------------------------

        txn.setReferenceNumber(incomingTxn.getSourceRef());
        txn.setAmount(incomingTxn.getAmount());
        txn.setCurrency(incomingTxn.getCurrency());
        txn.setValueDate(incomingTxn.getValueDate());
        txn.setTransactionType(incomingTxn.getTxnType());
        txn.setCreatedBy("TRANSACTION_FACTORY");

        return txn;
    }
}