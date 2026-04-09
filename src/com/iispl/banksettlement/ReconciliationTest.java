package com.iispl.banksettlement;

import com.iispl.banksettlement.entity.ReconciliationEntry;
import com.iispl.banksettlement.service.impl.ReconciliationServiceImpl;
import com.iispl.connectionpool.ConnectionPool;

import java.util.List;

/**
 * ReconciliationTest — Runs post-netting reconciliation on NPCI member accounts.
 *
 * RUN THIS AFTER:
 *   Step 1: FileIngestionTest       — ingests transaction files
 *   Step 2: SettlementProcessorTest — dispatches to credit/debit tables
 *   Step 3: SettlementEngineTest    — settles all transactions
 *   Step 4: NettingEngineTest       — computes netting positions
 *   Step 5: ReconciliationTest      ← YOU ARE HERE
 *
 * WHAT THIS DOES:
 *   For each bank's NPCI settlement account:
 *     expectedBalance = opening_balance ± all net positions for this bank
 *     actualBalance   = current_balance in npci_bank_account table
 *     variance        = actualBalance - expectedBalance
 *     MATCHED if variance == 0, UNMATCHED otherwise
 *   Saves one ReconciliationEntry per bank to reconciliation_entry table.
 *   Prints a full reconciliation report.
 *
 * PRE-REQUISITE: Run phase3_schema_changes.sql in Supabase first.
 */
public class ReconciliationTest {

    public static void main(String[] args) {

        System.out.println("================================================");
        System.out.println("  BANK SETTLEMENT — RECONCILIATION TEST");
        System.out.println("================================================\n");

        try {
            ReconciliationServiceImpl reconciliationService = new ReconciliationServiceImpl();
            List<ReconciliationEntry> entries = reconciliationService.runReconciliation();

            System.out.println("[ReconciliationTest] Done.");
            System.out.println("[ReconciliationTest] Total reconciliation entries: " + entries.size());

            long matched   = entries.stream()
                    .filter(e -> e.getReconStatus() == com.iispl.banksettlement.enums.ReconStatus.MATCHED)
                    .count();
            long unmatched = entries.size() - matched;

            System.out.println("[ReconciliationTest] Matched: " + matched
                    + " | Unmatched: " + unmatched);

        } catch (Exception e) {
            System.out.println("\n[ReconciliationTest] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ConnectionPool.shutdown();
            System.out.println("[ReconciliationTest] Connection pool closed.");
        }
    }
}