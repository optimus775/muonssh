package muon.app.common.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.CharArrayReader;
import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.KeyStore.SecretKeyEntry;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class LegacyPkcs12SecretStore implements SecretStore {

    private final File storeFile;
    private final KeyStore keyStore;
    private final ObjectMapper objectMapper;
    private KeyStore.PasswordProtection protParam;
    private Map<String, char[]> passwordMap = new HashMap<>();
    private boolean unlocked;

    public LegacyPkcs12SecretStore(File configDir) throws Exception {
        this.storeFile = new File(configDir, "passwords.pfx");
        this.keyStore = KeyStore.getInstance("PKCS12");
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public synchronized boolean isAvailable() {
        return true;
    }

    @Override
    public String backendName() {
        return "Legacy PKCS12";
    }

    public synchronized boolean exists() {
        return storeFile.exists();
    }

    public synchronized boolean isUnlocked() {
        return unlocked;
    }

    public synchronized File getStoreFile() {
        return storeFile;
    }

    public synchronized void unlock(char[] password) throws Exception {
        protParam = new KeyStore.PasswordProtection(password, "PBEWithHmacSHA256AndAES_256", null);
        if (!storeFile.exists()) {
            keyStore.load(null, protParam.getPassword());
            passwordMap = new HashMap<>();
            unlocked = true;
            return;
        }
        try (InputStream in = new FileInputStream(storeFile)) {
            keyStore.load(in, protParam.getPassword());
            loadPasswords();
            unlocked = true;
        }
    }

    private void loadPasswords() throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBE");
        KeyStore.SecretKeyEntry ske = (KeyStore.SecretKeyEntry) keyStore.getEntry("passwords", protParam);
        if (ske == null) {
            passwordMap = new HashMap<>();
            return;
        }
        PBEKeySpec keySpec = (PBEKeySpec) factory.getKeySpec(ske.getSecretKey(), PBEKeySpec.class);
        passwordMap = deserializePasswordMap(keySpec.getPassword());
    }

    private Map<String, char[]> deserializePasswordMap(char[] chars) throws Exception {
        return objectMapper.readValue(new CharArrayReader(chars), new TypeReference<>() {
        });
    }

    private char[] serializePasswordMap(Map<String, char[]> map) throws Exception {
        CharArrayWriter writer = new CharArrayWriter();
        objectMapper.writeValue(writer, map);
        return writer.toCharArray();
    }

    @Override
    public synchronized char[] get(String alias) {
        ensureUnlocked();
        char[] value = passwordMap.get(alias);
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    @Override
    public synchronized void set(String alias, char[] secret) throws Exception {
        ensureUnlocked();
        deleteInMemory(alias);
        if (secret != null && secret.length > 0) {
            passwordMap.put(alias, Arrays.copyOf(secret, secret.length));
        }
        save();
    }

    @Override
    public synchronized void delete(String alias) throws Exception {
        ensureUnlocked();
        deleteInMemory(alias);
        save();
    }

    private void deleteInMemory(String alias) {
        char[] existing = passwordMap.remove(alias);
        if (existing != null) {
            Arrays.fill(existing, '\0');
        }
    }

    public synchronized Map<String, char[]> snapshot() {
        ensureUnlocked();
        Map<String, char[]> copy = new HashMap<>();
        for (Map.Entry<String, char[]> entry : passwordMap.entrySet()) {
            copy.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        return copy;
    }

    public synchronized void save() throws Exception {
        ensureUnlocked();
        SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance("PBE");
        SecretKey generatedSecret = secretKeyFactory.generateSecret(new PBEKeySpec(serializePasswordMap(passwordMap)));
        keyStore.setEntry("passwords", new SecretKeyEntry(generatedSecret), protParam);
        log.info("Legacy password protection: {}", protParam.getProtectionAlgorithm());
        try (OutputStream out = new FileOutputStream(storeFile)) {
            keyStore.store(out, protParam.getPassword());
        }
    }

    public synchronized boolean changePassword(char[] newPassword) throws Exception {
        ensureUnlocked();
        Map<String, char[]> passMap = new HashMap<>();

        for (Map.Entry<String, char[]> entry : passwordMap.entrySet()) {
            passMap.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        if (keyStore.containsAlias("passwords")) {
            keyStore.deleteEntry("passwords");
        }

        protParam = new KeyStore.PasswordProtection(newPassword, "PBEWithHmacSHA256AndAES_256", null);
        passwordMap.clear();
        for (Map.Entry<String, char[]> entry : passMap.entrySet()) {
            passwordMap.put(entry.getKey(), entry.getValue());
        }
        save();
        return true;
    }

    private void ensureUnlocked() {
        if (!unlocked) {
            throw new IllegalStateException("Legacy password store is locked");
        }
    }
}
