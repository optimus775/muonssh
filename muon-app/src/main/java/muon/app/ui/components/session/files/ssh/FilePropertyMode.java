package muon.app.ui.components.session.files.ssh;

import java.util.OptionalInt;

final class FilePropertyMode {
    private FilePropertyMode() {
    }

    static OptionalInt parseOctalMode(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String value = text.trim();
        if (!value.matches("[0-7]{3,4}")) {
            return OptionalInt.empty();
        }
        int mode = Integer.parseInt(value, 8);
        if (mode > 07777) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(mode);
    }

    static String formatOctalMode(int mode) {
        int normalized = mode & 07777;
        return (normalized & 07000) == 0
               ? String.format("%03o", normalized)
               : String.format("%04o", normalized);
    }

    static boolean[] permissionsToSelection(int permissions) {
        boolean[] selected = new boolean[PropertiesDialog.PERMS.length];
        for (int i = 0; i < PropertiesDialog.PERMS.length; i++) {
            selected[i] = (permissions & PropertiesDialog.PERMS[i]) != 0;
        }
        return selected;
    }

    static int selectionToPermissions(boolean[] selected) {
        int permissions = 0;
        for (int i = 0; i < PropertiesDialog.PERMS.length && i < selected.length; i++) {
            if (selected[i]) {
                permissions |= PropertiesDialog.PERMS[i];
            }
        }
        return permissions;
    }
}
