package muon.app.common.secrets;

import com.sun.jna.LastErrorException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.win32.W32APITypeMapper;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class WindowsCredentialStore implements SecretStore {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final String TARGET_PREFIX = "MuonSSH/";

    private final WindowsCredentialApi api;

    public WindowsCredentialStore() {
        this.api = Native.load("Advapi32", WindowsCredentialApi.class, W32APIOptions.DEFAULT_OPTIONS);
    }

    @Override
    public boolean isAvailable() {
        try {
            PointerByReference credential = new PointerByReference();
            if (api.CredReadW(new WString(TARGET_PREFIX + "__availability_check__"), CRED_TYPE_GENERIC, 0, credential)) {
                api.CredFree(credential.getValue());
                return true;
            }
            return Kernel32.INSTANCE.GetLastError() == ERROR_NOT_FOUND;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            log.warn("Windows Credential Manager is not available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public String backendName() {
        return "Windows Credential Manager";
    }

    @Override
    public char[] get(String alias) {
        PointerByReference credentialRef = new PointerByReference();
        if (!api.CredReadW(new WString(target(alias)), CRED_TYPE_GENERIC, 0, credentialRef)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_NOT_FOUND) {
                return null;
            }
            throw new LastErrorException(error);
        }

        try {
            CREDENTIAL credential = new CREDENTIAL(credentialRef.getValue());
            credential.read();
            if (credential.CredentialBlob == null || credential.CredentialBlobSize <= 0) {
                return null;
            }
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            try {
                return new String(bytes, StandardCharsets.UTF_8).toCharArray();
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            api.CredFree(credentialRef.getValue());
        }
    }

    @Override
    public void set(String alias, char[] secret) {
        if (secret == null || secret.length == 0) {
            delete(alias);
            return;
        }
        byte[] bytes = toUtf8(secret);
        try {
            Memory blob = new Memory(bytes.length);
            blob.write(0, bytes, 0, bytes.length);

            CREDENTIAL credential = new CREDENTIAL();
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = new WString(target(alias));
            credential.CredentialBlobSize = bytes.length;
            credential.CredentialBlob = blob;
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.UserName = new WString("MuonSSH");
            credential.write();

            if (!api.CredWriteW(credential, 0)) {
                throw new LastErrorException(Kernel32.INSTANCE.GetLastError());
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    @Override
    public void delete(String alias) {
        if (!api.CredDeleteW(new WString(target(alias)), CRED_TYPE_GENERIC, 0)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error != ERROR_NOT_FOUND) {
                throw new LastErrorException(error);
            }
        }
    }

    private String target(String alias) {
        return TARGET_PREFIX + alias;
    }

    private byte[] toUtf8(char[] secret) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }

    private interface WindowsCredentialApi extends StdCallLibrary {
        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);

        boolean CredWriteW(CREDENTIAL credential, int flags);

        boolean CredDeleteW(WString targetName, int type, int flags);

        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({
            "Flags",
            "Type",
            "TargetName",
            "Comment",
            "LastWritten",
            "CredentialBlobSize",
            "CredentialBlob",
            "Persist",
            "AttributeCount",
            "Attributes",
            "TargetAlias",
            "UserName"
    })
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public WinBase.FILETIME LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public CREDENTIAL() {
            super(W32APITypeMapper.UNICODE);
        }

        public CREDENTIAL(Pointer pointer) {
            super(pointer, Structure.ALIGN_DEFAULT, W32APITypeMapper.UNICODE);
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("Flags", "Type", "TargetName", "Comment", "LastWritten", "CredentialBlobSize",
                    "CredentialBlob", "Persist", "AttributeCount", "Attributes", "TargetAlias", "UserName");
        }
    }
}
