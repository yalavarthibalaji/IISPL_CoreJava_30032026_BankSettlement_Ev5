package com.iispl.banksettlement.service;

import com.iispl.banksettlement.dao.SettlementBatchDao;
import com.iispl.banksettlement.dao.impl.SettlementBatchDaoImpl;
import com.iispl.banksettlement.dao.CreditTransactionDao;
import com.iispl.banksettlement.dao.impl.CreditTransactionDaoImpl;
import com.iispl.banksettlement.dao.DebitTransactionDao;
import com.iispl.banksettlement.dao.impl.DebitTransactionDaoImpl;
import com.iispl.banksettlement.dao.InterBankTransactionDao;
import com.iispl.banksettlement.dao.impl.InterBankTransactionDaoImpl;
import com.iispl.banksettlement.dao.ReversalTransactionDao;
import com.iispl.banksettlement.dao.impl.ReversalTransactionDaoImpl;
import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.dao.impl.IncomingTransactionDaoImpl;

import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.banksettlement.entity.SettlementBatch;
import com.iispl.banksettlement.entity.SettlementRecord;

import com.iispl.banksettlement.enums.BatchStatus;
import com.iispl.banksettlement.enums.SettlementStatus;
import com.iispl.banksettlement.enums.TransactionStatus;

import com.iispl.connectionpool.ConnectionPool;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SettlementEngine — Loads SETTLED transactions from DB tables into a
 * BlockingQueue, groups them by payment channel, creates one SettlementBatch
 * per channel, and settles them.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * FULL FLOW (step by step):
 * ─────────────────────────────────────────────────────────────────────────
 *
 * PHASE 1 — PRODUCER (runs in main thread): Reads ALL INITIATED transactions
 * from 4 DB tables: credit_transaction → identifies channel from
 * reference_number prefix debit_transaction → identifies channel from
 * reference_number prefix interbank_transaction → always RTGS channel
 * reversal_transaction → always FT (FinTech) channel
 *
 * Wraps each transaction into a SettlementItem (contains channel + txn object)
 * Puts all SettlementItems onto a LinkedBlockingQueue. Puts one SHUTDOWN
 * sentinel at the end.
 *
 * PHASE 2 — CONSUMER (runs in a separate Thread): Drains the BlockingQueue item
 * by item. Groups items into 5 channel buckets (Maps): cbsItems → CBS channel
 * (CREDIT + DEBIT) rtgsItems → RTGS channel (INTERBANK only) neftItems → NEFT
 * channel (CREDIT + DEBIT) upiItems → UPI channel (CREDIT only) ftItems → FT
 * channel (CREDIT + DEBIT + REVERSAL)
 *
 * PHASE 3 — BATCH CREATION + SETTLEMENT (inside consumer, after queue is
 * drained): For each non-empty channel bucket: a) CREATE a SettlementBatch
 * (INSERT into settlement_batch, status=RUNNING) b) SETTLE each transaction
 * using the channel's specific rule: CBS → direct balance update per
 * transaction RTGS → gross settlement per transaction (validate balance first)
 * NEFT → NET settlement (sum all debits/credits, apply net once) UPI → record
 * as settled (VPA-based, no local balance update) FT → best effort (never stop
 * on failure) c) For each transaction: INSERT into settlement_record d) UPDATE
 * transaction status to SETTLED or FAILED in its own table e) FINALIZE batch:
 * update settlement_batch with final status + totals
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CHANNEL DETECTION (from reference_number prefix):
 * ─────────────────────────────────────────────────────────────────────────
 * CBS-xxxx → CBS RTGS-xxxx → RTGS (also all interbank_transaction rows)
 * NEFT-xxxx → NEFT UPI-xxxx → UPI FT-xxxx → FT (also all reversal_transaction
 * rows)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * NETTING (NEFT channel):
 * ─────────────────────────────────────────────────────────────────────────
 * NEFT settles in net batches — not gross. Net = SUM(all NEFT credits) -
 * SUM(all NEFT debits) If net > 0 → net credit position (more money came in
 * than went out) If net < 0 → net debit position (more money went out than came
 * in) Balance update is logged but not applied to local accounts (in real
 * banking, this net position settles via RBI clearing). All individual NEFT
 * transactions are still marked SETTLED in DB.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PACKAGE: com.iispl.banksettlement.service
 * ─────────────────────────────────────────────────────────────────────────
 */
public class SettlementEngine {

	// -----------------------------------------------------------------------
	// SQL for reading INITIATED transactions by channel prefix
	// -----------------------------------------------------------------------

	private static final String SQL_LOAD_INITIATED_CREDITS = "SELECT txn_id, debit_account_id, credit_account_id, credit_account_number, "
			+ "amount, currency, txn_date, value_date, status, reference_number, txn_type "
			+ "FROM credit_transaction WHERE status = 'INITIATED' AND reference_number LIKE ? " + "ORDER BY txn_id ASC";

	private static final String SQL_LOAD_INITIATED_DEBITS = "SELECT txn_id, debit_account_id, credit_account_id, debit_account_number, "
			+ "amount, currency, txn_date, value_date, status, reference_number, txn_type "
			+ "FROM debit_transaction WHERE status = 'INITIATED' AND reference_number LIKE ? " + "ORDER BY txn_id ASC";

	private static final String SQL_LOAD_INITIATED_INTERBANK = "SELECT txn_id, debit_account_id, credit_account_id, correspondent_bank_code, "
			+ "amount, currency, txn_date, value_date, status, reference_number, txn_type "
			+ "FROM interbank_transaction WHERE status = 'INITIATED' " + "ORDER BY txn_id ASC";

	private static final String SQL_LOAD_INITIATED_REVERSALS = "SELECT txn_id, debit_account_id, credit_account_id, original_txn_ref, "
			+ "reversal_reason, amount, currency, txn_date, value_date, status, reference_number "
			+ "FROM reversal_transaction WHERE status = 'INITIATED' " + "ORDER BY txn_id ASC";

	// SQL for account balance operations
	private static final String SQL_CREDIT_BALANCE = "UPDATE account SET balance = balance + ?, updated_at = now() WHERE account_id = ?";

	private static final String SQL_DEBIT_BALANCE = "UPDATE account SET balance = balance - ?, updated_at = now() WHERE account_id = ?";

	private static final String SQL_GET_BALANCE = "SELECT balance FROM account WHERE account_id = ?";

	// SQL for looking up incoming_txn_id by source_ref (reference_number)
	private static final String SQL_FIND_INCOMING_TXN_ID = "SELECT incoming_txn_id FROM incoming_transaction WHERE source_ref = ?";

	// -----------------------------------------------------------------------
	// DAOs
	// -----------------------------------------------------------------------

	private final SettlementBatchDao batchDao;
	private final CreditTransactionDao creditDao;
	private final DebitTransactionDao debitDao;
	private final InterBankTransactionDao interbankDao;
	private final ReversalTransactionDao reversalDao;

	// -----------------------------------------------------------------------
	// Constructor
	// -----------------------------------------------------------------------

	public SettlementEngine() {
		this.batchDao = new SettlementBatchDaoImpl();
		this.creditDao = new CreditTransactionDaoImpl();
		this.debitDao = new DebitTransactionDaoImpl();
		this.interbankDao = new InterBankTransactionDaoImpl();
		this.reversalDao = new ReversalTransactionDaoImpl();
	}

	// -----------------------------------------------------------------------
	// runSettlement() — MAIN ENTRY POINT
	// -----------------------------------------------------------------------

	/**
	 * Full settlement pipeline: Phase 1 → load all INITIATED transactions → put on
	 * BlockingQueue Phase 2 → consumer thread drains queue → groups by channel
	 * Phase 3 → settle each channel batch
	 */
	public void runSettlement() {

		System.out.println("\n================================================");
		System.out.println("  SETTLEMENT ENGINE — STARTING");
		System.out.println("  Date: " + LocalDate.now());
		System.out.println("================================================\n");

		// ----------------------------------------------------------------
		// PHASE 1 — PRODUCER: Load all INITIATED transactions from DB
		// and put them onto the BlockingQueue as SettlementItems
		// ----------------------------------------------------------------

		// Queue capacity 1000 — adjust if you have more transactions
		BlockingQueue<SettlementItem> queue = new LinkedBlockingQueue<>(1000);

		int totalLoaded = loadAllTransactionsIntoQueue(queue);

		if (totalLoaded == 0) {
			System.out.println("[SettlementEngine] No INITIATED transactions found in DB.");
			System.out.println("[SettlementEngine] Run SettlementProcessorTest first.");
			return;
		}

		System.out.println(
				"\n[SettlementEngine] PHASE 1 DONE — Loaded " + totalLoaded + " transaction(s) onto the queue.");

		// Put shutdown sentinel so the consumer knows when to stop
		try {
			queue.put(SettlementItem.shutdown());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		// ----------------------------------------------------------------
		// PHASE 2 + 3 — CONSUMER: Drain queue, group by channel, settle
		// Running in a separate thread (classic producer-consumer)
		// ----------------------------------------------------------------

		// The consumer Runnable
		Runnable consumer = () -> {

			System.out.println(
					"\n[SettlementEngine] PHASE 2 — Consumer thread started: " + Thread.currentThread().getName());

			// 5 channel buckets — each holds the SettlementItems for that channel
			List<SettlementItem> cbsItems = new ArrayList<>();
			List<SettlementItem> rtgsItems = new ArrayList<>();
			List<SettlementItem> neftItems = new ArrayList<>();
			List<SettlementItem> upiItems = new ArrayList<>();
			List<SettlementItem> ftItems = new ArrayList<>();

			// Drain queue — group each item into the correct channel bucket
			while (true) {
				try {
					SettlementItem item = queue.take();

					// Shutdown sentinel — stop draining
					if (item.isShutdown()) {
						System.out.println("[SettlementEngine] Shutdown sentinel received. "
								+ "Queue drained. Starting settlement...");
						break;
					}

					// Route to correct channel bucket
					switch (item.getChannel()) {
					case "CBS":
						cbsItems.add(item);
						break;
					case "RTGS":
						rtgsItems.add(item);
						break;
					case "NEFT":
						neftItems.add(item);
						break;
					case "UPI":
						upiItems.add(item);
						break;
					case "FT":
						ftItems.add(item);
						break;
					default:
						System.out.println("[SettlementEngine] UNKNOWN channel: " + item.getChannel()
								+ " — skipping item: " + item);
					}

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.out.println("[SettlementEngine] Consumer interrupted.");
					break;
				}
			}

			System.out.println("\n[SettlementEngine] PHASE 2 DONE — Channel bucket sizes:");
			System.out.println("  CBS:    " + cbsItems.size() + " item(s)");
			System.out.println("  RTGS:   " + rtgsItems.size() + " item(s)");
			System.out.println("  NEFT:   " + neftItems.size() + " item(s)");
			System.out.println("  UPI:    " + upiItems.size() + " item(s)");
			System.out.println("  FT:     " + ftItems.size() + " item(s)");

			// ----------------------------------------------------------------
			// PHASE 3 — SETTLE each channel bucket
			// ----------------------------------------------------------------
			System.out.println("\n[SettlementEngine] PHASE 3 — Starting batch settlement...");

			if (!cbsItems.isEmpty())
				settleCbsBatch(cbsItems);
			if (!rtgsItems.isEmpty())
				settleRtgsBatch(rtgsItems);
			if (!neftItems.isEmpty())
				settleNeftBatch(neftItems);
			if (!upiItems.isEmpty())
				settleUpiBatch(upiItems);
			if (!ftItems.isEmpty())
				settleFtBatch(ftItems);

			System.out.println("\n[SettlementEngine] PHASE 3 DONE — All channels settled.");
		};

		// Start consumer in a background thread
		Thread consumerThread = new Thread(consumer, "SettlementEngine-Consumer");
		consumerThread.start();

		// Wait for consumer to finish
		try {
			consumerThread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		System.out.println("\n================================================");
		System.out.println("  SETTLEMENT ENGINE — COMPLETE");
		System.out.println("================================================\n");
	}

	// =======================================================================
	// PHASE 1 — PRODUCER: Load transactions from all 4 DB tables → queue
	// =======================================================================

	/**
	 * Loads all INITIATED transactions from all 4 tables. Wraps each into a
	 * SettlementItem with the correct channel. Puts all items onto the queue.
	 *
	 * @return total number of items put on the queue
	 */
	private int loadAllTransactionsIntoQueue(BlockingQueue<SettlementItem> queue) {

		int count = 0;

		System.out.println("[SettlementEngine] Loading INITIATED transactions from DB...");

		// ---- credit_transaction: CBS, NEFT, UPI, FT channels ----
		count += loadCreditsForChannel(queue, "CBS-", "CBS");
		count += loadCreditsForChannel(queue, "NEFT-", "NEFT");
		count += loadCreditsForChannel(queue, "UPI-", "UPI");
		count += loadCreditsForChannel(queue, "FT-", "FT");

		// ---- debit_transaction: CBS, NEFT, FT channels ----
		count += loadDebitsForChannel(queue, "CBS-", "CBS");
		count += loadDebitsForChannel(queue, "NEFT-", "NEFT");
		count += loadDebitsForChannel(queue, "FT-", "FT");

		// ---- interbank_transaction: always RTGS channel ----
		count += loadInterbankTransactions(queue);

		// ---- reversal_transaction: always FT channel ----
		count += loadReversalTransactions(queue);

		System.out.println("[SettlementEngine] Total items loaded onto queue: " + count);
		return count;
	}

	// ---- Load INITIATED credit_transaction rows for a given prefix + channel ----

	private int loadCreditsForChannel(BlockingQueue<SettlementItem> queue, String prefix, String channel) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		int count = 0;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_LOAD_INITIATED_CREDITS);
			ps.setString(1, prefix + "%");
			rs = ps.executeQuery();
			while (rs.next()) {
				CreditTransaction txn = mapCreditRow(rs);
				Long incomingTxnId = findIncomingTxnId(txn.getReferenceNumber());
				SettlementItem item = SettlementItem.ofCredit(channel, txn, incomingTxnId);
				queue.put(item);
				count++;
				System.out.println("[SettlementEngine] Queued CREDIT [" + channel + "] | ref: "
						+ txn.getReferenceNumber() + " | amount: " + txn.getAmount());
			}
		} catch (SQLException e) {
			throw new RuntimeException("loadCreditsForChannel() failed for prefix: " + prefix + " | " + e.getMessage(),
					e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeResources(rs, ps, conn);
		}
		return count;
	}

	// ---- Load INITIATED debit_transaction rows for a given prefix + channel ----

	private int loadDebitsForChannel(BlockingQueue<SettlementItem> queue, String prefix, String channel) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		int count = 0;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_LOAD_INITIATED_DEBITS);
			ps.setString(1, prefix + "%");
			rs = ps.executeQuery();
			while (rs.next()) {
				DebitTransaction txn = mapDebitRow(rs);
				Long incomingTxnId = findIncomingTxnId(txn.getReferenceNumber());
				SettlementItem item = SettlementItem.ofDebit(channel, txn, incomingTxnId);
				queue.put(item);
				count++;
				System.out.println("[SettlementEngine] Queued DEBIT [" + channel + "] | ref: "
						+ txn.getReferenceNumber() + " | amount: " + txn.getAmount());
			}
		} catch (SQLException e) {
			throw new RuntimeException("loadDebitsForChannel() failed for prefix: " + prefix + " | " + e.getMessage(),
					e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeResources(rs, ps, conn);
		}
		return count;
	}

	// ---- Load ALL INITIATED interbank_transaction rows → RTGS channel ----

	private int loadInterbankTransactions(BlockingQueue<SettlementItem> queue) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		int count = 0;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_LOAD_INITIATED_INTERBANK);
			rs = ps.executeQuery();
			while (rs.next()) {
				InterBankTransaction txn = mapInterbankRow(rs);
				Long incomingTxnId = findIncomingTxnId(txn.getReferenceNumber());
				SettlementItem item = SettlementItem.ofInterbank("RTGS", txn, incomingTxnId);
				queue.put(item);
				count++;
				System.out.println("[SettlementEngine] Queued INTERBANK [RTGS] | ref: " + txn.getReferenceNumber()
						+ " | amount: " + txn.getAmount() + " | correspondent: " + txn.getCorrespondentBankCode());
			}
		} catch (SQLException e) {
			throw new RuntimeException("loadInterbankTransactions() failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeResources(rs, ps, conn);
		}
		return count;
	}

	// ---- Load ALL INITIATED reversal_transaction rows → FT channel ----

	private int loadReversalTransactions(BlockingQueue<SettlementItem> queue) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		int count = 0;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_LOAD_INITIATED_REVERSALS);
			rs = ps.executeQuery();
			while (rs.next()) {
				ReversalTransaction txn = mapReversalRow(rs);
				Long incomingTxnId = findIncomingTxnId(txn.getReferenceNumber());
				SettlementItem item = SettlementItem.ofReversal("FT", txn, incomingTxnId);
				queue.put(item);
				count++;
				System.out.println("[SettlementEngine] Queued REVERSAL [FT] | ref: " + txn.getReferenceNumber()
						+ " | amount: " + txn.getAmount() + " | originalRef: " + txn.getOriginalTxnRef());
			}
		} catch (SQLException e) {
			throw new RuntimeException("loadReversalTransactions() failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeResources(rs, ps, conn);
		}
		return count;
	}

	// =======================================================================
	// PHASE 3 — SETTLEMENT per channel
	// =======================================================================

	// -----------------------------------------------------------------------
	// CBS — Direct balance update per transaction (CREDIT + DEBIT)
	// -----------------------------------------------------------------------

	private void settleCbsBatch(List<SettlementItem> items) {

		System.out.println("\n--- [CBS] Settling " + items.size() + " transaction(s) ---");

		String batchId = buildBatchId("CBS");
		SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
		batch.setBatchStatus(BatchStatus.RUNNING);
		batchDao.saveBatch(batch);

		int settled = 0;
		int failed = 0;

		for (SettlementItem item : items) {

			try {
				if ("CREDIT".equals(item.getTxnType())) {

					CreditTransaction txn = item.getCredit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					creditBalance(txn.getCreditAccountId(), txn.getAmount());
					creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[CBS] CREDIT settled | ref: " + txn.getReferenceNumber() + " | amount: "
							+ txn.getAmount());

				} else if ("DEBIT".equals(item.getTxnType())) {

					DebitTransaction txn = item.getDebit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					checkSufficientBalance(txn.getDebitAccountId(), txn.getAmount(), txn.getReferenceNumber());
					debitBalance(txn.getDebitAccountId(), txn.getAmount());
					debitDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println(
							"[CBS] DEBIT settled | ref: " + txn.getReferenceNumber() + " | amount: " + txn.getAmount());
				}

			} catch (Exception e) {
				failed++;
				handleFailure(item, batchId, batch, e.getMessage());
			}
		}

		finalizeBatch(batch, settled, failed, "CBS");
	}

	// -----------------------------------------------------------------------
	// RTGS — Gross settlement: each InterBankTransaction settled individually
	// -----------------------------------------------------------------------

	private void settleRtgsBatch(List<SettlementItem> items) {

		System.out.println("\n--- [RTGS] Settling " + items.size() + " interbank transaction(s) GROSS ---");

		String batchId = buildBatchId("RTGS");
		SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
		batch.setBatchStatus(BatchStatus.RUNNING);
		batchDao.saveBatch(batch);

		int settled = 0;
		int failed = 0;

		for (SettlementItem item : items) {

			try {
				InterBankTransaction txn = item.getInterbank();
				validateAmount(txn.getAmount(), txn.getReferenceNumber());

				// GROSS: settle debit side then credit side individually
				boolean debitSkip = (txn.getDebitAccountId() == null || txn.getDebitAccountId() == 0L);
				boolean creditSkip = (txn.getCreditAccountId() == null || txn.getCreditAccountId() == 0L);

				if (!debitSkip) {
					checkSufficientBalance(txn.getDebitAccountId(), txn.getAmount(), txn.getReferenceNumber());
					debitBalance(txn.getDebitAccountId(), txn.getAmount());
				}
				if (!creditSkip) {
					creditBalance(txn.getCreditAccountId(), txn.getAmount());
				}

				interbankDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

				SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
						SettlementStatus.SETTLED, null);
				batchDao.saveRecord(rec);
				batch.addRecord(rec);
				settled++;
				System.out.println("[RTGS] GROSS settled | ref: " + txn.getReferenceNumber() + " | amount: "
						+ txn.getAmount() + " | correspondent: " + txn.getCorrespondentBankCode());

			} catch (Exception e) {
				failed++;
				handleFailure(item, batchId, batch, e.getMessage());
			}
		}

		finalizeBatch(batch, settled, failed, "RTGS");
	}

	// -----------------------------------------------------------------------
	// NEFT — NET settlement: sum all debits and credits, apply net once
	// -----------------------------------------------------------------------

	private void settleNeftBatch(List<SettlementItem> items) {

		System.out.println("\n--- [NEFT] Settling " + items.size() + " transaction(s) using NET settlement ---");

		String batchId = buildBatchId("NEFT");
		SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
		batch.setBatchStatus(BatchStatus.RUNNING);
		batchDao.saveBatch(batch);

		// ---- NETTING: accumulate gross totals ----
		BigDecimal totalCredits = BigDecimal.ZERO;
		BigDecimal totalDebits = BigDecimal.ZERO;

		for (SettlementItem item : items) {
			if ("CREDIT".equals(item.getTxnType()) && item.getCredit().getAmount() != null) {
				totalCredits = totalCredits.add(item.getCredit().getAmount());
			} else if ("DEBIT".equals(item.getTxnType()) && item.getDebit().getAmount() != null) {
				totalDebits = totalDebits.add(item.getDebit().getAmount());
			}
		}

		BigDecimal netAmount = totalCredits.subtract(totalDebits);

		System.out.println("[NEFT] ── Netting Summary ──");
		System.out.println("[NEFT] Gross Credits : " + totalCredits);
		System.out.println("[NEFT] Gross Debits  : " + totalDebits);
		System.out.println("[NEFT] Net Amount    : " + netAmount
				+ (netAmount.compareTo(BigDecimal.ZERO) >= 0 ? "  ← NET CREDIT POSITION (more money received)"
						: "  ← NET DEBIT POSITION  (more money sent)"));

		// ---- Record each transaction individually for audit trail ----
		int settled = 0;
		int failed = 0;

		for (SettlementItem item : items) {
			try {
				if ("CREDIT".equals(item.getTxnType())) {
					CreditTransaction txn = item.getCredit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					// NEFT net: do NOT update individual account balances here.
					// The net position settles via RBI clearing house — not local accounts.
					creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[NEFT] CREDIT recorded (net) | ref: " + txn.getReferenceNumber() + " | amount: "
							+ txn.getAmount());

				} else if ("DEBIT".equals(item.getTxnType())) {
					DebitTransaction txn = item.getDebit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					debitDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[NEFT] DEBIT recorded (net) | ref: " + txn.getReferenceNumber() + " | amount: "
							+ txn.getAmount());
				}
			} catch (Exception e) {
				failed++;
				handleFailure(item, batchId, batch, e.getMessage());
			}
		}

		// Print final net position
		System.out.println("[NEFT] ── Net Settlement Applied ──");
		System.out.println("[NEFT] Net amount: " + netAmount
				+ (netAmount.compareTo(BigDecimal.ZERO) >= 0 ? " → Bank receives this net amount via RBI clearing"
						: " → Bank pays this net amount via RBI clearing"));

		finalizeBatch(batch, settled, failed, "NEFT");
	}

	// -----------------------------------------------------------------------
	// UPI — VPA-based real-time: skip local account balance update
	// -----------------------------------------------------------------------

	private void settleUpiBatch(List<SettlementItem> items) {

		System.out
				.println("\n--- [UPI] Settling " + items.size() + " VPA transaction(s) (no local balance update) ---");

		String batchId = buildBatchId("UPI");
		SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
		batch.setBatchStatus(BatchStatus.RUNNING);
		batchDao.saveBatch(batch);

		int settled = 0;
		int failed = 0;

		for (SettlementItem item : items) {
			try {
				// UPI uses VPA handles (e.g. user@paytm) — no local account_id rows.
				// Money movement is handled externally by NPCI.
				// We just record the settlement in our system.
				CreditTransaction txn = item.getCredit();
				validateAmount(txn.getAmount(), txn.getReferenceNumber());
				// NO balance update — skip creditBalance() for UPI
				creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

				SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
						SettlementStatus.SETTLED, null);
				batchDao.saveRecord(rec);
				batch.addRecord(rec);
				settled++;
				System.out.println("[UPI] Settled (VPA, no balance update) | ref: " + txn.getReferenceNumber()
						+ " | amount: " + txn.getAmount());

			} catch (Exception e) {
				failed++;
				handleFailure(item, batchId, batch, e.getMessage());
			}
		}

		finalizeBatch(batch, settled, failed, "UPI");
	}

	// -----------------------------------------------------------------------
	// FT (FinTech) — Best effort: CREDIT + DEBIT + REVERSAL, never stop on fail
	// -----------------------------------------------------------------------

	private void settleFtBatch(List<SettlementItem> items) {

		System.out.println("\n--- [FT] Settling " + items.size() + " FinTech transaction(s) (best effort) ---");

		String batchId = buildBatchId("FT");
		SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
		batch.setBatchStatus(BatchStatus.RUNNING);
		batchDao.saveBatch(batch);

		int settled = 0;
		int failed = 0;

		for (SettlementItem item : items) {
			// Best effort — catch ALL exceptions, log, and continue
			try {
				if ("CREDIT".equals(item.getTxnType())) {

					CreditTransaction txn = item.getCredit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					creditBalance(txn.getCreditAccountId(), txn.getAmount());
					creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[FT] CREDIT settled | ref: " + txn.getReferenceNumber());

				} else if ("DEBIT".equals(item.getTxnType())) {

					DebitTransaction txn = item.getDebit();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					checkSufficientBalance(txn.getDebitAccountId(), txn.getAmount(), txn.getReferenceNumber());
					debitBalance(txn.getDebitAccountId(), txn.getAmount());
					debitDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[FT] DEBIT settled | ref: " + txn.getReferenceNumber());

				} else if ("REVERSAL".equals(item.getTxnType())) {

					ReversalTransaction txn = item.getReversal();
					validateAmount(txn.getAmount(), txn.getReferenceNumber());
					// Reversal: credit back the amount to the original debit account
					if (txn.getDebitAccountId() != null && txn.getDebitAccountId() != 0L) {
						creditBalance(txn.getDebitAccountId(), txn.getAmount());
					}
					reversalDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

					SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
							SettlementStatus.SETTLED, null);
					batchDao.saveRecord(rec);
					batch.addRecord(rec);
					settled++;
					System.out.println("[FT] REVERSAL settled | ref: " + txn.getReferenceNumber() + " | originalRef: "
							+ txn.getOriginalTxnRef());
				}

			} catch (Exception e) {
				// Best effort — NEVER rethrow
				failed++;
				handleFailure(item, batchId, batch, e.getMessage());
			}
		}

		finalizeBatch(batch, settled, failed, "FT");
	}

	// =======================================================================
	// PRIVATE HELPERS
	// =======================================================================

	/**
	 * Validates that the amount is not null and is greater than zero. Throws
	 * RuntimeException if invalid — caught by channel settlers.
	 */
	private void validateAmount(BigDecimal amount, String ref) {
		if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
			throw new RuntimeException("Invalid amount: " + amount + " for ref: " + ref);
		}
	}

	/**
	 * Checks that an account has enough balance for a debit. Throws
	 * RuntimeException if insufficient — caught by channel settlers.
	 */
	private void checkSufficientBalance(Long accountId, BigDecimal required, String ref) {
		if (accountId == null || accountId == 0L)
			return; // skip for placeholder IDs
		BigDecimal balance = getBalance(accountId);
		if (balance.compareTo(required) < 0) {
			throw new RuntimeException("Insufficient funds | accountId: " + accountId + " | balance: " + balance
					+ " | required: " + required + " | ref: " + ref);
		}
	}

	/**
	 * Handles a failed settlement item: - Logs the error - Creates a FAILED
	 * SettlementRecord and saves it - Adds it to the batch - Updates the
	 * transaction status to FAILED in its own table
	 */
	private void handleFailure(SettlementItem item, String batchId, SettlementBatch batch, String reason) {

		BigDecimal amount = BigDecimal.ZERO;
		String ref = "unknown";

		try {
			if ("CREDIT".equals(item.getTxnType()) && item.getCredit() != null) {
				amount = item.getCredit().getAmount();
				ref = item.getCredit().getReferenceNumber();
				creditDao.updateStatus(item.getCredit().getTxnId(), TransactionStatus.FAILED.name());
			} else if ("DEBIT".equals(item.getTxnType()) && item.getDebit() != null) {
				amount = item.getDebit().getAmount();
				ref = item.getDebit().getReferenceNumber();
				debitDao.updateStatus(item.getDebit().getTxnId(), TransactionStatus.FAILED.name());
			} else if ("INTERBANK".equals(item.getTxnType()) && item.getInterbank() != null) {
				amount = item.getInterbank().getAmount();
				ref = item.getInterbank().getReferenceNumber();
				interbankDao.updateStatus(item.getInterbank().getTxnId(), TransactionStatus.FAILED.name());
			} else if ("REVERSAL".equals(item.getTxnType()) && item.getReversal() != null) {
				amount = item.getReversal().getAmount();
				ref = item.getReversal().getReferenceNumber();
				reversalDao.updateStatus(item.getReversal().getTxnId(), TransactionStatus.FAILED.name());
			}
		} catch (Exception ignored) {
			// Don't let status update failure hide the original error
		}

		System.out.println("[SettlementEngine] FAILED | channel: " + item.getChannel() + " | type: " + item.getTxnType()
				+ " | ref: " + ref + " | reason: " + reason);

		try {
			Long incomingTxnId = (item.getIncomingTxnId() != null) ? item.getIncomingTxnId() : 0L;
			SettlementRecord rec = new SettlementRecord(batchId, incomingTxnId,
					(amount != null ? amount : BigDecimal.ZERO), SettlementStatus.FAILED, reason);
			batchDao.saveRecord(rec);
			batch.addRecord(rec);
		} catch (Exception e) {
			System.out.println("[SettlementEngine] Could not save FAILED record: " + e.getMessage());
		}
	}

	/**
	 * Decides final BatchStatus, updates batch in DB, and prints summary. All
	 * settled → COMPLETED Some settled → PARTIAL None settled → FAILED
	 */
	private void finalizeBatch(SettlementBatch batch, int settled, int failed, String channel) {
		if (failed == 0 && settled > 0) {
			batch.setBatchStatus(BatchStatus.COMPLETED);
		} else if (settled > 0) {
			batch.setBatchStatus(BatchStatus.PARTIAL);
		} else {
			batch.setBatchStatus(BatchStatus.FAILED);
		}
		batchDao.updateBatch(batch);
		System.out.println("[" + channel + "] Batch finalized | batchId: " + batch.getBatchId() + " | status: "
				+ batch.getBatchStatus() + " | settled: " + settled + " | failed: " + failed + " | totalAmount: "
				+ batch.getTotalAmount());
	}

	/**
	 * Adds amount to an account's balance (CREDIT). Skips if accountId is null or
	 * 0.
	 */
	private void creditBalance(Long accountId, BigDecimal amount) {
		if (accountId == null || accountId == 0L)
			return;
		Connection conn = null;
		PreparedStatement ps = null;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_CREDIT_BALANCE);
			ps.setBigDecimal(1, amount);
			ps.setLong(2, accountId);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException("creditBalance() failed for accountId: " + accountId + " | " + e.getMessage(),
					e);
		} finally {
			closeResources(null, ps, conn);
		}
	}

	/**
	 * Subtracts amount from an account's balance (DEBIT). Skips if accountId is
	 * null or 0.
	 */
	private void debitBalance(Long accountId, BigDecimal amount) {
		if (accountId == null || accountId == 0L)
			return;
		Connection conn = null;
		PreparedStatement ps = null;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_DEBIT_BALANCE);
			ps.setBigDecimal(1, amount);
			ps.setLong(2, accountId);
			ps.executeUpdate();
		} catch (SQLException e) {
			throw new RuntimeException("debitBalance() failed for accountId: " + accountId + " | " + e.getMessage(), e);
		} finally {
			closeResources(null, ps, conn);
		}
	}

	/** Returns the current balance for a given accountId. */
	private BigDecimal getBalance(Long accountId) {
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_GET_BALANCE);
			ps.setLong(1, accountId);
			rs = ps.executeQuery();
			if (rs.next())
				return rs.getBigDecimal("balance");
			throw new RuntimeException("Account not found for accountId: " + accountId);
		} catch (SQLException e) {
			throw new RuntimeException("getBalance() failed for accountId: " + accountId + " | " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	/**
	 * Looks up the incoming_txn_id from incoming_transaction table using the
	 * source_ref (which equals reference_number in transaction tables). Returns 0L
	 * if not found.
	 */
	private Long findIncomingTxnId(String sourceRef) {
		if (sourceRef == null)
			return 0L;
		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_INCOMING_TXN_ID);
			ps.setString(1, sourceRef);
			rs = ps.executeQuery();
			if (rs.next())
				return rs.getLong("incoming_txn_id");
			return 0L;
		} catch (SQLException e) {
			System.out.println(
					"[SettlementEngine] Could not find incoming_txn_id for ref: " + sourceRef + " | " + e.getMessage());
			return 0L;
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	/** Builds batch ID: BATCH-{CHANNEL}-{YYYYMMDD}. Example: BATCH-CBS-20260406 */
	private String buildBatchId(String channel) {
		String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		return "BATCH-" + channel + "-" + date;
	}

	// -----------------------------------------------------------------------
	// Row mappers — convert ResultSet rows to entity objects
	// -----------------------------------------------------------------------

	private CreditTransaction mapCreditRow(ResultSet rs) throws SQLException {
		CreditTransaction txn = new CreditTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setCreditAccountNumber(rs.getString("credit_account_number"));
		txn.setAmount(rs.getBigDecimal("amount"));
		txn.setCurrency(rs.getString("currency"));
		txn.setValueDate(rs.getDate("value_date").toLocalDate());
		txn.setReferenceNumber(rs.getString("reference_number"));
		return txn;
	}

	private DebitTransaction mapDebitRow(ResultSet rs) throws SQLException {
		DebitTransaction txn = new DebitTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setDebitAccountNumber(rs.getString("debit_account_number"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setAmount(rs.getBigDecimal("amount"));
		txn.setCurrency(rs.getString("currency"));
		txn.setValueDate(rs.getDate("value_date").toLocalDate());
		txn.setReferenceNumber(rs.getString("reference_number"));
		return txn;
	}

	private InterBankTransaction mapInterbankRow(ResultSet rs) throws SQLException {
		InterBankTransaction txn = new InterBankTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setCorrespondentBankCode(rs.getString("correspondent_bank_code"));
		txn.setAmount(rs.getBigDecimal("amount"));
		txn.setCurrency(rs.getString("currency"));
		txn.setValueDate(rs.getDate("value_date").toLocalDate());
		txn.setReferenceNumber(rs.getString("reference_number"));
		return txn;
	}

	private ReversalTransaction mapReversalRow(ResultSet rs) throws SQLException {
		ReversalTransaction txn = new ReversalTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setOriginalTxnRef(rs.getString("original_txn_ref"));
		txn.setReversalReason(rs.getString("reversal_reason"));
		txn.setAmount(rs.getBigDecimal("amount"));
		txn.setCurrency(rs.getString("currency"));
		txn.setValueDate(rs.getDate("value_date").toLocalDate());
		txn.setReferenceNumber(rs.getString("reference_number"));
		return txn;
	}

	// -----------------------------------------------------------------------
	// Close JDBC resources safely
	// -----------------------------------------------------------------------

	private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
		try {
			if (rs != null)
				rs.close();
		} catch (SQLException ignored) {
		}
		try {
			if (ps != null)
				ps.close();
		} catch (SQLException ignored) {
		}
		try {
			if (conn != null)
				conn.close();
		} catch (SQLException ignored) {
		}
	}
}