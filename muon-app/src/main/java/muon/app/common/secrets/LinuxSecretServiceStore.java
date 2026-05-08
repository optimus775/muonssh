package muon.app.common.secrets;

import lombok.extern.slf4j.Slf4j;
import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.types.Variant;
import org.freedesktop.secret.Collection;
import org.freedesktop.secret.Item;
import org.freedesktop.secret.Pair;
import org.freedesktop.secret.Prompt;
import org.freedesktop.secret.Secret;
import org.freedesktop.secret.Service;
import org.freedesktop.secret.Static;
import org.freedesktop.secret.TransportEncryption;
import org.freedesktop.secret.interfaces.Prompt.Completed;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class LinuxSecretServiceStore implements SecretStore {

    private static final String APPLICATION_ATTRIBUTE = "application";
    private static final String ALIAS_ATTRIBUTE = "alias";
    private static final String APPLICATION_NAME = "MuonSSH";

    @Override
    public boolean isAvailable() {
        try (Client ignored = new Client()) {
            return true;
        } catch (Exception e) {
            log.warn("Secret Service is not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String backendName() {
        return "Secret Service";
    }

    @Override
    public char[] get(String alias) throws Exception {
        try (Client client = new Client()) {
            List<ObjectPath> items = client.findItems(alias);
            for (ObjectPath itemPath : items) {
                Item item = new Item(itemPath, client.service);
                Secret secret = item.getSecret(client.service.getSession().getPath());
                if (secret == null) {
                    continue;
                }
                try {
                    return client.transport.decrypt(secret);
                } finally {
                    secret.clear();
                }
            }
            return null;
        }
    }

    @Override
    public void set(String alias, char[] secret) throws Exception {
        if (secret == null || secret.length == 0) {
            delete(alias);
            return;
        }
        try (Client client = new Client()) {
            client.unlockDefaultCollection();
            Map<String, Variant> properties = Item.createProperties("MuonSSH " + alias, attributes(alias));
            try (Secret encrypted = client.transport.encrypt(CharBuffer.wrap(secret))) {
                client.collection.createItem(properties, encrypted, true);
            }
        }
    }

    @Override
    public void delete(String alias) throws Exception {
        try (Client client = new Client()) {
            for (ObjectPath itemPath : client.findItems(alias)) {
                Item item = new Item(itemPath, client.service);
                ObjectPath prompt = item.delete();
                client.performPrompt(prompt);
            }
        }
    }

    private static Map<String, String> attributes(String alias) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(APPLICATION_ATTRIBUTE, APPLICATION_NAME);
        attributes.put(ALIAS_ATTRIBUTE, alias);
        attributes.put("xdg:schema", "org.muonssh.Secret");
        return attributes;
    }

    private static final class Client implements AutoCloseable {
        private final DBusConnection connection;
        private final TransportEncryption transport;
        private final Service service;
        private final Prompt prompt;
        private final Collection collection;

        private Client() throws Exception {
            connection = DBusConnection.newConnection(DBusConnection.DBusBusType.SESSION);
            transport = new TransportEncryption(connection);
            try {
                transport.initialize();
                if (!transport.openSession()) {
                    throw new IOException("Secret Service did not open a session");
                }
                transport.generateSessionKey();
                service = transport.getService();
                prompt = new Prompt(service);
                collection = new Collection(Static.Convert.toObjectPath(Static.ObjectPaths.DEFAULT_COLLECTION), service);
            } catch (Exception e) {
                close();
                throw e;
            }
        }

        private List<ObjectPath> findItems(String alias) {
            Pair<List<ObjectPath>, List<ObjectPath>> result = service.searchItems(attributes(alias));
            List<ObjectPath> items = new ArrayList<>();
            if (result == null) {
                return items;
            }
            if (result.a != null) {
                items.addAll(result.a);
            }
            if (result.b != null && !result.b.isEmpty()) {
                unlock(result.b);
                items.addAll(result.b);
            }
            return items;
        }

        private void unlockDefaultCollection() {
            if (collection.isLocked()) {
                unlock(List.of(collection.getPath()));
            }
        }

        private void unlock(List<ObjectPath> objects) {
            Pair<List<ObjectPath>, ObjectPath> response = service.unlock(objects);
            if (response != null) {
                performPrompt(response.b);
            }
        }

        private void performPrompt(ObjectPath path) {
            if (path == null || "/".equals(path.getPath())) {
                return;
            }
            Completed completed = prompt.await(path);
            if (completed != null && completed.dismissed) {
                throw new IllegalStateException("Secret Service prompt was dismissed");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                if (transport != null) {
                    if (transport.getService() != null && transport.getService().getSession() != null) {
                        transport.getService().getSession().close();
                    }
                    transport.close();
                }
            } catch (Exception e) {
                log.debug("Unable to close Secret Service session cleanly: {}", e.getMessage());
            } finally {
                if (connection != null && connection.isConnected()) {
                    connection.close();
                }
            }
        }
    }
}
