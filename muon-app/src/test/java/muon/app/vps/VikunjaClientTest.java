package muon.app.vps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import muon.app.common.settings.Settings;
import muon.app.ui.components.session.SessionInfo;

public class VikunjaClientTest extends TestCase {

    public void testBuildPaymentPayload() {
        SessionInfo info = new SessionInfo();
        info.setName("alpha");
        info.setHost("203.0.113.5");
        info.setProvider("Provider");
        info.setPrice("3.50");
        info.setCurrency("EUR");
        info.setNextPaymentDate("2026-07-15");

        Settings settings = new Settings();
        settings.setVikunjaProjectId(42);
        settings.setVikunjaReminderOffsetDays(5);

        ObjectNode payload = new VikunjaClient().buildPaymentTaskPayload(info, settings);

        assertEquals("VPS payment: alpha", payload.get("title").asText());
        assertEquals(42, payload.get("project_id").asLong());
        assertTrue(payload.get("due_date").asText().startsWith("2026-07-15T09:00"));
        assertEquals(-432000, payload.get("reminders").get(0).get("relative_period").asInt());
        assertEquals("due_date", payload.get("reminders").get(0).get("relative_to").asText());
        assertTrue(payload.get("description").asText().contains("Provider: Provider"));
    }

    public void testBuildHourlyBalancePayload() {
        SessionInfo info = new SessionInfo();
        info.setName("hourly");
        info.setHost("203.0.113.6");
        info.setProvider("Provider");
        info.setBillingPeriodType("hourly_balance");
        info.setHourlyRate("0.01");
        info.setCurrency("USD");
        info.setNextBalanceCheckDate("2026-08-20");

        Settings settings = new Settings();
        settings.setVikunjaProjectId(42);
        settings.setVikunjaReminderOffsetDays(2);

        ObjectNode payload = new VikunjaClient().buildPaymentTaskPayload(info, settings);

        assertEquals("Check provider balance: hourly", payload.get("title").asText());
        assertTrue(payload.get("due_date").asText().startsWith("2026-08-20T09:00"));
        assertEquals(-172800, payload.get("reminders").get(0).get("relative_period").asInt());
        assertTrue(payload.get("description").asText().contains("Hourly rate: 0.01 USD"));
    }
}
