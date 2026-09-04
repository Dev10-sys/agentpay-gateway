package com.razorpay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.Assert.*;

/*
 * Unit tests for AgentPolicyEngine.
 * Each test gets a fresh temp-file SQLite DB so all getConnection() calls
 * within a test share the same schema (in-memory DBs are per-connection).
 */
public class AgentPolicyEngineTest {

    private File tmpDb;

    @Before
    public void setUp() throws Exception {
        tmpDb = File.createTempFile("agentpay_test_", ".db");
        tmpDb.deleteOnExit();
        AgentDatabase.setDbPath(tmpDb.getAbsolutePath());
        AgentDatabase.initialize();
    }

    @After
    public void tearDown() {
        if (tmpDb != null) tmpDb.delete();
    }

    // ---- reserve() --------------------------------------------------------

    @Test
    public void reserve_withinBudget_succeeds() {
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-A", 1000);
        assertTrue("Should allow reservation within default budget", r.allowed);
    }

    @Test
    public void reserve_exceedsCap_denied() {
        AgentPolicyEngine.upsertPolicy("agent-B", true, 100);
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-B", 500);
        assertFalse("Reservation exceeding cap must be denied", r.allowed);
    }

    @Test
    public void reserve_blockedAgent_denied() {
        AgentPolicyEngine.upsertPolicy("agent-C", false, 50000);
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-C", 100);
        assertFalse("Blocked agent must be denied", r.allowed);
    }

    @Test
    public void reserve_zeroAmount_denied() {
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-D", 0);
        assertFalse("Zero-amount reservation must be rejected", r.allowed);
    }

    @Test
    public void reserve_negativeAmount_denied() {
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-D", -50);
        assertFalse("Negative amount must be rejected", r.allowed);
    }

    // ---- commitReservation() -----------------------------------------------

    @Test
    public void commitReservation_afterReserve_succeeds() {
        AgentPolicyEngine.reserve("agent-E", 200);
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.commitReservation("agent-E", 200);
        assertTrue("Committing a valid reservation should succeed", r.allowed);
    }

    // ---- releaseReservation() ----------------------------------------------

    @Test
    public void releaseReservation_freesHold_allowsNextReservation() {
        AgentPolicyEngine.upsertPolicy("agent-F", true, 300);
        AgentPolicyEngine.reserve("agent-F", 300);         // exhaust budget
        AgentPolicyEngine.releaseReservation("agent-F", 300); // free hold
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-F", 300);
        assertTrue("After releasing reservation, agent should be able to reserve again", r.allowed);
    }

    // ---- checkAndDebit() (metering path) -----------------------------------

    @Test
    public void checkAndDebit_withinBudget_succeeds() {
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.checkAndDebit("agent-G", 100);
        assertTrue(r.allowed);
    }

    @Test
    public void checkAndDebit_exceedsCap_denied() {
        AgentPolicyEngine.upsertPolicy("agent-H", true, 100);
        AgentPolicyEngine.checkAndDebit("agent-H", 100);
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.checkAndDebit("agent-H", 1);
        assertFalse("Debit after cap exhausted must be denied", r.allowed);
    }

    // ---- concurrent spending (BEGIN IMMEDIATE serialises writes) -----------

    @Test
    public void reserve_concurrent_onlyOneSucceedsWhenBudgetIsExact() throws Exception {
        AgentPolicyEngine.upsertPolicy("agent-I", true, 100);

        boolean[] results = new boolean[2];
        Thread[] threads  = new Thread[2];

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            threads[i] = new Thread(() ->
                results[idx] = AgentPolicyEngine.reserve("agent-I", 100).allowed
            );
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(5000);

        int wins = 0;
        for (boolean r : results) if (r) wins++;
        assertEquals("Exactly one concurrent reservation should win when budget is exact", 1, wins);
    }

    // ---- atomicConsumeChallenge -------------------------------------------

    @Test
    public void atomicConsumeChallenge_firstCall_returnsTrue() throws Exception {
        // Insert a PENDING challenge manually.
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO payment_challenge(order_id,agent_id,resource_id,amount_paise,status) " +
                 "VALUES('order_test1','agent-J','res-1',100,'PENDING')"
             )) {
            ps.executeUpdate();
        }
        try (Connection conn = AgentDatabase.getConnection()) {
            assertTrue("First consume of PENDING challenge must succeed",
                    AgentDatabase.atomicConsumeChallenge(conn, "order_test1"));
        }
    }

    @Test
    public void atomicConsumeChallenge_secondCall_returnsFalse() throws Exception {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO payment_challenge(order_id,agent_id,resource_id,amount_paise,status) " +
                 "VALUES('order_test2','agent-K','res-1',100,'PENDING')"
             )) {
            ps.executeUpdate();
        }
        try (Connection conn = AgentDatabase.getConnection()) {
            AgentDatabase.atomicConsumeChallenge(conn, "order_test2"); // first consume
        }
        try (Connection conn = AgentDatabase.getConnection()) {
            assertFalse("Second consume of already-CONSUMED challenge must return false",
                    AgentDatabase.atomicConsumeChallenge(conn, "order_test2"));
        }
    }

    // ---- purgeExpiredChallenges -------------------------------------------

    @Test
    public void purgeExpiredChallenges_releasesReservationForExpiredOrders() throws Exception {
        // Reserve 200 paise, then inject a backdated PENDING challenge.
        AgentPolicyEngine.upsertPolicy("agent-L", true, 200);
        AgentPolicyEngine.reserve("agent-L", 200);

        // Backdate the challenge to simulate expiry.
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO payment_challenge" +
                 "(order_id,agent_id,resource_id,amount_paise,status,created_at) " +
                 "VALUES('order_old','agent-L','res-1',200,'PENDING'," +
                 "datetime('now','-20 minutes'))"
             )) {
            ps.executeUpdate();
        }

        AgentDatabase.purgeExpiredChallenges("agent-L");

        // Budget should now be free again.
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-L", 200);
        assertTrue("After purge, agent should be able to reserve again", r.allowed);
    }
}

