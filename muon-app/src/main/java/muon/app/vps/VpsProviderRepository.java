package muon.app.vps;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class VpsProviderRepository {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public VpsProviderRepository() {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

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

        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                boolean explicitSlug = provider.getSlug() != null && !provider.getSlug().isBlank();
                if (!explicitSlug) {
                    provider.setSlug(ProviderSlug.normalize(provider.getName()));
                } else {
                    provider.setSlug(ProviderSlug.normalize(provider.getSlug()));
                    ensureSlugAvailable(connection, provider.getSlug(), provider.getId());
                }
                upsertProvider(connection, provider, explicitSlug, true);
            }
            return provider;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to save VPS provider", e);
        }
    }

    public synchronized ProviderRecord upsertImportedProvider(ProviderRecord provider) throws IOException {
        if (provider == null || provider.getName() == null || provider.getName().trim().isEmpty()) {
            throw new IOException("Provider name can not be empty");
        }
        try {
            VpsDatabaseManager.migrate();
            try (Connection connection = VpsDatabaseManager.openConnection()) {
                upsertProvider(connection, provider, false, false);
            }
            return provider;
        } catch (Exception e) {
            throw new IOException("Unable to import VPS provider", e);
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

    synchronized void upsertProvider(Connection connection, ProviderRecord provider,
                                     boolean preserveExplicitSlug, boolean touchUpdatedAt) throws Exception {
        prepareProvider(provider, touchUpdatedAt);
        provider.setSlug(resolveUniqueSlug(connection, provider, preserveExplicitSlug));
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO providers("
                        + "id, name, slug, website, account_id, notes, tags, updated_at, deleted"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET "
                        + "name = excluded.name, "
                        + "slug = excluded.slug, "
                        + "website = excluded.website, "
                        + "account_id = excluded.account_id, "
                        + "notes = excluded.notes, "
                        + "tags = excluded.tags, "
                        + "updated_at = excluded.updated_at, "
                        + "deleted = excluded.deleted")) {
            statement.setString(1, provider.getId());
            statement.setString(2, provider.getName());
            statement.setString(3, provider.getSlug());
            statement.setString(4, provider.getWebsite());
            statement.setString(5, provider.getAccountId());
            statement.setString(6, provider.getNotes());
            statement.setString(7, provider.getTags());
            statement.setLong(8, provider.getUpdatedAt());
            statement.setInt(9, provider.isDeleted() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    synchronized void replaceProviders(Connection connection, List<ProviderRecord> providers) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM providers")) {
            statement.executeUpdate();
        }
        if (providers == null) {
            return;
        }
        for (ProviderRecord provider : providers) {
            if (provider == null || provider.getName() == null || provider.getName().trim().isEmpty()) {
                continue;
            }
            provider.setDeleted(false);
            upsertProvider(connection, provider, false, false);
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
        provider.setAccountId(accountId);
        provider.setUpdatedAt(System.currentTimeMillis());
        upsertProvider(connection, provider, false, true);
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
        provider.setSlug(rs.getString("slug"));
        provider.setWebsite(rs.getString("website"));
        provider.setAccountId(rs.getString("account_id"));
        provider.setNotes(rs.getString("notes"));
        provider.setTags(rs.getString("tags"));
        provider.setUpdatedAt(rs.getLong("updated_at"));
        provider.setDeleted(rs.getInt("deleted") != 0);
        return provider;
    }

    private void prepareProvider(ProviderRecord provider, boolean touchUpdatedAt) {
        if (provider.getId() == null || provider.getId().isBlank()) {
            provider.setId(UUID.randomUUID().toString());
        }
        provider.setName(provider.getName().trim());
        provider.setSlug(ProviderSlug.normalize(provider.getSlug() == null || provider.getSlug().isBlank()
                ? provider.getName()
                : provider.getSlug()));
        if (touchUpdatedAt || provider.getUpdatedAt() <= 0) {
            provider.setUpdatedAt(System.currentTimeMillis());
        }
    }

    private void ensureSlugAvailable(Connection connection, String slug, String providerId) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM providers WHERE LOWER(slug) = LOWER(?) AND deleted = 0")) {
            statement.setString(1, slug);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String existingId = rs.getString("id");
                    if (providerId == null || !providerId.equals(existingId)) {
                        throw new IOException("Provider slug must be unique");
                    }
                }
            }
        }
    }

    private String resolveUniqueSlug(Connection connection, ProviderRecord provider, boolean preserveExplicitSlug) throws Exception {
        String baseSlug = ProviderSlug.normalize(provider.getSlug());
        if (preserveExplicitSlug) {
            return baseSlug;
        }
        Set<String> usedSlugs = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT slug FROM providers WHERE deleted = 0 AND id <> ?")) {
            statement.setString(1, provider.getId());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String existingSlug = rs.getString("slug");
                    if (existingSlug != null && !existingSlug.isBlank()) {
                        usedSlugs.add(existingSlug.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return ProviderSlug.unique(baseSlug, usedSlugs);
    }
}
