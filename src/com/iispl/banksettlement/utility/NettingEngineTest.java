package com.iispl.banksettlement.utility;

import com.iispl.banksettlement.entity.NettingResult;
import com.iispl.banksettlement.service.impl.NettingServiceImpl;
import com.iispl.connectionpool.ConnectionPool;

import java.util.List;

/**
 * NettingEngineTest — Runs the post-settlement netting cycle.
 *
 * WHAT THIS DOES:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Reads all PROCESSED incoming_transaction rows from DB.
 *    (These are transactions that have been ingested AND dispatched already.)
 *
 * 2. Parses fromBank and toBank from each row's normalized_payload JSON.
 *
 * 3. Computes bilateral net positions — for each pair of banks,
 *    calculates who owes whom and how much.
 *
 * 4. Prints the inter-bank payment report:
 *    "HDFC Bank → MUST PAY → Rs. 3,00,000 → TO → SBI Bank"
 *
 * 5. NPCI applies the netting results to update each bank's
 *    settlement account balance.
 *
 * PRE-REQUISITES:
 *   Run in this order:
 *     Step 1: FileIngestionTest       (or SettlementProcessorTest MODE_B)
 *     Step 2: SettlementProcessorTest (dispatches to credit/debit tables)
 *     Step 3: SettlementEngineTest    (settles all transactions)
 *     Step 4: NettingEngineTest       ← YOU ARE HERE
 *
 * PACKAGE: com.iispl.banksettlement.utility
 */
public class NettingEngineTest {

    public static void main(String[] args) {

        System.out.println("================================================");
        System.out.println("  BANK SETTLEMENT — NETTING ENGINE TEST");
        System.out.println("================================================\n");

        try {
            NettingServiceImpl nettingService = new NettingServiceImpl();
            List<NettingResult> results = nettingService.runNetting();

            System.out.println("\n[NettingEngineTest] Summary:");
            System.out.println("[NettingEngineTest] Total inter-bank obligations computed: " + results.size());

        } catch (Exception e) {
            System.out.println("\n[NettingEngineTest] FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ConnectionPool.shutdown();
            System.out.println("[NettingEngineTest] Connection pool closed.");
        }
    }
}