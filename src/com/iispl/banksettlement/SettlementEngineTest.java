package com.iispl.banksettlement;

import com.iispl.banksettlement.service.impl.SettlementEngineImpl;
import com.iispl.connectionpool.ConnectionPool;

/**
 * SettlementEngineTest — Main class to run the full settlement pipeline.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * WHAT THIS RUNS:
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Phase 1 — PRODUCER: Reads ALL INITIATED transactions from 4 DB tables:
 * credit_transaction (CBS, NEFT, UPI, FT channels) debit_transaction (CBS,
 * NEFT, FT channels) interbank_transaction (RTGS channel) reversal_transaction
 * (FT channel)
 *
 * Wraps each into a SettlementItem and puts it on a BlockingQueue.
 *
 * Phase 2 — CONSUMER (separate thread): Drains the BlockingQueue. Groups items
 * into 5 channel buckets: CBS bucket, RTGS bucket, NEFT bucket, UPI bucket, FT
 * bucket
 *
 * Phase 3 — SETTLEMENT (inside consumer thread): CBS → direct balance update
 * per transaction RTGS → gross settlement per interbank transaction NEFT → net
 * settlement (sum debits/credits → apply net) UPI → record settled, no local
 * balance update (VPA-based) FT → best effort (credit + debit + reversal)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PREREQUISITES — run BEFORE this class:
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Step 1: FileIngestionTest.java → populates incoming_transaction table
 *
 * Step 2: SettlementProcessorTest.java (MODE_A or MODE_B) → populates
 * credit_transaction, debit_transaction, interbank_transaction,
 * reversal_transaction with status = INITIATED
 *
 * Step 3: THIS CLASS — SettlementEngineTest.java → settles all INITIATED
 * transactions
 *
 * ─────────────────────────────────────────────────────────────────────────
 * EXPECTED DB CHANGES AFTER RUNNING:
 * ─────────────────────────────────────────────────────────────────────────
 *
 * settlement_batch: Up to 5 new rows (BATCH-CBS-*, BATCH-RTGS-*, BATCH-NEFT-*,
 * BATCH-UPI-*, BATCH-FT-*) batch_status = COMPLETED / PARTIAL / FAILED
 *
 * settlement_record: One row per transaction processed settled_status = SETTLED
 * or FAILED
 *
 * account: balance updated for CBS, RTGS, FT settled transactions (UPI skips
 * balance update, NEFT net is logged but not applied locally)
 *
 * credit_transaction → status: INITIATED → SETTLED or FAILED debit_transaction
 * → status: INITIATED → SETTLED or FAILED interbank_transaction → status:
 * INITIATED → SETTLED or FAILED reversal_transaction → status: INITIATED →
 * SETTLED or FAILED
 *
 * PACKAGE: com.iispl.banksettlement
 */
public class SettlementEngineTest {

	public static void main(String[] args) {

		System.out.println("================================================");
		System.out.println("  BANK SETTLEMENT — SETTLEMENT ENGINE TEST");
		System.out.println("================================================\n");

		try {

<<<<<<< Updated upstream
			SettlementEngine engine = new SettlementEngine();
			engine.runSettlement();
=======
            SettlementEngineImpl engine = new SettlementEngineImpl();
            engine.runSettlement();
>>>>>>> Stashed changes

			System.out.println("\n================================================");
			System.out.println("  SETTLEMENT ENGINE TEST — COMPLETE");
			System.out.println("  Check DB tables:");
			System.out.println("    settlement_batch      — up to 5 batch rows");
			System.out.println("    settlement_record     — one row per transaction");
			System.out.println("    account               — updated balances");
			System.out.println("    credit_transaction    — SETTLED / FAILED");
			System.out.println("    debit_transaction     — SETTLED / FAILED");
			System.out.println("    interbank_transaction — SETTLED / FAILED");
			System.out.println("    reversal_transaction  — SETTLED / FAILED");
			System.out.println("================================================\n");

		} catch (Exception e) {
			System.out.println("\n[SettlementEngineTest] FATAL ERROR: " + e.getMessage());
			e.printStackTrace();
		} finally {
			// Always close the connection pool
			ConnectionPool.shutdown();
			System.out.println("[SettlementEngineTest] Connection pool closed.");
		}
	}
}