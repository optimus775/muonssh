package muon.app.common.secrets;

public class SecretStoreLockedException extends Exception {

    public SecretStoreLockedException(String message) {
        super(message);
    }
}
