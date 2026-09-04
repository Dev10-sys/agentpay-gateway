package com.razorpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/*
 * Per-agent access control and daily spend cap.
 *
 * Budget model (Fix #1):
 *   available = daily_limit_paise - daily_spent_paise - reserved_paise
 *
 *   reserve()           — at challenge time: check + hold the amount
 *   commitReservation() — after payment captured: move reserved → spent
 *   releaseReservation()— if payment fails/expires: free the hold
 *   checkAndDebit()     — direct debit used by the metering flow only
 *
 * All writes run inside BEGIN IMMEDIATE so concurrent callers for the same
 * agent see a consistent balance before any of them commits.
 */
public class AgentPolicyEngine {

    public static class PolicyResult {
        public final boolean allowed;
        public final String  reason;
        PolicyResult(boolean allowed, String reason) {
            this.allowed = allowed; this.reason = reason;
        }
    }

    // --- Fix #1: reserve at challenge time --------------------------------

    public static PolicyResult reserve(String agentId, long amountPaise) {
        if (amountPaise <= 0) return new PolicyResult(false, "Amount must be positive.");
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try {
                AgentDatabase.insertDefaultPolicy(conn, agentId);
                String today = LocalDate.now().toString();

                try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT allowed,daily_limit_paise,daily_spent_paise,reserved_paise,last_reset_date " +
                    "FROM agent_policy WHERE agent_id=?"
                )) {
                    sel.setString(1, agentId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) { conn.rollback(); return new PolicyResult(false, "Agent not found."); }

                        if (rs.getInt("allowed") == 0) { conn.rollback(); return new PolicyResult(false, "Agent blocked."); }

                        long limit    = rs.getLong("daily_limit_paise");
                        long spent    = rs.getLong("daily_spent_paise");
                        long reserved = rs.getLong("reserved_paise");
                        String last   = rs.getString("last_reset_date");

                        boolean isNewDay = !today.equals(last);
                        if (isNewDay) spent = 0; // new day

                        long available = limit - spent - reserved;
                        if (available < amountPaise) {
                            conn.rollback();
                            return new PolicyResult(false,
                                String.format("Budget: limit=%d spent=%d reserved=%d available=%d requested=%d paise",
                                    limit, spent, reserved, available, amountPaise));
                        }

                        try (PreparedStatement upd = conn.prepareStatement(
                            isNewDay
                                ? "UPDATE agent_policy SET daily_spent_paise=0,reserved_paise=reserved_paise+?,last_reset_date=? WHERE agent_id=?"
                                : "UPDATE agent_policy SET reserved_paise=reserved_paise+?,last_reset_date=? WHERE agent_id=?"
                        )) {
                            upd.setLong(1, amountPaise);
                            upd.setString(2, today);
                            upd.setString(3, agentId);
                            upd.executeUpdate();
                        }
                        conn.commit();
                        return new PolicyResult(true,
                            String.format("Reserved %d paise. Available: %d paise", amountPaise, available - amountPaise));
                    }
                }
            } catch (SQLException e) {
                rollback(conn); return new PolicyResult(false, "Policy error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return new PolicyResult(false, "DB error: " + e.getMessage());
        }
    }

    // Called after payment is confirmed captured — converts reservation to actual spend inside an existing transaction.
    public static boolean commitReservation(Connection conn, String agentId, long amountPaise) throws SQLException {
        if (amountPaise <= 0) return false;
        try (PreparedStatement upd = conn.prepareStatement(
            "UPDATE agent_policy " +
            "SET daily_spent_paise=daily_spent_paise+?," +
            "    reserved_paise=MAX(0,reserved_paise-?) " +
            "WHERE agent_id=? AND reserved_paise >= ?"
        )) {
            upd.setLong(1, amountPaise);
            upd.setLong(2, amountPaise);
            upd.setString(3, agentId);
            upd.setLong(4, amountPaise);
            return upd.executeUpdate() == 1;
        }
    }

    // Standalone commit reservation with its own transaction.
    public static PolicyResult commitReservation(String agentId, long amountPaise) {
        if (amountPaise <= 0) return new PolicyResult(false, "Amount must be positive.");
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try {
                boolean ok = commitReservation(conn, agentId, amountPaise);
                if (!ok) {
                    rollback(conn);
                    return new PolicyResult(false, "No active reservation matching amount.");
                }
                conn.commit();
                return new PolicyResult(true, "Reservation committed.");
            } catch (SQLException e) {
                rollback(conn); return new PolicyResult(false, "Commit error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return new PolicyResult(false, "DB error: " + e.getMessage());
        }
    }

    // Called when a payment expires or fails — frees the held budget.
    public static void releaseReservation(String agentId, long amountPaise) {
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE agent_policy SET reserved_paise=MAX(0,reserved_paise-?) WHERE agent_id=?"
            )) {
                upd.setLong(1, amountPaise);
                upd.setString(2, agentId);
                upd.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                rollback(conn);
                System.err.println("[PolicyEngine] releaseReservation failed: " + e.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("[PolicyEngine] DB error releasing reservation: " + e.getMessage());
        }
    }

    // Direct debit used by the metering flow (no reservation step needed there).
    public static PolicyResult checkAndDebit(Connection conn, String agentId, long amountPaise) throws SQLException {
        if (amountPaise < 0) return new PolicyResult(false, "Amount cannot be negative.");
        AgentDatabase.insertDefaultPolicy(conn, agentId);
        String today = LocalDate.now().toString();

        try (PreparedStatement sel = conn.prepareStatement(
            "SELECT allowed,daily_limit_paise,daily_spent_paise,reserved_paise,last_reset_date " +
            "FROM agent_policy WHERE agent_id=?"
        )) {
            sel.setString(1, agentId);
            try (ResultSet rs = sel.executeQuery()) {
                if (!rs.next()) return new PolicyResult(false, "Agent not found.");
                if (rs.getInt("allowed") == 0) return new PolicyResult(false, "Agent blocked.");

                long limit    = rs.getLong("daily_limit_paise");
                long spent    = rs.getLong("daily_spent_paise");
                String last   = rs.getString("last_reset_date");

                if (!today.equals(last)) spent = 0;

                if (spent + amountPaise > limit) {
                    return new PolicyResult(false,
                        String.format("Daily cap: spent=%d limit=%d requested=%d paise",
                            spent, limit, amountPaise));
                }

                long newSpent = spent + amountPaise;
                try (PreparedStatement upd = conn.prepareStatement(
                    "UPDATE agent_policy SET daily_spent_paise=?,last_reset_date=? WHERE agent_id=?"
                )) {
                    upd.setLong(1, newSpent);
                    upd.setString(2, today);
                    upd.setString(3, agentId);
                    upd.executeUpdate();
                }
                return new PolicyResult(true,
                    String.format("OK. Spent today: %d/%d paise", newSpent, limit));
            }
        }
    }

    public static PolicyResult checkAndDebit(String agentId, long amountPaise) {
        if (amountPaise < 0) return new PolicyResult(false, "Amount cannot be negative.");
        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            beginImmediate(conn);
            try {
                PolicyResult res = checkAndDebit(conn, agentId, amountPaise);
                if (res.allowed) {
                    conn.commit();
                } else {
                    conn.rollback();
                }
                return res;
            } catch (SQLException e) {
                rollback(conn); return new PolicyResult(false, "Policy error: " + e.getMessage());
            }
        } catch (SQLException e) {
            return new PolicyResult(false, "DB error: " + e.getMessage());
        }
    }

    public static void upsertPolicy(String agentId, boolean allowed, long dailyLimitPaise) {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO agent_policy(agent_id,allowed,daily_limit_paise,daily_spent_paise,reserved_paise,last_reset_date)" +
                 " VALUES(?,?,?,0,0,date('now'))" +
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

    private static void beginImmediate(Connection conn) throws SQLException {
        try (java.sql.Statement st = conn.createStatement()) { st.execute("BEGIN IMMEDIATE"); }
        catch (SQLException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            // In SQLite JDBC, setAutoCommit(false) starts a transaction; ignore only that specific state
            if (!msg.contains("cannot start a transaction within a transaction")) {
                throw e;
            }
        }
    }

    private static void rollback(Connection conn) {
        try { conn.rollback(); } catch (SQLException ignored) {}
    }
}
