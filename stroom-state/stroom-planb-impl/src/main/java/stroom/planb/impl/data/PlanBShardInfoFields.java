package stroom.planb.impl.data;

import stroom.docref.DocRef;
import stroom.query.api.datasource.ConditionSet;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;

import java.util.Arrays;
import java.util.List;

public class PlanBShardInfoFields {

    public static final DocRef PLAN_B_SHARD_INFO_PSEUDO_DOC_REF = new DocRef(
            "PlanBShards",
            "PlanBShards",
            "Plan B Shards");
    public static final QueryField NAME_FIELD = QueryField
            .builder()
            .fldName("Name")
            .fldType(FieldType.TEXT)
            .conditionSet(ConditionSet.BASIC_TEXT)
            .queryable(true)
            .build();
    public static final QueryField SCHEMA_TYPE_FIELD = QueryField
            .builder()
            .fldName("Schema Type")
            .fldType(FieldType.TEXT)
            .conditionSet(ConditionSet.BASIC_TEXT)
            .queryable(true)
            .build();
    public static final QueryField NODE_FIELD = QueryField
            .builder()
            .fldName("Node")
            .fldType(FieldType.TEXT)
            .conditionSet(ConditionSet.BASIC_TEXT)
            .queryable(true)
            .build();
    public static final QueryField SHARD_TYPE_FIELD = QueryField
            .builder()
            .fldName("Shard Type")
            .fldType(FieldType.TEXT)
            .conditionSet(ConditionSet.BASIC_TEXT)
            .queryable(true)
            .build();
    public static final QueryField BYTE_SIZE_FIELD = QueryField
            .builder()
            .fldName("Byte Size")
            .fldType(FieldType.LONG)
            .conditionSet(ConditionSet.DEFAULT_NUMERIC)
            .queryable(true)
            .build();
    public static final QueryField SETTINGS_FIELD = QueryField
            .builder()
            .fldName("Settings")
            .fldType(FieldType.TEXT)
            .conditionSet(ConditionSet.BASIC_TEXT)
            .queryable(false)
            .build();

    public static final List<QueryField> FIELDS = Arrays.asList(
            NAME_FIELD,
            SCHEMA_TYPE_FIELD,
            NODE_FIELD,
            SHARD_TYPE_FIELD,
            BYTE_SIZE_FIELD,
            SETTINGS_FIELD);

    public static List<QueryField> getFields() {
        return FIELDS;
    }
}
