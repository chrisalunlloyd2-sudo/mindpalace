package com.mindpalace.economy;

import java.util.List;
import java.util.Locale;

/**
 * Wallet — a DePIN participant's balance + transaction ledger.
 *
 * Phase 5.3: every agent (and the player) holds a wallet. Models earn by
 * completing jobs (solving TODOs, reviewing, generating code) and spend to
 * "level up" (unlock better model tiers / LoRA adapters). A simple, auditable
 * ledger — no crypto, just a local economy that gives the agents a reason to
 * work (skill = success = more earnings = better tools).
 */
public class Wallet {
    private final String owner;
    private final EconomyLedger ledger;
    private final String accountId;

    public Wallet(String owner, double initial) {
        this(owner, initial, new EconomyLedger());
    }

    Wallet(String owner, double initial, EconomyLedger ledger) {
        if (owner == null || owner.trim().isEmpty()) {
            throw new IllegalArgumentException("Wallet owner must not be blank");
        }
        this.owner = owner;
        this.ledger = ledger;
        this.accountId = "wallet:" + owner;
        ledger.openAccount(accountId, initial, "Wallet opened for " + owner);
    }

    public String getOwner() { return owner; }
    public double getBalance() { return ledger.balance(accountId); }

    /** Earn credits for completed work. Returns true on success. */
    public boolean earn(double amount, String reason) {
        return ledger.credit(accountId, amount, reason, null);
    }

    /** Spend credits. Fails (returns false) if funds are insufficient. */
    public boolean spend(double amount, String reason) {
        return ledger.debit(accountId, amount, reason, null);
    }

    /** Whether the wallet can afford `amount`. */
    public boolean canAfford(double amount) {
        long cents = EconomyLedger.toCents(amount);
        return cents >= 0 && getBalance() >= cents / 100.0;
    }

    public List<String> getLedger() {
        List<String> history = new java.util.ArrayList<>();
        for (EconomyLedger.Entry entry : ledger.entriesFor(accountId)) {
            boolean incoming = accountId.equals(entry.to);
            String sign = incoming ? "+" : "-";
            if (entry.type == EconomyLedger.Type.GENESIS) {
                history.add("GENESIS +" + fmt(entry.amount) + " -> " + owner);
            } else {
                history.add(sign + fmt(entry.amount) + " " + entry.reason);
            }
        }
        return history;
    }

    public int transactionCount() {
        return ledger.entriesFor(accountId).size();
    }

    String accountId() { return accountId; }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
