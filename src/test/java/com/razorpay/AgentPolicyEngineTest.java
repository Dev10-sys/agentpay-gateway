package com.razorpay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

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

    // ---- midnight reset ---------------------------------------------------

    @Test
    public void midnightReset_resetsDailySpentInDatabase() throws Exception {
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO agent_policy(agent_id,allowed,daily_limit_paise,daily_spent_paise,reserved_paise,last_reset_date) " +
                 "VALUES('agent-reset',1,500,450,0,date('now','-1 day'))"
             )) {
            ps.executeUpdate();
        }

        // Reserving 100 paise should succeed because yesterday's spent was 450 but today is a fresh day (limit 500)
        AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.reserve("agent-reset", 100);
        assertTrue("Reservation on new day should succeed", r.allowed);

        // Verify DB directly: daily_spent_paise MUST have been reset to 0 in SQLite
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT daily_spent_paise, reserved_paise FROM agent_policy WHERE agent_id='agent-reset'"
             );
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("daily_spent_paise must be reset to 0 in DB on new day", 0, rs.getLong("daily_spent_paise"));
            assertEquals("reserved_paise must be 100", 100, rs.getLong("reserved_paise"));
        }

        // Commit reservation and check spend is 100, not 450 + 100 = 550
        AgentPolicyEngine.PolicyResult commitRes = AgentPolicyEngine.commitReservation("agent-reset", 100);
        assertTrue(commitRes.allowed);

        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT daily_spent_paise, reserved_paise FROM agent_policy WHERE agent_id='agent-reset'"
             );
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("daily_spent_paise should now be 100", 100, rs.getLong("daily_spent_paise"));
            assertEquals("reserved_paise should be 0", 0, rs.getLong("reserved_paise"));
        }
    }

    // ---- transactional checkAndDebit ---------------------------------------

    @Test
    public void checkAndDebit_transactionalRollback_preservesBudget() throws Exception {
        AgentPolicyEngine.upsertPolicy("agent-tx", true, 500);

        try (Connection conn = AgentDatabase.getConnection()) {
            conn.setAutoCommit(false);
            AgentPolicyEngine.PolicyResult r = AgentPolicyEngine.checkAndDebit(conn, "agent-tx", 50);
            assertTrue(r.allowed);
            // Roll back transaction
            conn.rollback();
        }

        // Verify budget was not debited
        try (Connection conn = AgentDatabase.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT daily_spent_paise FROM agent_policy WHERE agent_id='agent-tx'"
             );
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("Rollback must restore daily_spent_paise to 0", 0, rs.getLong("daily_spent_paise"));
        }
    }

    // ---- webhook deduplication & event_id ----------------------------------

    @Test
    public void recordWebhookEvent_withEventId_distinguishesNewAndDuplicate() {
        AgentDatabase.WebhookRecordResult r1 =
            AgentDatabase.recordWebhookEvent("evt_test_100", "payment.captured", "pay_test_100", "order_test_100");
        assertEquals("First delivery should be NEW", AgentDatabase.WebhookRecordResult.NEW, r1);

        AgentDatabase.WebhookRecordResult r2 =
            AgentDatabase.recordWebhookEvent("evt_test_100", "payment.captured", "pay_test_100", "order_test_100");
        assertEquals("Second delivery with same event_id should be DUPLICATE", AgentDatabase.WebhookRecordResult.DUPLICATE, r2);

        // Duplicate by payment_id without event_id
        AgentDatabase.WebhookRecordResult r3 =
            AgentDatabase.recordWebhookEvent(null, "payment.captured", "pay_test_100", "order_test_100");
        assertEquals("Duplicate payment_id should be DUPLICATE", AgentDatabase.WebhookRecordResult.DUPLICATE, r3);
    }
}

