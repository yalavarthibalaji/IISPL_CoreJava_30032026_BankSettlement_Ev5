package com.iispl.banksettlement.enums;

/**
 * Represents the type of action recorded in an AuditLog entry.
 */
public enum AuditAction {
    CREATE,   // A new record was created
    READ,     // A record was accessed/read
    UPDATE,   // An existing record was modified
    DELETE,   // A record was deleted
    APPROVE,  // A transaction or batch was approved
    REJECT,   // A transaction or batch was rejected
    REVERSE,  // A transaction was reversed
    LOCK      // A record was locked (e.g., for compliance hold)
}
