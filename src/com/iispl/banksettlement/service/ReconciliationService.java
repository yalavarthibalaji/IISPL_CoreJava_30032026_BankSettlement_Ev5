package com.iispl.banksettlement.service;

import com.iispl.banksettlement.entity.ReconciliationEntry;
import java.util.List;

/**
 * ReconciliationService — Interface for the post-netting reconciliation.
 *
 * After netting is done and NPCI balances are updated, reconciliation checks:
 *   For each bank's NPCI account:
 *     expectedAmount = opening_balance ± sum of all net positions for that bank
 *     actualAmount   = current_balance in npci_bank_account table
 *     variance       = actualAmount - expectedAmount
 *
 *   If variance == 0 → MATCHED  (everything balanced correctly)
 *   If variance != 0 → UNMATCHED (something went wrong)
 *
 * Each check produces one ReconciliationEntry row saved to the DB.
 */
public interface ReconciliationService {

    /**
     * Runs reconciliation for all NPCI member accounts.
     * Returns the list of ReconciliationEntry records created.
     */
    List<ReconciliationEntry> runReconciliation();
}