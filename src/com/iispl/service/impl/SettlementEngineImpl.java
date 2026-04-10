package com.iispl.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import com.iispl.dao.CreditTransactionDao;
import com.iispl.dao.DebitTransactionDao;
import com.iispl.dao.InterBankTransactionDao;
import com.iispl.dao.ReversalTransactionDao;
import com.iispl.dao.SettlementBatchDao;
import com.iispl.dao.impl.CreditTransactionDaoImpl;
import com.iispl.dao.impl.DebitTransactionDaoImpl;
import com.iispl.dao.impl.InterBankTransactionDaoImpl;
import com.iispl.dao.impl.ReversalTransactionDaoImpl;
import com.iispl.dao.impl.SettlementBatchDaoImpl;
import com.iispl.service.SettlementService;
import com.iispl.settlement.ChannelSettler;
import com.iispl.settlement.SettlementItem;
import com.iispl.settlement.SettlementQueueLoader;
import com.iispl.utility.PhaseLogger;

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
public class SettlementEngineImpl implements SettlementService {

    // -----------------------------------------------------------------------
    // DAOs
    // -----------------------------------------------------------------------

    private final SettlementBatchDao batchDao;
    private final CreditTransactionDao creditDao;
    private final DebitTransactionDao debitDao;
    private final InterBankTransactionDao interbankDao;
    private final ReversalTransactionDao reversalDao;
    private final SettlementQueueLoader queueLoader;
    private final ChannelSettler channelSettler;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public SettlementEngineImpl() {
        this.batchDao = new SettlementBatchDaoImpl();
        this.creditDao = new CreditTransactionDaoImpl();
        this.debitDao = new DebitTransactionDaoImpl();
        this.interbankDao = new InterBankTransactionDaoImpl();
        this.reversalDao = new ReversalTransactionDaoImpl();
        this.queueLoader = new SettlementQueueLoader();
        this.channelSettler = new ChannelSettler();
    }

    // -----------------------------------------------------------------------
    // runSettlement() — MAIN ENTRY POINT
    // -----------------------------------------------------------------------

    /**
     * Full settlement pipeline: Phase 1 → load all INITIATED transactions → put on
     * BlockingQueue Phase 2 → consumer thread drains queue → groups by channel
     * Phase 3 → settle each channel batch
     */
    @Override
    public void runSettlement() {
        Logger logger = PhaseLogger.getLogger();
        logger.info("Settlement engine started on " + LocalDate.now());

        // ----------------------------------------------------------------
        // PHASE 1 — PRODUCER: Load all INITIATED transactions from DB
        // and put them onto the BlockingQueue as SettlementItems
        // ----------------------------------------------------------------

        // Queue capacity 1000 — adjust if you have more transactions
        BlockingQueue<SettlementItem> queue = new LinkedBlockingQueue<>(1000);

        int totalLoaded = loadAllTransactionsIntoQueue(queue);

        if (totalLoaded == 0) {
            logger.info("No INITIATED transactions found in DB. Run settlement processor first.");
            return;
        }

        logger.info("Phase 1 complete. Loaded " + totalLoaded + " transactions onto queue.");

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

            logger.info("Phase 2 started. Consumer thread: " + Thread.currentThread().getName());

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
                        logger.info("Shutdown sentinel received. Queue drained. Starting settlement.");
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
                        logger.warning("Unknown channel: " + item.getChannel() + ". Skipping item.");
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warning("Consumer interrupted.");
                    break;
                }
            }

            logger.info("Phase 2 complete. Bucket sizes -> CBS: " + cbsItems.size() + ", RTGS: " + rtgsItems.size()
                    + ", NEFT: " + neftItems.size() + ", UPI: " + upiItems.size() + ", FT: " + ftItems.size());

            // ----------------------------------------------------------------
            // PHASE 3 — SETTLE each channel bucket
            // ----------------------------------------------------------------
            logger.info("Phase 3 started. Running channel settlement.");

            if (!cbsItems.isEmpty())
                channelSettler.settleCbsBatch(cbsItems);
            if (!rtgsItems.isEmpty())
                channelSettler.settleRtgsBatch(rtgsItems);
            if (!neftItems.isEmpty())
                channelSettler.settleNeftBatch(neftItems);
            if (!upiItems.isEmpty())
                channelSettler.settleUpiBatch(upiItems);
            if (!ftItems.isEmpty())
                channelSettler.settleFtBatch(ftItems);

            logger.info("Phase 3 complete. All channels settled.");
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

        logger.info("Settlement engine completed.");
    }

    /**
     * Loads all INITIATED transactions from all 4 tables. Wraps each into a
     * SettlementItem with the correct channel. Puts all items onto the queue.
     *
     * @return total number of items put on the queue
     */
    private int loadAllTransactionsIntoQueue(BlockingQueue<SettlementItem> queue) {
        return queueLoader.loadAllTransactionsIntoQueue(queue);
    }
}