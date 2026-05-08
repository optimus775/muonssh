package muon.app.vps;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class ProviderSlug {

    private ProviderSlug() {
    }

    public static String normalize(String value) {
        String normalized = Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        normalized = normalized.replace('_', '-');
        normalized = normalized.replaceAll("[^a-z0-9\\s-]", "-");
        normalized = normalized.replaceAll("\\s+", "-");
        normalized = normalized.replaceAll("-{2,}", "-");
        normalized = normalized.replaceAll("^-+", "");
        normalized = normalized.replaceAll("-+$", "");
        return normalized.isBlank() ? "provider" : normalized;
    }

    public static String unique(String value, Set<String> usedSlugs) {
        String base = normalize(value);
        String candidate = base;
        int suffix = 2;
        while (usedSlugs.contains(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + "-" + suffix++;
        }
        usedSlugs.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }
}
