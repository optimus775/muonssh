package muon.app.common;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import muon.app.util.TimeUtils;
import muon.app.util.enums.FileType;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
@Slf4j
public class FileInfo implements Serializable {
    private static final Pattern USER_REGEX = Pattern
            .compile("^[^\\s]+\\s+[^\\s]+\\s+([^\\s]+)\\s+([^\\s]+)");
    private final String name;
    private String path;
    private long size;
    private FileType type;
    private LocalDateTime lastModified;
    private LocalDateTime created;
    private int permission;
    private String protocol;
    private String permissionString;
    private String extra;
    private String user;
    private String group;
    private int uid = -1;
    private int gid = -1;
    private boolean hidden;

    public FileInfo(String name, String path, long size, FileType type,
                    long lastModified, int permission, String protocol,
                    String permissionString, long created, String extra,
                    boolean hidden) {
        super();
        this.name = name;
        this.path = path;
        this.size = size;
        this.type = type;
        this.lastModified = TimeUtils.toDateTime(lastModified);
        this.permission = permission;
        this.protocol = protocol;
        this.permissionString = permissionString;
        this.created = TimeUtils.toDateTime(created);
        this.extra = extra;
        if (this.extra != null && !this.extra.isEmpty()) {
            setUserGroupFromExtra();
        }
        this.hidden = hidden;
    }

    private void setUserGroupFromExtra() {
        try {
            if (this.extra != null && !this.extra.isEmpty()) {
                Matcher matcher = USER_REGEX.matcher(this.extra);
                if (matcher.find()) {
                    this.user = matcher.group(1);
                    this.group = matcher.group(2);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    public void setLastModified(long lastModified) {
        this.lastModified = TimeUtils.toDateTime(lastModified);
    }

    @Override
    public String toString() {
        return name;
    }

    public boolean isDirectory() {
        return getType() == FileType.DIRECTORY || getType() == FileType.DIR_LINK;
    }
}
