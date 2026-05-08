package muon.app.vps;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderRecord {
    private String id;
    private String name;
    private String slug;
    private String website;
    private String accountId;
    private String notes;
    private String tags;
    private long updatedAt = System.currentTimeMillis();
    private boolean deleted;

    @JsonIgnore
    public boolean isBlank() {
        return id == null && (name == null || name.isBlank());
    }

    @Override
    public String toString() {
        return name == null || name.isBlank() ? "No provider" : name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ProviderRecord)) {
            return false;
        }
        ProviderRecord that = (ProviderRecord) obj;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
