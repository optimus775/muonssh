package muon.app.common.secrets;

public interface SecretStore {

    boolean isAvailable();

    String backendName();

    char[] get(String alias) throws Exception;

    default char[] getWithoutPrompt(String alias) throws Exception {
        return get(alias);
    }

    void set(String alias, char[] secret) throws Exception;

    default void setWithoutPrompt(String alias, char[] secret) throws Exception {
        set(alias, secret);
    }

    void delete(String alias) throws Exception;

    default void deleteWithoutPrompt(String alias) throws Exception {
        delete(alias);
    }

    default boolean isPersistent() {
        return true;
    }
}
