package stroom.visualisation.shared;

import stroom.docs.shared.Description;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Holds the data about a web asset within a visualisation.
 */
@Description(
        "Holds the data on a web asset within a visualisation."
)
@JsonPropertyOrder({
        "id",
        "path",
        "isFolder"
})
@JsonInclude(Include.NON_NULL)
public class VisualisationAsset {

    @JsonProperty
    private String id;

    @JsonProperty
    private String path;

    @JsonProperty
    private boolean isFolder;

    @JsonCreator
    public VisualisationAsset(@JsonProperty("id") final String id,
                              @JsonProperty("path") final String path,
                              @JsonProperty("isFolder") final boolean isFolder) {
        this.id = id;
        this.path = path;
        this.isFolder = isFolder;
    }

    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getPath() {
        return path;
    }

    public void setPath(final String path) {
        this.path = path;
    }

    public boolean isFolder() {
        return isFolder;
    }

    public void setFolder(final boolean folder) {
        isFolder = folder;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final VisualisationAsset that = (VisualisationAsset) o;
        return isFolder == that.isFolder && Objects.equals(id, that.id) && Objects.equals(path,
                that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, path, isFolder);
    }
}
