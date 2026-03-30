package com.iispl.banksettlement.enums;

/**
 * Status of a settlement attempt for a batch or individual record.
 */
public enum SettlementStatus {
    PENDING,            // Settlement not yet started
    IN_PROGRESS,        // Settlement currently running
    SETTLED,            // Fully settled
    PARTIALLY_SETTLED,  // Some records settled, some failed
    FAILED,             // Entire settlement failed
    CANCELLED           // Settlement was cancelled before completion
}
