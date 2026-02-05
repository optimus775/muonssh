
package muon.app.util;

import lombok.extern.slf4j.Slf4j;
import muon.app.App;
import muon.app.ui.laf.AppSkin;
import muon.app.util.enums.Language;

import java.awt.*;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import static java.util.Map.entry;

/**
 * @author subhro
 */
@Slf4j
public class FontUtils {
    private static final String SYSTEM_MONOSPACED_KEY = "Monospaced";
    private static final String TERMINAL_GLYPH_SAMPLE = "─│┌┐└┘█▁▂▃▄▅▆▇▉⣀⣄⣤⣶";
    private static final String[] TERMINAL_FONT_FALLBACK_ORDER = {
            SYSTEM_MONOSPACED_KEY,
            "DejaVuSansMono",
            "Hack-Regular",
            "JetBrainsMono-Regular",
            "SourceCodePro-Regular",
            "Inconsolata-Regular",
            "FiraCode-Regular"
    };

    FontUtils() {

    }
    public static final Map<String, String> TERMINAL_FONTS = Map.ofEntries(
            entry(SYSTEM_MONOSPACED_KEY, "System Monospaced"),
            entry("DejaVuSansMono", "DejaVu Sans Mono"),
            entry("FiraCode-Regular", "Fira Code Regular"),
            entry("Inconsolata-Regular", "Inconsolata Regular"),
            entry("JetBrainsMono-Regular", "JetBrainsMono Regular"),
            entry("Hack-Regular", "Hack Regular"),
            entry("SourceCodePro-Regular", "Source Code Pro Regular"),
            entry("NotoMono-Regular", "Noto Mono"));

    public static Font loadFonts() {
        String fontPath = "/fonts/Helvetica.ttf";
        if (App.getGlobalSettings().getLanguage().equals(Language.CHINESE)) {
            fontPath = "/fonts/WenQuanYi-Micro-Hei-Regular.ttf";
        }

        try (InputStream is = AppSkin.class
                .getResourceAsStream(fontPath)) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(is));
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(font);
            return font.deriveFont(Font.PLAIN, 12.0f);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static Font loadFontAwesomeFonts() {
        try (InputStream is = AppSkin.class.getResourceAsStream("/fonts/fontawesome-webfont.ttf")) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(is));
            return font.deriveFont(Font.PLAIN, 14f);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public static Font loadTerminalFont(String name) {
        log.debug("Loading font: {}", name);
        Font requestedFont = loadTerminalFontInternal(name);
        if (supportsTerminalGlyphs(requestedFont)) {
            return requestedFont;
        }

        if (requestedFont != null) {
            log.warn("Font {} does not support full terminal pseudographics. Falling back.", name);
        } else {
            log.warn("Unable to load font {}. Falling back.", name);
        }

        for (String fallbackName : TERMINAL_FONT_FALLBACK_ORDER) {
            if (Objects.equals(fallbackName, name)) {
                continue;
            }
            Font fallback = loadTerminalFontInternal(fallbackName);
            if (supportsTerminalGlyphs(fallback)) {
                log.info("Using fallback terminal font: {}", fallbackName);
                return fallback;
            }
        }

        if (requestedFont != null) {
            return requestedFont;
        }

        return new Font(Font.MONOSPACED, Font.PLAIN, 12);
    }

    private static boolean supportsTerminalGlyphs(Font font) {
        return font != null && font.canDisplayUpTo(TERMINAL_GLYPH_SAMPLE) == -1;
    }

    private static Font loadTerminalFontInternal(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        if (SYSTEM_MONOSPACED_KEY.equals(name)) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 12);
        }

        try (InputStream is = AppSkin.class.getResourceAsStream(String.format("/fonts/terminal/%s.ttf", name))) {
            Font font = Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(is));
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            ge.registerFont(font);
            log.debug("Font loaded: {} of family: {}", font.getFontName(), font.getFamily());
            return font.deriveFont(Font.PLAIN, 12.0f);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }
}
