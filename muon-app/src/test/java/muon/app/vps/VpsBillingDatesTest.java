package muon.app.vps;

import junit.framework.TestCase;

import java.time.LocalDate;

public class VpsBillingDatesTest extends TestCase {

    public void testNextMonthlyDateUsesCalendarMonth() {
        assertEquals(LocalDate.now().plusMonths(1).toString(), VpsBillingDates.nextMonthlyDate());
    }

    public void testNextFixedDateSupportsYearlyAndCustomDays() {
        assertEquals(LocalDate.now().plusYears(1).toString(), VpsBillingDates.nextFixedDate("yearly", 365));
        assertEquals(LocalDate.now().plusDays(45).toString(), VpsBillingDates.nextFixedDate("custom", 45));
    }
}
