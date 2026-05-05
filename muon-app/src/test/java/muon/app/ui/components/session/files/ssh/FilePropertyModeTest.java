package muon.app.ui.components.session.files.ssh;

import org.junit.Test;

import static org.junit.Assert.*;

public class FilePropertyModeTest {
    @Test
    public void parsesAndFormatsOctalModes() {
        assertEquals(0644, FilePropertyMode.parseOctalMode("644").getAsInt());
        assertEquals(0755, FilePropertyMode.parseOctalMode("755").getAsInt());
        assertEquals(0755, FilePropertyMode.parseOctalMode("0755").getAsInt());
        assertEquals(04755, FilePropertyMode.parseOctalMode("4755").getAsInt());
        assertEquals(01777, FilePropertyMode.parseOctalMode("1777").getAsInt());

        assertEquals("644", FilePropertyMode.formatOctalMode(0644));
        assertEquals("755", FilePropertyMode.formatOctalMode(0755));
        assertEquals("4755", FilePropertyMode.formatOctalMode(04755));
        assertEquals("1777", FilePropertyMode.formatOctalMode(01777));
    }

    @Test
    public void rejectsInvalidOctalModes() {
        assertFalse(FilePropertyMode.parseOctalMode("").isPresent());
        assertFalse(FilePropertyMode.parseOctalMode("88").isPresent());
        assertFalse(FilePropertyMode.parseOctalMode("888").isPresent());
        assertFalse(FilePropertyMode.parseOctalMode("10000").isPresent());
        assertFalse(FilePropertyMode.parseOctalMode("64a").isPresent());
    }

    @Test
    public void convertsPermissionsToSelectionsAndBack() {
        boolean[] selected = FilePropertyMode.permissionsToSelection(0644);

        assertTrue(selected[0]);
        assertTrue(selected[1]);
        assertFalse(selected[2]);
        assertTrue(selected[3]);
        assertFalse(selected[4]);
        assertFalse(selected[5]);
        assertTrue(selected[6]);
        assertFalse(selected[7]);
        assertFalse(selected[8]);
        assertFalse(selected[9]);

        assertEquals(0644, FilePropertyMode.selectionToPermissions(selected));

        selected[9] = true;
        selected[10] = true;
        selected[11] = true;
        assertEquals(07644, FilePropertyMode.selectionToPermissions(selected));
    }
}
