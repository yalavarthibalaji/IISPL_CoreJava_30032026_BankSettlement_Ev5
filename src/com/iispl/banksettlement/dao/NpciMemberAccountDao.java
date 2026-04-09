package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.NpciMemberAccount;
import java.util.List;

/**
 * NpciMemberAccountDao — Interface for npci_bank_account table operations.
 */
public interface NpciMemberAccountDao {

    /** Returns all NPCI member accounts (one per bank). */
    List<NpciMemberAccount> findAll();

    /** Finds the NPCI account for a specific bank. */
    NpciMemberAccount findByBankId(Long bankId);

    /**
     * Updates opening_balance and current_balance for a given npci_account_id.
     * Called before netting: saves opening balance.
     * Called after netting: saves updated current balance.
     */
    void updateBalances(NpciMemberAccount account);
}
