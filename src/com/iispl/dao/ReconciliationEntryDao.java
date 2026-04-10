package com.iispl.dao;

import java.util.List;

import com.iispl.entity.ReconciliationEntry;

/**
 * ReconciliationEntryDao — Interface for reconciliation_entry table operations.
 */
public interface ReconciliationEntryDao {
    void save(ReconciliationEntry entry);
    List<ReconciliationEntry> findAll();
}