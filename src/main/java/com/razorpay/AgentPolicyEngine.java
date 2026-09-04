package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/*
 * Per-agent access control and daily spend cap.
 *
 * checkAndDebit() runs inside BEGIN IMMEDIATE so two concurrent requests
 * for the same agent can't both read "budget available" before either commits.
 * Daily counter resets automatically when the date changes — no cron needed.
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

    public static PolicyResult checkAndDebit(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);

            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

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
                            return new PolicyResult(false, "Agent row missing after insert.");
                        }

                        int    allowed    = rs.getInt("allowed");
                        long   limit      = rs.getLong("daily_limit_paise");
                        long   spent      = rs.getLong("daily_spent_paise");
                        String lastReset  = rs.getString("last_reset_date");

                        if (allowed == 0) {
                            conn.rollback();
                            return new PolicyResult(false, "Agent is blocked.");
                        }

                        if (!today.equals(lastReset)) spent = 0; // new day — reset

                        if (spent + amountPaise > limit) {
                            conn.rollback();
                            return new PolicyResult(false,
                                String.format("Daily cap: spent=%d limit=%d requested=%d paise",
                                    spent, limit, amountPaise));
                        }

                        long newSpent = spent + amountPaise;
                        try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE agent_policy SET daily_spent_paise=?, last_reset_date=? WHERE agent_id=?"
                        )) {
                            upd.setLong(1, newSpent);
                            upd.setString(2, today);
                            upd.setString(3, agentId);
                            upd.executeUpdate();
                        }

                        conn.commit();
                        return new PolicyResult(true,
                            String.format("OK. Spent today: %d / %d paise", newSpent, limit));
                    }
                }
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                return new PolicyResult(false, "Policy error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return new PolicyResult(false, "DB error: " + e.getMessage());
        }
    }

    // Reverse a debit — called when a payment fails after the budget was reserved.
    public static void refundDebit(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            try (java.sql.Statement st = conn.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            } catch (SQLException ignored) {}

            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE agent_policy SET daily_spent_paise=MAX(0, daily_spent_paise-?) WHERE agent_id=?"
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

    public static void upsertPolicy(String agentId, boolean allowed, long dailyLimitPaise) {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO agent_policy(agent_id,allowed,daily_limit_paise,daily_spent_paise,last_reset_date)" +
                 " VALUES(?,?,?,0,date('now'))" +
                 " ON CONFLICT(agent_id) DO UPDATE SET allowed=excluded.allowed,daily_limit_paise=excluded.daily_limit_paise"
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
