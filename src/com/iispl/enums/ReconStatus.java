package com.iispl.enums;

/**
 * Reconciliation status of a ReconciliationEntry (expected vs actual
 * comparison).
 */
public enum ReconStatus {
	MATCHED, // Expected and actual amounts match exactly
	UNMATCHED, // Expected and actual amounts do not match
	EXCEPTION, // Serious discrepancy requiring manual intervention
	PARTIALLY_MATCHED, // Partial match found (e.g., split payments)
	PENDING // Reconciliation not yet performed
}
