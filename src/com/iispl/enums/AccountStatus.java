package com.iispl.enums;

/**
 * Operational status of a bank Account.
 */
public enum AccountStatus {
	ACTIVE, // Account is open and fully operational
	INACTIVE, // Account exists but is not currently in use
	FROZEN, // Account is frozen (no debits/credits allowed)
	CLOSED, // Account has been permanently closed
	DORMANT // Account has had no activity for an extended period
}
