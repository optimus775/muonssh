package muon.app.common.secrets;

public final class SecretAliases {

    public static final String VIKUNJA_API_TOKEN = "integration:vikunja:api-token";
    public static final String INFISICAL_CLIENT_SECRET = "integration:infisical:client-secret";
    public static final String LEGACY_VIKUNJA_API_TOKEN = "vps-ledger.vikunja.api-token";
    public static final String LEGACY_INFISICAL_CLIENT_SECRET = "vps-ledger.infisical.client-secret";

    private static final String SESSION_PREFIX = "session:";
    private static final String SSH_PASSWORD_SUFFIX = ":ssh-password";
    private static final String PROXY_PASSWORD_SUFFIX = ":proxy-password";
    private static final String JUMP_SEGMENT = ":jump:";
    private static final String JUMP_PASSWORD_SUFFIX = ":password";

    private SecretAliases() {
    }

    public static String sshPassword(String sessionId) {
        return SESSION_PREFIX + sessionId + SSH_PASSWORD_SUFFIX;
    }

    public static String proxyPassword(String sessionId) {
        return SESSION_PREFIX + sessionId + PROXY_PASSWORD_SUFFIX;
    }

    public static String jumpPassword(String sessionId, String hopId) {
        return SESSION_PREFIX + sessionId + JUMP_SEGMENT + hopId + JUMP_PASSWORD_SUFFIX;
    }

    public static String legacyAliasFor(String alias) {
        if (VIKUNJA_API_TOKEN.equals(alias)) {
            return LEGACY_VIKUNJA_API_TOKEN;
        }
        if (INFISICAL_CLIENT_SECRET.equals(alias)) {
            return LEGACY_INFISICAL_CLIENT_SECRET;
        }
        if (alias != null && alias.startsWith(SESSION_PREFIX) && alias.endsWith(SSH_PASSWORD_SUFFIX)) {
            return alias.substring(SESSION_PREFIX.length(), alias.length() - SSH_PASSWORD_SUFFIX.length());
        }
        return null;
    }

    public static boolean isCanonicalAlias(String alias) {
        return VIKUNJA_API_TOKEN.equals(alias)
                || INFISICAL_CLIENT_SECRET.equals(alias)
                || (alias != null && alias.startsWith(SESSION_PREFIX));
    }
}
