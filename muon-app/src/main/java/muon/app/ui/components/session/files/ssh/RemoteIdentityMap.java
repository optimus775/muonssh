package muon.app.ui.components.session.files.ssh;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class RemoteIdentityMap {
    static final RemoteIdentityMap EMPTY = new RemoteIdentityMap(Collections.emptyMap(), Collections.emptyMap());

    private final Map<String, Integer> idByName;
    private final Map<Integer, String> nameById;

    private RemoteIdentityMap(Map<String, Integer> idByName, Map<Integer, String> nameById) {
        this.idByName = idByName;
        this.nameById = nameById;
    }

    static RemoteIdentityMap parsePasswd(String output) {
        return parse(output, 2);
    }

    static RemoteIdentityMap parseGroup(String output) {
        return parse(output, 2);
    }

    private static RemoteIdentityMap parse(String output, int idIndex) {
        Map<String, Integer> ids = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        if (output == null || output.isEmpty()) {
            return EMPTY;
        }

        for (String line : output.split("\\R")) {
            String[] parts = line.split(":", -1);
            if (parts.length <= idIndex) {
                continue;
            }
            String name = parts[0].trim();
            if (name.isEmpty()) {
                continue;
            }
            try {
                int id = Integer.parseInt(parts[idIndex].trim());
                if (id < 0) {
                    continue;
                }
                ids.putIfAbsent(name, id);
                names.putIfAbsent(id, name);
            } catch (NumberFormatException ignored) {
                // Ignore malformed getent rows.
            }
        }
        return new RemoteIdentityMap(ids, names);
    }

    Integer getId(String name) {
        return idByName.get(name);
    }

    String getName(int id) {
        return nameById.get(id);
    }

    static Resolution resolve(String nameText, String idText, RemoteIdentityMap identityMap) {
        String name = nameText == null ? "" : nameText.trim();
        String idValue = idText == null ? "" : idText.trim();
        Integer typedId = null;
        if (!idValue.isEmpty()) {
            try {
                typedId = Integer.parseInt(idValue);
                if (typedId < 0) {
                    return Resolution.invalid();
                }
            } catch (NumberFormatException e) {
                return Resolution.invalid();
            }
        }

        RemoteIdentityMap map = identityMap == null ? EMPTY : identityMap;
        if (!name.isEmpty()) {
            Integer mappedId = map.getId(name);
            if (mappedId != null) {
                if (typedId != null && !mappedId.equals(typedId)) {
                    return Resolution.invalid();
                }
                return Resolution.valid(mappedId);
            }
            return typedId == null ? Resolution.invalid() : Resolution.valid(typedId);
        }

        return Resolution.valid(typedId);
    }

    @Getter
    static final class Resolution {
        private final boolean valid;
        private final Integer id;

        private Resolution(boolean valid, Integer id) {
            this.valid = valid;
            this.id = id;
        }

        static Resolution valid(Integer id) {
            return new Resolution(true, id);
        }

        static Resolution invalid() {
            return new Resolution(false, null);
        }
    }
}
