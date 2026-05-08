package muon.app.common.secrets;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SessionSecretStore implements SecretStore {

    private final Map<String, char[]> secrets = new HashMap<>();

    @Override
    public synchronized boolean isAvailable() {
        return true;
    }

    @Override
    public String backendName() {
        return "Session memory";
    }

    @Override
    public synchronized char[] get(String alias) {
        char[] value = secrets.get(alias);
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public synchronized void set(String alias, char[] secret) {
        delete(alias);
        if (secret != null && secret.length > 0) {
            secrets.put(alias, Arrays.copyOf(secret, secret.length));
        }
    }

    @Override
    public synchronized void delete(String alias) {
        char[] existing = secrets.remove(alias);
        if (existing != null) {
            Arrays.fill(existing, '\0');
        }
    }

    @Override
    public boolean isPersistent() {
        return false;
    }
}
