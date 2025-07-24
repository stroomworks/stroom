package stroom.pipeline.shared.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

public final class ElementId {
    private final String id;
    private final String name;

    @JsonCreator
    public ElementId(@JsonProperty("id") String id) {
        this(id, id);
    }

    @JsonCreator
    public static ElementId fromString(String id) {
        return new ElementId(id);
    }

    @JsonValue
    public String asString() {
        return id;
    }

    public ElementId(@JsonProperty("id") String id, @JsonProperty("name") String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String id() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        if (Objects.equals(id, name)) {
            return id;
        }
        return name + " (" + id + ")";
    }

    public int compareTo(final ElementId other) {
        if (other == null) {
            return 1;
        }
        int result = id.compareTo(other.id);
        if (result == 0) {
            result = name.compareTo(other.name);
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ElementId)) {
            return false;
        }
        final ElementId that = (ElementId) o;
        return Objects.equals(id, that.id) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name);
    }
}
