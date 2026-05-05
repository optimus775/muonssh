package muon.app.vps;

import junit.framework.TestCase;

import java.time.LocalDate;

public class VpsDateFormatTest extends TestCase {

    public void testParsesDisplayDate() {
        assertEquals("2026-06-05", VpsDateFormat.toStorageDate("05-06-2026"));
        assertEquals("05-06-2026", VpsDateFormat.toDisplayDate("2026-06-05"));
    }

    public void testParsesDayMonthWithCurrentYear() {
        int year = LocalDate.now().getYear();
        assertEquals(year + "-06-05", VpsDateFormat.toStorageDate("05-06"));
    }
}
