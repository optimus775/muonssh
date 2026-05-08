package muon.app.common.secrets;

public interface SecretStore {

    boolean isAvailable();

    String backendName();

    char[] get(String alias) throws Exception;

    void set(String alias, char[] secret) throws Exception;

    void delete(String alias) throws Exception;

    default boolean isPersistent() {
        return true;
    }
}
