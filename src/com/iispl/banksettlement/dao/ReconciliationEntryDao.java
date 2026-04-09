package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.ReconciliationEntry;
import java.util.List;

/**
 * ReconciliationEntryDao — Interface for reconciliation_entry table operations.
 */
public interface ReconciliationEntryDao {
    void save(ReconciliationEntry entry);
    List<ReconciliationEntry> findAll();
}