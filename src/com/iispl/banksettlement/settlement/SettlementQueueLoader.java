package com.iispl.banksettlement.settlement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;

import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.connectionpool.ConnectionPool;

public class SettlementQueueLoader {

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

	// SQL for looking up incoming_txn_id by source_ref (reference_number)
	private static final String SQL_FIND_INCOMING_TXN_ID = "SELECT incoming_txn_id FROM incoming_transaction WHERE source_ref = ?";

	public int loadAllTransactionsIntoQueue(BlockingQueue<SettlementItem> queue) {

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

	public int loadCreditsForChannel(BlockingQueue<SettlementItem> queue, String prefix, String channel) {
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

	public int loadDebitsForChannel(BlockingQueue<SettlementItem> queue, String prefix, String channel) {
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

	public int loadInterbankTransactions(BlockingQueue<SettlementItem> queue) {
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

	public int loadReversalTransactions(BlockingQueue<SettlementItem> queue) {
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

	/**
	 * Looks up the incoming_txn_id from incoming_transaction table using the
	 * source_ref (which equals reference_number in transaction tables). Returns 0L
	 * if not found.
	 */
	public Long findIncomingTxnId(String sourceRef) {
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

	// -----------------------------------------------------------------------
	// Row mappers — convert ResultSet rows to entity objects
	// -----------------------------------------------------------------------

	public CreditTransaction mapCreditRow(ResultSet rs) throws SQLException {
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

	public DebitTransaction mapDebitRow(ResultSet rs) throws SQLException {
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

	public InterBankTransaction mapInterbankRow(ResultSet rs) throws SQLException {
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

	public ReversalTransaction mapReversalRow(ResultSet rs) throws SQLException {
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
