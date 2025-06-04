package stroom.query.common.v2;

import stroom.query.api.SearchRequestSource.SourceType;

public class KeyFactoryConfigImpl implements KeyFactoryConfig {

    public static final String DEFAULT_TIME_FIELD_NAME = "__time__";
    public static final String FALLBACK_TIME_FIELD_NAME = "EventTime";

    private int timeFieldIndex = -1;
    private boolean addTimeToKey;

    public KeyFactoryConfigImpl(final SourceType sourceType,
                                final CompiledColumn[] compiledColumns,
                                final CompiledDepths compiledDepths) {
        boolean timeGrouped = false;

        for (int i = 0; i < compiledColumns.length; i++) {
            final CompiledColumn column = compiledColumns[i];
            if (sourceType.isRequireTimeValue() &&
                    DEFAULT_TIME_FIELD_NAME.equalsIgnoreCase(column.getColumn().getName())) {
                timeFieldIndex = i;
                if (column.getGroupDepth() >= 0) {
                    timeGrouped = true;
                }
            }
        }

        for (int i = 0; i < compiledColumns.length; i++) {
            final CompiledColumn column = compiledColumns[i];
            if (sourceType.isRequireTimeValue() &&
                    FALLBACK_TIME_FIELD_NAME.equalsIgnoreCase(column.getColumn().getName())) {
                if (column.getGroupDepth() >= 0) {
                    if (!timeGrouped) {
                        timeFieldIndex = i;
                        timeGrouped = true;
                    }
                } else if (timeFieldIndex == -1) {
                    timeFieldIndex = i;
                }
            }
        }

        if ((!compiledDepths.hasGroup() && timeFieldIndex != -1) || (compiledDepths.hasGroup() && timeGrouped)) {
            addTimeToKey = true;
        } else {
            timeFieldIndex = -1;
        }

        if (sourceType.isRequireTimeValue() && timeFieldIndex == -1) {
            throw new RuntimeException("Time field required but not found.");
        }
    }

    @Override
    public int getTimeColumnIndex() {
        return timeFieldIndex;
    }

    @Override
    public boolean addTimeToKey() {
        return addTimeToKey;
    }
}
