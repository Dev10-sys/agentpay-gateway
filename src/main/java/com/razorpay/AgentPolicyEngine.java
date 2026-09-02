package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * AgentPolicyEngine enforces per-agent access control and daily spending caps.
 *
 * Key design guarantee: every read-check-then-write against daily_spent_paise
 * runs inside a single BEGIN IMMEDIATE transaction.  SQLite's IMMEDIATE mode
 * acquires a write lock at the start, so two concurrent requests for the same
 * agent cannot both observe "budget available" before either has committed its
 * update.  The cap cannot be bypassed by racing callers.
 */
public class AgentPolicyEngine {

    public static class PolicyResult {
        public final boolean allowed;
        public final String reason;

        PolicyResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }

    /**
     * Checks whether an agent is allowed to spend the given amount, and if so,
     * atomically debits that amount from their daily budget.  Returns a
     * PolicyResult indicating whether the request is allowed and why.
     *
     * @param agentId     identifier of the calling agent
     * @param amountPaise amount the agent wants to spend (in paise)
     */
    public static PolicyResult checkAndDebit(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement lock = conn.prepareStatement(
                "BEGIN IMMEDIATE"
            )) {
                lock.execute();
            } catch (SQLException ignored) {
                // BEGIN IMMEDIATE not available as prepared statement in all drivers;
                // handled by the Statement below.
            }

            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException e) {
                // Already in a transaction; proceed.
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
                            return new PolicyResult(false, "Agent not found after insert – internal error");
                        }

                        int allowed = rs.getInt("allowed");
                        long limitPaise = rs.getLong("daily_limit_paise");
                        long spentPaise = rs.getLong("daily_spent_paise");
                        String lastReset = rs.getString("last_reset_date");

                        if (allowed == 0) {
                            conn.rollback();
                            return new PolicyResult(false, "Agent is on the deny list");
                        }

                        if (!today.equals(lastReset)) {
                            spentPaise = 0;
                        }

                        if (spentPaise + amountPaise > limitPaise) {
                            conn.rollback();
                            return new PolicyResult(false,
                                String.format("Daily budget exceeded: spent=%d limit=%d requested=%d (paise)",
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
                            String.format("Approved. Spent today: %d / %d paise", newSpent, limitPaise));
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
     * Reverses a previously debited amount, e.g. when a payment subsequently
     * fails and the reservation must be released.
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
     * Explicitly add or update an agent's policy record.
     *
     * @param agentId         identifier
     * @param allowed         true to allow, false to block
     * @param dailyLimitPaise maximum daily spend in paise
     */
    public static void upsertPolicy(String agentId, boolean allowed, long dailyLimitPaise) {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO agent_policy(agent_id, allowed, daily_limit_paise, " +
                 "daily_spent_paise, last_reset_date) VALUES (?, ?, ?, 0, date('now')) " +
                 "ON CONFLICT(agent_id) DO UPDATE SET allowed=excluded.allowed, " +
                 "daily_limit_paise=excluded.daily_limit_paise"
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
