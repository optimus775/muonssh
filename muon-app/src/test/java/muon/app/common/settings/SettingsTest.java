package muon.app.common.settings;

import junit.framework.TestCase;

public class SettingsTest extends TestCase {

    public void testDefaultPaletteUsesAccessibleReds() {
        int[] colors = Settings.defaultPalleteColors();

        assertEquals(Settings.ANSI_RED, colors[1]);
        assertEquals(Settings.ANSI_BRIGHT_RED, colors[9]);
    }

    public void testNormalizeTerminalPaletteMigratesLegacyReds() {
        Settings settings = new Settings();
        int[] colors = settings.getPalleteColors();
        colors[1] = 0xcd0000;
        colors[9] = 0xffff0000;

        assertTrue(settings.normalizeTerminalPalette());
        assertEquals(Settings.ANSI_RED, settings.getPalleteColors()[1]);
        assertEquals(Settings.ANSI_BRIGHT_RED, settings.getPalleteColors()[9]);
    }
}
