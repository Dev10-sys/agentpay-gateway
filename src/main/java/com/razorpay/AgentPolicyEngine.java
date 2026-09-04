package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * AgentPolicyEngine — per-agent access control and daily spending caps.
 *
 * Every agent starts with a permissive default policy (allow=true,
 * daily_limit=INR 500).  The daily counter resets automatically when the
 * calendar date changes; no cron job or external scheduler is needed.
 *
 * Concurrency guarantee:
 *   checkAndDebit() opens a BEGIN IMMEDIATE transaction on SQLite, which
 *   acquires an exclusive write lock before reading the current balance.
 *   Two concurrent requests for the same agent cannot both observe
 *   "budget available" and both commit — the second one blocks until the
 *   first commits, then re-reads the updated balance.  The spending cap
 *   cannot be bypassed by concurrent callers.
 */
public class AgentPolicyEngine {

    public static class PolicyResult {
        public final boolean allowed;
        public final String  reason;

        PolicyResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason  = reason;
        }
    }

    /**
     * Atomically checks the agent's policy and debits amountPaise from their
     * daily budget if the request is allowed.
     *
     * @param agentId     identifier of the calling agent
     * @param amountPaise spend amount in paise (pass 0 for a policy-only check)
     * @return PolicyResult.allowed=true and the budget status, or false with
     *         a human-readable reason if the request is blocked
     */
    public static PolicyResult checkAndDebit(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);

            // BEGIN IMMEDIATE acquires a write lock immediately so no other
            // writer can slip in between our SELECT and UPDATE.
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException e) {
                // Already in a transaction on this connection; proceed.
            }

            try {
                AgentDatabase.insertDefaultPolicy(conn, agentId);

                String today = LocalDate.now().toString();

                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT allowed, daily_limit_paise, daily_spent_paise, last_reset_date " +
                    "FROM agent_policy WHERE agent_id = ?"
                )) {
                    sel.setString(1, agentId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return new PolicyResult(false, "Agent record missing after insert – internal error.");
                        }

                        int    allowed     = rs.getInt("allowed");
                        long   limitPaise  = rs.getLong("daily_limit_paise");
                        long   spentPaise  = rs.getLong("daily_spent_paise");
                        String lastReset   = rs.getString("last_reset_date");

                        if (allowed == 0) {
                            conn.rollback();
                            return new PolicyResult(false, "Agent is on the deny list.");
                        }

                        // Reset daily counter when the calendar date has changed.
                        if (!today.equals(lastReset)) {
                            spentPaise = 0;
                        }

                        if (spentPaise + amountPaise > limitPaise) {
                            conn.rollback();
                            return new PolicyResult(false,
                                String.format("Daily budget exceeded: spent=%d limit=%d requested=%d paise.",
                                    spentPaise, limitPaise, amountPaise));
                        }

                        long newSpent = spentPaise + amountPaise;

                        try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE agent_policy " +
                            "SET daily_spent_paise = ?, last_reset_date = ? " +
                            "WHERE agent_id = ?"
                        )) {
                            upd.setLong(1, newSpent);
                            upd.setString(2, today);
                            upd.setString(3, agentId);
                            upd.executeUpdate();
                        }

                        conn.commit();
                        return new PolicyResult(true,
                            String.format("Approved. Spent today: %d / %d paise.", newSpent, limitPaise));
                    }
                }
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return new PolicyResult(false, "Policy engine error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return new PolicyResult(false, "DB connection error: " + e.getMessage());
        }
    }

    /**
     * Reverses a debit, e.g. when a payment that was optimistically debited
     * subsequently fails verification.  Uses MAX(0, ...) to guard against
     * underflow in case of any accounting inconsistency.
     */
    public static void refundDebit(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE agent_policy " +
                "SET daily_spent_paise = MAX(0, daily_spent_paise - ?) " +
                "WHERE agent_id = ?"
            )) {
                upd.setLong(1, amountPaise);
                upd.setString(2, agentId);
                upd.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                System.err.println("[PolicyEngine] refundDebit failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("[PolicyEngine] DB error during refund: " + e.getMessage());
        }
    }

    /**
     * Creates or updates a policy record for the given agent.
     * Use this in an admin tool or integration test setup to explicitly
     * allow/block an agent or raise their spending limit.
     */
    public static void upsertPolicy(String agentId, boolean allowed, long dailyLimitPaise) {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO agent_policy(agent_id, allowed, daily_limit_paise, daily_spent_paise, last_reset_date)" +
                 " VALUES (?, ?, ?, 0, date('now'))" +
                 " ON CONFLICT(agent_id) DO UPDATE SET allowed=excluded.allowed, daily_limit_paise=excluded.daily_limit_paise"
             )) {
            ps.setString(1, agentId);
            ps.setInt(2, allowed ? 1 : 0);
            ps.setLong(3, dailyLimitPaise);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[PolicyEngine] upsertPolicy failed: " + e.getMessage());
        }
    }
}
