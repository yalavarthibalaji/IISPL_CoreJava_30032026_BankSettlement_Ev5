package com.iispl.banksettlement;

import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.service.impl.NettingServiceImpl;
import com.iispl.connectionpool.ConnectionPool;

import java.util.List;

/**
 * NettingEngineTest — Runs the post-settlement netting cycle.
 *
 * RUN THIS AFTER:
 *   Step 1: FileIngestionTest       — ingests transaction files
 *   Step 2: SettlementProcessorTest — dispatches to credit/debit tables
 *   Step 3: SettlementEngineTest    — settles all transactions
 *   Step 4: NettingEngineTest       ← YOU ARE HERE
 *
 * WHAT THIS DOES:
 *   1. Reads all PROCESSED incoming_transaction rows from DB.
 *   2. Extracts fromBank and toBank from each normalized_payload.
 *   3. Computes bilateral gross debit, gross credit, and net amount per bank pair.
 *   4. Saves NettingPosition rows to netting_position table.
 *   5. Updates NPCI member account balances in npci_bank_account table.
 *   6. Prints: "HDFC Bank → MUST PAY → Rs. X → TO → SBI Bank"
 *
 * PRE-REQUISITE: Run phase3_schema_changes.sql in Supabase first.
 */
public class NettingEngineTest {

    public static void main(String[] args) {

        System.out.println("================================================");
        System.out.println("  BANK SETTLEMENT — NETTING ENGINE TEST");
        System.out.println("================================================\n");

        try {
            NettingServiceImpl nettingService = new NettingServiceImpl();
            List<NettingPosition> positions = nettingService.runNetting();

            System.out.println("[NettingEngineTest] Done.");
            System.out.println("[NettingEngineTest] Total bilateral netting positions: " + positions.size());

        } catch (Exception e) {
            System.out.println("\n[NettingEngineTest] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ConnectionPool.shutdown();
            System.out.println("[NettingEngineTest] Connection pool closed.");
        }
    }
}