package com.iispl.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
//
/**
 * Npci — Represents NPCI (National Payments Corporation of India).
 *
 * NPCI is the central clearing house. It:
 *   1. HAS-A List<NpciMemberAccount> — one account per member bank.
 *   2. Applies netting positions to update each bank's account balance.
 *
 * IMPORTANT:
 *   - This entity is NOT stored in the DB as a table.
 *   - It is a Java object that holds the loaded NpciMemberAccount list in memory.
 *   - The NpciMemberAccount objects are loaded from and saved to npci_bank_account table.
 *
 * HOW IT WORKS IN CODE:
 *   NettingServiceImpl loads all NpciMemberAccount rows from DB into this object.
 *   Then it calls applyNettingPositions() to update the balances.
 *   Then it saves the updated NpciMemberAccount rows back to DB.
 */
public class Npci {

    // The name of the clearing house
    private String name;

    // HAS-A composition: NPCI holds settlement accounts for all 5 member banks
    private List<NpciMemberAccount> memberAccounts;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    public Npci() {
        this.name = "NPCI - National Payments Corporation of India";
    }

    public Npci(List<NpciMemberAccount> memberAccounts) {
        this.name           = "NPCI - National Payments Corporation of India";
        this.memberAccounts = memberAccounts;
    }

    // -----------------------------------------------------------------------
    // Core method: apply netting positions to update bank balances
    // -----------------------------------------------------------------------

    /**
     * Applies all netting positions to update the NPCI member account balances
     * in memory. After this method, call the DAO to persist the updated balances.
     *
     * For each NettingPosition:
     *   fromBank's balance is DEBITED  (they pay net amount)
     *   toBank's  balance is CREDITED  (they receive net amount)
     *
     * Only NET_DEBIT positions are processed here. Each NET_DEBIT means:
     *   fromBank pays netAmount to toBank.
     *
     * @param positions List of NettingPosition computed by NettingEngine
     */
    public void applyNettingPositions(List<NettingPosition> positions) {

        System.out.println("\n================================================");
        System.out.println("  NPCI — APPLYING NETTING POSITIONS TO ACCOUNTS");
        System.out.println("  Date: " + LocalDate.now());
        System.out.println("================================================");

        if (positions == null || positions.isEmpty()) {
            System.out.println("[NPCI] No netting positions to apply.");
            return;
        }

        System.out.println("\n[NPCI] Pre-settlement balances:");
        printAllBalances();

        for (NettingPosition position : positions) {
            // Only apply NET_DEBIT positions (fromBank pays toBank)
            // NET_CREDIT means the opposite direction already covered
            if (position.getDirection() == com.iispl.enums.NetDirection.NET_DEBIT
                    && position.getNetAmount().compareTo(BigDecimal.ZERO) > 0) {

                NpciMemberAccount fromAccount = findByBankName(position.getFromBankName());
                NpciMemberAccount toAccount   = findByBankName(position.getToBankName());

                if (fromAccount == null) {
                    System.out.println("[NPCI] WARNING: NPCI account not found for bank: "
                            + position.getFromBankName() + " — skipping.");
                    continue;
                }
                if (toAccount == null) {
                    System.out.println("[NPCI] WARNING: NPCI account not found for bank: "
                            + position.getToBankName() + " — skipping.");
                    continue;
                }

                BigDecimal netAmt = position.getNetAmount().abs();

                fromAccount.debit(netAmt);
                toAccount.credit(netAmt);

                System.out.println("[NPCI] APPLIED: " + position.getFromBankName()
                        + " paid Rs. " + String.format("%,.2f", netAmt)
                        + " to " + position.getToBankName());
            }
        }

        System.out.println("\n[NPCI] Post-settlement balances:");
        printAllBalances();
        System.out.println("================================================\n");
    }

    // -----------------------------------------------------------------------
    // Helper: find a member account by bank name
    // -----------------------------------------------------------------------

    /**
     * Finds an NpciMemberAccount by the bank's name.
     * Returns null if not found.
     */
    public NpciMemberAccount findByBankName(String bankName) {
        if (bankName == null || memberAccounts == null) return null;
        for (NpciMemberAccount account : memberAccounts) {
            if (bankName.equalsIgnoreCase(account.getBankName())) {
                return account;
            }
        }
        return null;
    }

    /**
     * Prints all member account balances in a neat table.
     */
    public void printAllBalances() {
        System.out.println(String.format("  %-20s  %20s  %20s", "Bank", "Opening Balance", "Current Balance"));
        System.out.println("  " + "-".repeat(65));
        if (memberAccounts != null) {
            for (NpciMemberAccount account : memberAccounts) {
                System.out.println(String.format("  %-20s  %20.2f  %20.2f",
                        account.getBankName(),
                        account.getOpeningBalance(),
                        account.getCurrentBalance()));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<NpciMemberAccount> getMemberAccounts() { return memberAccounts; }
    public void setMemberAccounts(List<NpciMemberAccount> memberAccounts) {
        this.memberAccounts = memberAccounts;
    }
}