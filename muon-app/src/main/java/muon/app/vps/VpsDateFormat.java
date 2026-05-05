package muon.app.vps;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class VpsDateFormat {

    public static final String DISPLAY_PATTERN = "DD-MM-YYYY";
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private VpsDateFormat() {
    }

    public static LocalDate parse(String value) {
        if (value == null || value.isBlank()) {
            throw new DateTimeParseException("Date is empty", "", 0);
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            String[] parts = trimmed.split("-");
            return createDate(parseInt(parts[0], trimmed), parseInt(parts[1], trimmed), parseInt(parts[2], trimmed), trimmed);
        }
        String[] parts = trimmed.split("-");
        if (parts.length == 2) {
            return createDate(LocalDate.now().getYear(), parseInt(parts[1], trimmed), parseInt(parts[0], trimmed), trimmed);
        }
        if (parts.length == 3 && parts[2].length() == 4) {
            return createDate(parseInt(parts[2], trimmed), parseInt(parts[1], trimmed), parseInt(parts[0], trimmed), trimmed);
        }
        throw new DateTimeParseException("Date must use " + DISPLAY_PATTERN + " or DD-MM", trimmed, 0);
    }

    public static boolean isValidOptional(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public static String toStorageDate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return parse(value).toString();
    }

    public static String toDisplayDate(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return parse(value).format(DISPLAY_FORMATTER);
        } catch (DateTimeParseException e) {
            return value;
        }
    }

    private static int parseInt(String value, String source) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DateTimeParseException("Invalid date number", source, 0, e);
        }
    }

    private static LocalDate createDate(int year, int month, int day, String source) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw new DateTimeParseException("Invalid date", source, 0, e);
        }
    }
}
