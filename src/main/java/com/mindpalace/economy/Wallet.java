package com.mindpalace.economy;

import java.util.ArrayList;
import java.util.List;

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
    private double balance;
    private final List<String> ledger = new ArrayList<>();

    public Wallet(String owner, double initial) {
        this.owner = owner;
        this.balance = initial;
        ledger.add("GENESIS +" + fmt(initial) + " -> " + owner);
    }

    public String getOwner() { return owner; }
    public double getBalance() { return balance; }

    /** Earn credits for completed work. Returns true on success. */
    public synchronized boolean earn(double amount, String reason) {
        if (amount <= 0) return false;
        balance += amount;
        ledger.add("+" + fmt(amount) + " " + reason);
        return true;
    }

    /** Spend credits. Fails (returns false) if funds are insufficient. */
    public synchronized boolean spend(double amount, String reason) {
        if (amount <= 0 || amount > balance) return false;
        balance -= amount;
        ledger.add("-" + fmt(amount) + " " + reason);
        return true;
    }

    /** Whether the wallet can afford `amount`. */
    public synchronized boolean canAfford(double amount) { return amount >= 0 && balance >= amount; }

    public synchronized List<String> getLedger() { return new ArrayList<>(ledger); }
    public synchronized int transactionCount() { return ledger.size(); }

    private static String fmt(double v) { return String.format("%.2f", v); }
}
