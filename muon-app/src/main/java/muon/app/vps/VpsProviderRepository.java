package muon.app.vps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
public class VpsProviderRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public synchronized List<ProviderRecord> listProviders() {
        List<ProviderRecord> providers = new ArrayList<>();
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM providers WHERE deleted = 0 ORDER BY LOWER(name)")) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        providers.add(readProvider(rs));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unable to list VPS providers", e);
        }
        return providers;
    }

    public synchronized ProviderRecord findProvider(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM providers WHERE id = ? AND deleted = 0")) {
                statement.setString(1, id);
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return readProvider(rs);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unable to find VPS provider {}", id, e);
        }
        return null;
    }

    public synchronized ProviderRecord findProviderByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT * FROM providers WHERE LOWER(name) = LOWER(?) AND deleted = 0")) {
                statement.setString(1, name.trim());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return readProvider(rs);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Unable to find VPS provider by name {}", name, e);
        }
        return null;
    }

    public synchronized ProviderRecord upsertProvider(ProviderRecord provider) throws IOException {
        if (provider == null || provider.getName() == null || provider.getName().trim().isEmpty()) {
            throw new IOException("Provider name can not be empty");
        }
        if (provider.getId() == null || provider.getId().isBlank()) {
            provider.setId(UUID.randomUUID().toString());
        }
        provider.setName(provider.getName().trim());
        provider.setUpdatedAt(System.currentTimeMillis());

        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                upsertProvider(connection, provider);
            }
            return provider;
        } catch (Exception e) {
            throw new IOException("Unable to save VPS provider", e);
        }
    }

    public synchronized void deleteProvider(String id) throws IOException {
        if (id == null || id.isBlank()) {
            return;
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE providers SET deleted = 1, updated_at = ? WHERE id = ?")) {
                statement.setLong(1, System.currentTimeMillis());
                statement.setString(2, id);
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IOException("Unable to delete VPS provider", e);
        }
    }

    synchronized void upsertProvider(Connection connection, ProviderRecord provider) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO providers("
                        + "id, name, website, panel_url, billing_url, account_id, notes, tags, updated_at, deleted"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "name = excluded.name, "
                        + "website = excluded.website, "
                        + "panel_url = excluded.panel_url, "
                        + "billing_url = excluded.billing_url, "
                        + "account_id = excluded.account_id, "
                        + "notes = excluded.notes, "
                        + "tags = excluded.tags, "
                        + "updated_at = excluded.updated_at, "
                        + "deleted = excluded.deleted")) {
            statement.setString(1, provider.getId());
            statement.setString(2, provider.getName());
            statement.setString(3, provider.getWebsite());
            statement.setString(4, provider.getPanelUrl());
            statement.setString(5, provider.getBillingUrl());
            statement.setString(6, provider.getAccountId());
            statement.setString(7, provider.getNotes());
            statement.setString(8, provider.getTags());
            statement.setLong(9, provider.getUpdatedAt());
            statement.setInt(10, provider.isDeleted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    synchronized String createProviderFromHost(Connection connection, String providerName, String providerUrl,
                                               String accountId) throws Exception {
        if (providerName == null || providerName.isBlank()) {
            return null;
        }
        ProviderRecord existing = findProviderByName(connection, providerName.trim());
        if (existing != null) {
            return existing.getId();
        }
        ProviderRecord provider = new ProviderRecord();
        provider.setId(UUID.randomUUID().toString());
        provider.setName(providerName.trim());
        provider.setWebsite(providerUrl);
        provider.setPanelUrl(providerUrl);
        provider.setBillingUrl(providerUrl);
        provider.setAccountId(accountId);
        provider.setUpdatedAt(System.currentTimeMillis());
        upsertProvider(connection, provider);
        return provider.getId();
    }

    synchronized ProviderRecord findProviderByName(Connection connection, String name) throws Exception {
        if (name == null || name.isBlank()) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM providers WHERE LOWER(name) = LOWER(?) AND deleted = 0")) {
            statement.setString(1, name.trim());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readProvider(rs);
                }
            }
        }
        return null;
    }

    public synchronized String toProvidersJson(List<ProviderRecord> providers) throws IOException {
        return objectMapper.writeValueAsString(providers);
    }

    public synchronized List<ProviderRecord> fromProvidersJson(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private ProviderRecord readProvider(ResultSet rs) throws Exception {
        ProviderRecord provider = new ProviderRecord();
        provider.setId(rs.getString("id"));
        provider.setName(rs.getString("name"));
        provider.setWebsite(rs.getString("website"));
        provider.setPanelUrl(rs.getString("panel_url"));
        provider.setBillingUrl(rs.getString("billing_url"));
        provider.setAccountId(rs.getString("account_id"));
        provider.setNotes(rs.getString("notes"));
        provider.setTags(rs.getString("tags"));
        provider.setUpdatedAt(rs.getLong("updated_at"));
        provider.setDeleted(rs.getInt("deleted") != 0);
        return provider;
    }
}
