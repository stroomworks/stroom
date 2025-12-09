package stroom.visualisation.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/**
 * Holds the data about a web asset within a visualisation,
 * as part of a VisualisationDoc.
 */
@Description(
        "Holds the data on a web asset within a visualisation."
)
@JsonPropertyOrder({
        "id",
        "path",
        "folder"
})
@JsonInclude(Include.NON_NULL)
public class VisualisationAsset {

    @JsonProperty
    private DocRef ownerDocRef;

    @JsonProperty
    private String id;

    @JsonProperty
    private String path;

    @JsonProperty
    private boolean folder;

    @JsonCreator
    public VisualisationAsset(@JsonProperty("ownerDocRef") final DocRef ownerDocRef,
                              @JsonProperty("id") final String id,
                              @JsonProperty("path") final String path,
                              @JsonProperty("folder") final boolean folder) {
        this.ownerDocRef = ownerDocRef;
        this.id = id;
        this.path = path;
        this.folder = folder;
    }

    public DocRef getOwnerDocRef() {
        return ownerDocRef;
    }

    public void setOwnerDocRef(final DocRef ownerDocRef) {
        this.ownerDocRef = ownerDocRef;
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
        return folder;
    }

    public void setFolder(final boolean folder) {
        this.folder = folder;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final VisualisationAsset that = (VisualisationAsset) o;
        return folder == that.folder && Objects.equals(id, that.id) && Objects.equals(path,
                that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, path, folder);
    }

    @Override
    public String toString() {
        return "VisualisationAsset{" +
               "id='" + id + '\'' +
               ", path='" + path + '\'' +
               ", isFolder=" + folder +
               '}';
    }
}
