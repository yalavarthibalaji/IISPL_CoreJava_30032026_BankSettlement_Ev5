package com.iispl.banksettlement.enums;

/**
 * Represents the settlement lifecycle status of a Transaction.
 */
public enum TransactionStatus {
    INITIATED,           // Transaction created but not yet validated
    VALIDATED,           // Passed all business validations
    PENDING_SETTLEMENT,  // Waiting to be picked up by settlement engine
    SETTLED,             // Successfully settled
    FAILED,              // Settlement failed
    REVERSED,            // Transaction has been reversed
    ON_HOLD              // Manually placed on hold (compliance/review)
}
