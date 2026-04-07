package com.iispl.banksettlement.settlement;

import com.iispl.banksettlement.entity.CreditTransaction;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.entity.ReversalTransaction;

/**
 * SettlementItem — A wrapper that holds ONE transaction of any type
 * along with the channel it came from.
 *
 * WHY THIS CLASS EXISTS:
 *   The BlockingQueue in SettlementEngine needs to hold different
 *   transaction types together:
 *     CreditTransaction, DebitTransaction,
 *     InterBankTransaction, ReversalTransaction
 *
 *   Java's BlockingQueue is typed — it can hold only ONE type.
 *   Solution: wrap all transaction types inside this single class
 *   and put SettlementItem objects on the queue.
 *
 *   The consumer then checks getChannel() to decide which batch
 *   this item belongs to, and checks getTxnType() to know which
 *   field to use (getCredit(), getDebit(), getInterbank(), getReversal()).
 *
 * HOW TO USE:
 *   // Producer — wrapping a CreditTransaction for CBS channel:
 *   SettlementItem item = new SettlementItem("CBS", credit, null, null, null, incomingTxnId);
 *   queue.put(item);
 *
 *   // Consumer — unwrapping:
 *   if (item.getChannel().equals("CBS") && item.getCredit() != null) {
 *       CreditTransaction txn = item.getCredit();
 *   }
 *
 * CHANNEL VALUES (matches reference_number prefix):
 *   "CBS"    — Core Banking System
 *   "RTGS"   — Real-Time Gross Settlement
 *   "NEFT"   — National Electronic Funds Transfer
 *   "UPI"    — Unified Payments Interface
 *   "FT"     — FinTech / Third-party API
 *   "SHUTDOWN" — Sentinel signal to stop the consumer thread
 *
 * TXN_TYPE VALUES:
 *   "CREDIT"    — CreditTransaction
 *   "DEBIT"     — DebitTransaction
 *   "INTERBANK" — InterBankTransaction
 *   "REVERSAL"  — ReversalTransaction
 *   "SHUTDOWN"  — Sentinel
 *
 * PACKAGE: com.iispl.banksettlement.service
 */
public class SettlementItem {

    // Which payment channel this transaction came from
    // Values: "CBS", "RTGS", "NEFT", "UPI", "FT", "SHUTDOWN"
    private final String channel;

    // Which transaction type — one of these four will be non-null, rest will be null
    private final String txnType;   // "CREDIT", "DEBIT", "INTERBANK", "REVERSAL", "SHUTDOWN"

    // Exactly ONE of these four will be non-null per SettlementItem
    private final CreditTransaction    credit;
    private final DebitTransaction     debit;
    private final InterBankTransaction interbank;
    private final ReversalTransaction  reversal;

    // The incoming_txn_id from the incoming_transaction table.
    // Used when creating the SettlementRecord (FK: incoming_txn_id).
    private final Long incomingTxnId;

    // -----------------------------------------------------------------------
    // Constructor — private, use static factory methods below
    // -----------------------------------------------------------------------

    private SettlementItem(String channel, String txnType,
                           CreditTransaction credit,
                           DebitTransaction debit,
                           InterBankTransaction interbank,
                           ReversalTransaction reversal,
                           Long incomingTxnId) {
        this.channel      = channel;
        this.txnType      = txnType;
        this.credit       = credit;
        this.debit        = debit;
        this.interbank    = interbank;
        this.reversal     = reversal;
        this.incomingTxnId = incomingTxnId;
    }

    // -----------------------------------------------------------------------
    // Static factory methods — one per transaction type
    // -----------------------------------------------------------------------

    /**
     * Creates a SettlementItem wrapping a CreditTransaction.
     *
     * @param channel       "CBS", "NEFT", "UPI", or "FT"
     * @param credit        The CreditTransaction loaded from DB
     * @param incomingTxnId FK for settlement_record table
     */
    public static SettlementItem ofCredit(String channel,
                                          CreditTransaction credit,
                                          Long incomingTxnId) {
        return new SettlementItem(channel, "CREDIT", credit, null, null, null, incomingTxnId);
    }

    /**
     * Creates a SettlementItem wrapping a DebitTransaction.
     *
     * @param channel       "CBS", "NEFT", or "FT"
     * @param debit         The DebitTransaction loaded from DB
     * @param incomingTxnId FK for settlement_record table
     */
    public static SettlementItem ofDebit(String channel,
                                         DebitTransaction debit,
                                         Long incomingTxnId) {
        return new SettlementItem(channel, "DEBIT", null, debit, null, null, incomingTxnId);
    }

    /**
     * Creates a SettlementItem wrapping an InterBankTransaction.
     *
     * @param channel       "RTGS"
     * @param interbank     The InterBankTransaction loaded from DB
     * @param incomingTxnId FK for settlement_record table
     */
    public static SettlementItem ofInterbank(String channel,
                                              InterBankTransaction interbank,
                                              Long incomingTxnId) {
        return new SettlementItem(channel, "INTERBANK", null, null, interbank, null, incomingTxnId);
    }

    /**
     * Creates a SettlementItem wrapping a ReversalTransaction.
     *
     * @param channel       "FT"
     * @param reversal      The ReversalTransaction loaded from DB
     * @param incomingTxnId FK for settlement_record table
     */
    public static SettlementItem ofReversal(String channel,
                                             ReversalTransaction reversal,
                                             Long incomingTxnId) {
        return new SettlementItem(channel, "REVERSAL", null, null, null, reversal, incomingTxnId);
    }

    /**
     * Creates a shutdown sentinel item.
     * The consumer thread checks for this and stops its loop when it sees it.
     */
    public static SettlementItem shutdown() {
        return new SettlementItem("SHUTDOWN", "SHUTDOWN", null, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Helper — is this a shutdown sentinel?
    // -----------------------------------------------------------------------

    public boolean isShutdown() {
        return "SHUTDOWN".equals(channel);
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public String getChannel() {
        return channel;
    }

    public String getTxnType() {
        return txnType;
    }

    public CreditTransaction getCredit() {
        return credit;
    }

    public DebitTransaction getDebit() {
        return debit;
    }

    public InterBankTransaction getInterbank() {
        return interbank;
    }

    public ReversalTransaction getReversal() {
        return reversal;
    }

    public Long getIncomingTxnId() {
        return incomingTxnId;
    }

    // -----------------------------------------------------------------------
    // toString — useful for console logs
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        String ref = "?";
        if (credit   != null) ref = credit.getReferenceNumber();
        if (debit    != null) ref = debit.getReferenceNumber();
        if (interbank != null) ref = interbank.getReferenceNumber();
        if (reversal != null) ref = reversal.getReferenceNumber();
        return "SettlementItem{channel='" + channel + "', txnType='" + txnType
                + "', ref='" + ref + "', incomingTxnId=" + incomingTxnId + "}";
    }
}