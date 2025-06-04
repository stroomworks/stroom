package stroom.annotation.shared;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ChangeTitle.class, name = "title"),
        @JsonSubTypes.Type(value = ChangeSubject.class, name = "subject"),
        @JsonSubTypes.Type(value = AddTag.class, name = "addTag"),
        @JsonSubTypes.Type(value = RemoveTag.class, name = "removeTag"),
        @JsonSubTypes.Type(value = SetTag.class, name = "setTag"),
        @JsonSubTypes.Type(value = ChangeAssignedTo.class, name = "assignedTo"),
        @JsonSubTypes.Type(value = ChangeComment.class, name = "comment"),
        @JsonSubTypes.Type(value = ChangeRetentionPeriod.class, name = "retentionPeriod"),
        @JsonSubTypes.Type(value = ChangeDescription.class, name = "description"),
        @JsonSubTypes.Type(value = LinkEvents.class, name = "link"),
        @JsonSubTypes.Type(value = UnlinkEvents.class, name = "unlink")
})
public abstract class AbstractAnnotationChange {

}
