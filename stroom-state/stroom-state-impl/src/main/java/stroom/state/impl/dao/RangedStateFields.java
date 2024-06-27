package stroom.state.impl.dao;

import stroom.datasource.api.v2.QueryField;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public interface RangedStateFields {
    String KEY_START = "KeyStart";
    String KEY_END = "KeyEnd";
    String VALUE_TYPE = "ValueType";
    String VALUE = "Value";
    String INSERT_TIME = "InsertTime";

    QueryField KEY_START_FIELD = QueryField.createLong(KEY_START);
    QueryField KEY_END_FIELD = QueryField.createText(KEY_END);
    QueryField VALUE_TYPE_FIELD = QueryField.createText(VALUE_TYPE, false);
    QueryField VALUE_FIELD = QueryField.createText(VALUE, false);
    QueryField INSERT_TIME_FIELD = QueryField.createText(INSERT_TIME, false);

    List<QueryField> FIELDS = Arrays.asList(
            KEY_START_FIELD,
            KEY_END_FIELD,
            VALUE_TYPE_FIELD,
            VALUE_FIELD,
            INSERT_TIME_FIELD);

    Map<String, QueryField> FIELD_MAP = Map.of(
            KEY_START, KEY_START_FIELD,
            KEY_END, KEY_END_FIELD,
            VALUE_TYPE, VALUE_TYPE_FIELD,
            VALUE, VALUE_FIELD,
            INSERT_TIME, INSERT_TIME_FIELD);
}