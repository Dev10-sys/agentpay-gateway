package com.razorpay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

/*
 * Unit tests for AuditLog.
 * Verifies VERIFIED vs WEBHOOK decision separation so webhook delivery
 * cannot cause a spurious 409 on the resource unlock path.
 */
public class AuditLogTest {

    private File tmpDb;

    @Before
    public void setUp() throws Exception {
        tmpDb = File.createTempFile("agentpay_audit_test_", ".db");
        tmpDb.deleteOnExit();
        AgentDatabase.setDbPath(tmpDb.getAbsolutePath());
        AgentDatabase.initialize();
    }

    @After
    public void tearDown() {
        if (tmpDb != null) tmpDb.delete();
    }

    @Test
    public void isAlreadyCredited_returnsFalse_whenNoRecord() {
        assertFalse(AuditLog.isAlreadyCredited("pay_nonexistent"));
    }

    @Test
    public void isAlreadyCredited_returnsFalse_forNullOrEmpty() {
        assertFalse(AuditLog.isAlreadyCredited(null));
        assertFalse(AuditLog.isAlreadyCredited(""));
    }

    @Test
    public void isAlreadyCredited_returnsTrue_afterVerifiedRecord() {
        AuditLog.record("agent-1", "res-1", 100, AuditLog.DECISION_VERIFIED,
                "Test unlock", "order_1", "pay_1");
        assertTrue(AuditLog.isAlreadyCredited("pay_1"));
    }

    // Webhook fix: DECISION_WEBHOOK must NOT trigger isAlreadyCredited.
    @Test
    public void isAlreadyCredited_returnsFalse_forWebhookDecision() {
        // Webhook row has null payment_id (the event carries it but we don't
        // store it there to avoid polluting the replay-protection index).
        AuditLog.record("webhook", null, 100, AuditLog.DECISION_WEBHOOK,
                "payment.captured received", "order_2", null);
        // Neither null nor "pay_2" should match — no VERIFIED row exists yet.
        assertFalse(AuditLog.isAlreadyCredited(null));
        assertFalse(AuditLog.isAlreadyCredited("pay_2"));
    }

    @Test
    public void record_duplicateVerified_secondInsertSwallowed() {
        // First insert succeeds; second is caught internally due to unique index.
        AuditLog.record("agent-1", "res-1", 100, AuditLog.DECISION_VERIFIED,
                "first", "order_3", "pay_3");
        AuditLog.record("agent-1", "res-1", 100, AuditLog.DECISION_VERIFIED,
                "duplicate", "order_3", "pay_3"); // swallowed, no exception

        // First record still wins; isAlreadyCredited should return true.
        assertTrue("First VERIFIED record must persist after duplicate attempt",
                AuditLog.isAlreadyCredited("pay_3"));
    }
}
