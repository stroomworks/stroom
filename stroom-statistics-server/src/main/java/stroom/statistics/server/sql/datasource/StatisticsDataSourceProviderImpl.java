/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.statistics.server.sql.datasource;

import org.springframework.stereotype.Component;
import stroom.datasource.api.v2.DataSource;
import stroom.datasource.api.v2.DataSourceField;
import stroom.datasource.api.v2.DataSourceField.DataSourceFieldType;
import stroom.query.api.v2.DocRef;
import stroom.query.api.v2.ExpressionTerm.Condition;
import stroom.statistics.server.sql.Statistics;
import stroom.statistics.shared.StatisticStoreEntity;
import stroom.statistics.shared.StatisticType;
import stroom.statistics.shared.common.StatisticField;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
public class StatisticsDataSourceProviderImpl implements StatisticsDataSourceProvider {
    private final StatisticStoreCache statisticStoreCache;
    private final Statistics statistics;

    @Inject
    StatisticsDataSourceProviderImpl(final StatisticStoreCache statisticStoreCache,
                                     final Statistics statistics) {

        this.statisticStoreCache = statisticStoreCache;
        this.statistics = statistics;
    }

    @Override
    public DataSource getDataSource(final DocRef docRef) {
        final StatisticStoreEntity entity = statisticStoreCache.getStatisticsDataSource(docRef);
        if (entity == null) {
            return null;
        }

        final List<DataSourceField> fields = buildFields(entity);

        return new DataSource(fields);
    }

    /**
     * Turn the {@link StatisticStoreEntity} into an {@link List<DataSourceField>} object
     * <p>
     * This builds the standard set of fields for a statistics store, which can
     * be filtered by the relevant statistics store instance
     */
    private List<DataSourceField> buildFields(final StatisticStoreEntity entity) {
        List<DataSourceField> fields = new ArrayList<>();

        // TODO currently only BETWEEN is supported, but need to add support for
        // more conditions like >, >=, <, <=, =
        addField(StatisticStoreEntity.FIELD_NAME_DATE_TIME, DataSourceFieldType.DATE_FIELD, true,
                Collections.singletonList(Condition.BETWEEN), fields);

        // one field per tag
        if (entity.getStatisticDataSourceDataObject() != null) {
            final List<Condition> supportedConditions = Arrays.asList(Condition.EQUALS, Condition.IN);

            for (final StatisticField statisticField : entity.getStatisticFields()) {
                // TODO currently only EQUALS is supported, but need to add
                // support for more conditions like CONTAINS
                addField(statisticField.getFieldName(), DataSourceFieldType.FIELD, true,
                        supportedConditions, fields);
            }
        }

        addField(StatisticStoreEntity.FIELD_NAME_COUNT,
                DataSourceFieldType.NUMERIC_FIELD,
                false,
                Collections.emptyList(),
                fields);

        if (entity.getStatisticType().equals(StatisticType.VALUE)) {
            addField(StatisticStoreEntity.FIELD_NAME_VALUE,
                    DataSourceFieldType.NUMERIC_FIELD,
                    false,
                    Collections.emptyList(),
                    fields);
        }

        addField(StatisticStoreEntity.FIELD_NAME_PRECISION_MS,
                DataSourceFieldType.NUMERIC_FIELD,
                false,
                Collections.emptyList(),
                fields);

        // Filter fields.
        if (entity.getStatisticDataSourceDataObject() != null) {
            fields = statistics.getSupportedFields(fields);
        }

        return fields;
    }

    private void addField(final String name,
                          final DataSourceFieldType type,
                          final boolean isQueryable,
                          final List<Condition> supportedConditions,
                          final List<DataSourceField> fields) {
        final DataSourceField field = new DataSourceField.Builder()
                .type(type)
                .name(name)
                .queryable(isQueryable)
                .addConditions(supportedConditions.toArray(new Condition[0]))
                .build();
        fields.add(field);
    }
}