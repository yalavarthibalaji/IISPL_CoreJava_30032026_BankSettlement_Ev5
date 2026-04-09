package com.iispl.banksettlement.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

import com.iispl.banksettlement.dao.CreditTransactionDao;
import com.iispl.banksettlement.dao.DebitTransactionDao;
import com.iispl.banksettlement.dao.InterBankTransactionDao;
import com.iispl.banksettlement.dao.ReversalTransactionDao;
import com.iispl.banksettlement.dao.SettlementBatchDao;
import com.iispl.banksettlement.dao.impl.CreditTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.DebitTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.InterBankTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.ReversalTransactionDaoImpl;
import com.iispl.banksettlement.dao.impl.SettlementBatchDaoImpl;
import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.banksettlement.entity.SettlementBatch;
import com.iispl.banksettlement.entity.SettlementRecord;
import com.iispl.banksettlement.enums.BatchStatus;
import com.iispl.banksettlement.enums.SettlementStatus;
import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.utility.PhaseLogger;

public class ChannelSettler {
    private static final Logger LOGGER = Logger.getLogger(ChannelSettler.class.getName());

    private final SettlementBatchDao batchDao;
    private final CreditTransactionDao creditDao;
    private final DebitTransactionDao debitDao;
    private final InterBankTransactionDao interbankDao;
    private final ReversalTransactionDao reversalDao;

    public ChannelSettler() {
        this.batchDao = new SettlementBatchDaoImpl();
        this.creditDao = new CreditTransactionDaoImpl();
        this.debitDao = new DebitTransactionDaoImpl();
        this.interbankDao = new InterBankTransactionDaoImpl();
        this.reversalDao = new ReversalTransactionDaoImpl();
        PhaseLogger.configureReadableConsole(LOGGER);
    }

    // -----------------------------------------------------------------------
    // CBS — Direct settlement per transaction
    // -----------------------------------------------------------------------

    public void settleCbsBatch(List<SettlementItem> items) {

        LOGGER.info("[CBS] Settling " + items.size() + " transactions.");

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
                    creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[CBS] CREDIT settled | ref: " + txn.getReferenceNumber() + " | amount: " + txn.getAmount());

                } else if ("DEBIT".equals(item.getTxnType())) {
                    DebitTransaction txn = item.getDebit();
                    validateAmount(txn.getAmount(), txn.getReferenceNumber());
                    debitDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[CBS] DEBIT settled | ref: " + txn.getReferenceNumber() + " | amount: " + txn.getAmount());
                }

            } catch (Exception e) {
                failed++;
                handleFailure(item, batchId, batch, e.getMessage());
            }
        }

        finalizeBatch(batch, settled, failed, "CBS");
    }

    // -----------------------------------------------------------------------
    // RTGS — Gross settlement
    // -----------------------------------------------------------------------

    public void settleRtgsBatch(List<SettlementItem> items) {

        LOGGER.info("[RTGS] Settling " + items.size() + " interbank transactions (gross).");

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

                interbankDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                        SettlementStatus.SETTLED, null);
                batchDao.saveRecord(rec);
                batch.addRecord(rec);
                settled++;
                LOGGER.fine("[RTGS] GROSS settled | ref: " + txn.getReferenceNumber() + " | amount: " + txn.getAmount()
                        + " | correspondent: " + txn.getCorrespondentBankCode());

            } catch (Exception e) {
                failed++;
                handleFailure(item, batchId, batch, e.getMessage());
            }
        }

        finalizeBatch(batch, settled, failed, "RTGS");
    }

    // -----------------------------------------------------------------------
    // NEFT — NET settlement
    // -----------------------------------------------------------------------

    public void settleNeftBatch(List<SettlementItem> items) {

        LOGGER.info("[NEFT] Settling " + items.size() + " transactions (net).");

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

        LOGGER.info("[NEFT] Netting summary -> Gross Credits: " + totalCredits + ", Gross Debits: " + totalDebits
                + ", Net Amount: " + netAmount
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
                    creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[NEFT] CREDIT recorded (net) | ref: " + txn.getReferenceNumber() + " | amount: "
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
                    LOGGER.fine("[NEFT] DEBIT recorded (net) | ref: " + txn.getReferenceNumber() + " | amount: "
                            + txn.getAmount());
                }
            } catch (Exception e) {
                failed++;
                handleFailure(item, batchId, batch, e.getMessage());
            }
        }

        // Print final net position
        LOGGER.info("[NEFT] Net settlement applied. Net amount: " + netAmount
                + (netAmount.compareTo(BigDecimal.ZERO) >= 0 ? " → Bank receives this net amount via RBI clearing"
                        : " → Bank pays this net amount via RBI clearing"));

        finalizeBatch(batch, settled, failed, "NEFT");
    }

    // -----------------------------------------------------------------------
    // UPI — VPA-based settlement
    // -----------------------------------------------------------------------

    public void settleUpiBatch(List<SettlementItem> items) {

        LOGGER.info("[UPI] Settling " + items.size() + " VPA transactions (no balance update).");

        String batchId = buildBatchId("UPI");
        SettlementBatch batch = new SettlementBatch(batchId, LocalDate.now(), "SETTLEMENT_ENGINE");
        batch.setBatchStatus(BatchStatus.RUNNING);
        batchDao.saveBatch(batch);

        int settled = 0;
        int failed = 0;

        for (SettlementItem item : items) {
            try {
                CreditTransaction txn = item.getCredit();
                validateAmount(txn.getAmount(), txn.getReferenceNumber());
                creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                        SettlementStatus.SETTLED, null);
                batchDao.saveRecord(rec);
                batch.addRecord(rec);
                settled++;
                LOGGER.fine("[UPI] Settled (VPA, no balance update) | ref: " + txn.getReferenceNumber() + " | amount: "
                        + txn.getAmount());

            } catch (Exception e) {
                failed++;
                handleFailure(item, batchId, batch, e.getMessage());
            }
        }

        finalizeBatch(batch, settled, failed, "UPI");
    }

    // -----------------------------------------------------------------------
    // FT (FinTech) — Best effort settlement
    // -----------------------------------------------------------------------

    public void settleFtBatch(List<SettlementItem> items) {

        LOGGER.info("[FT] Settling " + items.size() + " FinTech transactions (best effort).");

        String batchId = buildBatchId("FT");
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
                    creditDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[FT] CREDIT settled | ref: " + txn.getReferenceNumber());

                } else if ("DEBIT".equals(item.getTxnType())) {
                    DebitTransaction txn = item.getDebit();
                    validateAmount(txn.getAmount(), txn.getReferenceNumber());
                    debitDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[FT] DEBIT settled | ref: " + txn.getReferenceNumber());

                } else if ("REVERSAL".equals(item.getTxnType())) {
                    ReversalTransaction txn = item.getReversal();
                    validateAmount(txn.getAmount(), txn.getReferenceNumber());
                    reversalDao.updateStatus(txn.getTxnId(), TransactionStatus.SETTLED.name());

                    SettlementRecord rec = new SettlementRecord(batchId, item.getIncomingTxnId(), txn.getAmount(),
                            SettlementStatus.SETTLED, null);
                    batchDao.saveRecord(rec);
                    batch.addRecord(rec);
                    settled++;
                    LOGGER.fine("[FT] REVERSAL settled | ref: " + txn.getReferenceNumber() + " | originalRef: "
                            + txn.getOriginalTxnRef());
                }

            } catch (Exception e) {
                failed++;
                handleFailure(item, batchId, batch, e.getMessage());
            }
        }

        finalizeBatch(batch, settled, failed, "FT");
    }

    /**
     * Validates that the amount is not null and is greater than zero.
     */
    private void validateAmount(BigDecimal amount, String ref) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new RuntimeException("Invalid amount: " + amount + " for ref: " + ref);
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

        LOGGER.warning("[SettlementEngine] FAILED | channel: " + item.getChannel() + " | type: " + item.getTxnType()
                + " | ref: " + ref + " | reason: " + reason);

        try {
            Long incomingTxnId = (item.getIncomingTxnId() != null) ? item.getIncomingTxnId() : 0L;
            SettlementRecord rec = new SettlementRecord(batchId, incomingTxnId,
                    (amount != null ? amount : BigDecimal.ZERO), SettlementStatus.FAILED, reason);
            batchDao.saveRecord(rec);
            batch.addRecord(rec);
        } catch (Exception e) {
            LOGGER.warning("[SettlementEngine] Could not save FAILED record: " + e.getMessage());
        }
    }

    /**
     * Decides final BatchStatus, updates batch in DB, and prints summary.
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
        LOGGER.info("[" + channel + "] Batch finalized | batchId: " + batch.getBatchId() + " | status: "
                + batch.getBatchStatus() + " | settled: " + settled + " | failed: " + failed + " | totalAmount: "
                + batch.getTotalAmount());
    }

    /**
     * Builds batch ID: BATCH-{CHANNEL}-{YYYYMMDD}
     */
    private String buildBatchId(String channel) {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "BATCH-" + channel + "-" + date;
    }
}