package com.iispl.banksettlement.enums;

/**
 * Indicates the net direction of a NettingPosition between two banks.
 */
public enum NetDirection {
    NET_DEBIT,  // We owe more to the counterparty (net payable)
    NET_CREDIT, // Counterparty owes more to us (net receivable)
    FLAT        // Perfectly offsetting — no net obligation either way
}
