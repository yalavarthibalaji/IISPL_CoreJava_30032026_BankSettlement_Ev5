package com.iispl.banksettlement.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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
import com.iispl.banksettlement.service.SettlementService;
import com.iispl.banksettlement.settlement.ChannelSettler;
import com.iispl.banksettlement.settlement.SettlementItem;
import com.iispl.banksettlement.settlement.SettlementQueueLoader;

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
                channelSettler.settleCbsBatch(cbsItems);
            if (!rtgsItems.isEmpty())
                channelSettler.settleRtgsBatch(rtgsItems);
            if (!neftItems.isEmpty())
                channelSettler.settleNeftBatch(neftItems);
            if (!upiItems.isEmpty())
                channelSettler.settleUpiBatch(upiItems);
            if (!ftItems.isEmpty())
                channelSettler.settleFtBatch(ftItems);

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