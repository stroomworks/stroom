/*
 * Copyright 2016-2025 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.common.v2;

import stroom.query.api.Column;
import stroom.query.api.OffsetRange;
import stroom.query.api.ParamUtil;
import stroom.query.api.Result;
import stroom.query.api.ResultRequest;
import stroom.query.api.ResultRequest.Fetch;
import stroom.query.api.TableResult;
import stroom.query.api.TableResultBuilder;
import stroom.query.api.TableSettings;
import stroom.query.common.v2.format.FormatterFactory;
import stroom.query.language.functions.ref.ErrorConsumer;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class TableResultCreator implements ResultCreator {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TableResultCreator.class);

    private final FormatterFactory formatterFactory;
    private final ExpressionPredicateFactory expressionPredicateFactory;

    private final ErrorConsumer errorConsumer = new ErrorConsumerImpl();
    private final boolean cacheLastResult;
    private TableResult lastResult;

    public TableResultCreator() {
        this(new FormatterFactory(null),
                new ExpressionPredicateFactory(),
                false);
    }

    public TableResultCreator(final FormatterFactory formatterFactory,
                              final ExpressionPredicateFactory expressionPredicateFactory) {
        this(formatterFactory, expressionPredicateFactory, false);
    }

    public TableResultCreator(final FormatterFactory formatterFactory,
                              final ExpressionPredicateFactory expressionPredicateFactory,
                              final boolean cacheLastResult) {
        this.formatterFactory = formatterFactory;
        this.expressionPredicateFactory = expressionPredicateFactory;
        this.cacheLastResult = cacheLastResult;
    }

    public TableResultBuilder createTableResultBuilder() {
        return TableResult.builder();
    }

    @Override
    public Result create(final DataStore dataStore, final ResultRequest resultRequest) {
        errorConsumer.clear();

        final Fetch fetch = resultRequest.getFetch();
        if (Fetch.NONE.equals(fetch)) {
            return null;
        }

        final TableResultBuilder resultBuilder = createTableResultBuilder();
        final KeyFactory keyFactory = dataStore.getKeyFactory();
        final AtomicLong pageLength = new AtomicLong();
        final OffsetRange range = resultRequest.getRequestedRange();

        try {
            // What is the interaction between the paging and the maxResults? The assumption is that
            // maxResults defines the max number of records to come back and the paging can happen up to
            // that maxResults threshold
            final TableSettings tableSettings = resultRequest.getMappings().getFirst();
            final List<Column> columns = enrichColumns(dataStore, tableSettings);
            resultBuilder.columns(columns);

            if (RowValueFilter.matches(columns)) {
                ItemMapper mapper;
                mapper = SimpleMapper.create(dataStore.getColumns(), columns);
                mapper = FilteredMapper.create(
                        columns,
                        tableSettings.applyValueFilters(),
                        tableSettings.getAggregateFilter(),
                        dataStore.getDateTimeSettings(),
                        errorConsumer,
                        expressionPredicateFactory,
                        mapper);

                mapper = ConditionalFormattingMapper.create(resultRequest.getSourceComponentId(),
                        resultRequest.getSourceComponentName(),
                        columns,
                        tableSettings.getConditionalFormattingRules(),
                        dataStore.getDateTimeSettings(),
                        expressionPredicateFactory,
                        errorConsumer,
                        mapper);

                final RowCreator rowCreator = SimpleRowCreator
                        .create(columns, formatterFactory, keyFactory, errorConsumer);
                final OpenGroups openGroups = OpenGroupsImpl.fromGroupSelection(
                        resultRequest.getGroupSelection(), keyFactory);

                dataStore.fetch(
                        columns,
                        range,
                        openGroups,
                        resultRequest.getTimeFilter(),
                        mapper,
                        item -> {
                            resultBuilder.addRow(rowCreator.create(item));
                            pageLength.incrementAndGet();
                        },
                        resultBuilder::totalResults);
            }
        } catch (final RuntimeException e) {
            LOGGER.debug(e.getMessage(), e);
            errorConsumer.add(e);
        }

        long offset = 0;
        if (range != null) {
            offset = range.getOffset();
        }

        resultBuilder.componentId(resultRequest.getComponentId());
        resultBuilder.errorMessages(errorConsumer.getErrorMessages());
        resultBuilder.resultRange(new OffsetRange(offset, pageLength.get()));
        TableResult result = resultBuilder.build();

        if (cacheLastResult) {
            if (Fetch.CHANGES.equals(fetch)) {
                // See if we have delivered an identical result before, so we
                // don't send more data to the client than we need to.
                if (result.equals(lastResult)) {
                    result = null;
                } else {
                    lastResult = result;
                }
            } else {
                lastResult = result;
            }
        }

        LOGGER.debug("Delivering {} for {}", result, resultRequest.getComponentId());
        return result;
    }

    private List<Column> enrichColumns(final DataStore dataStore, final TableSettings tableSettings) {
        final List<Column> columns = new ArrayList<>(NullSafe.list(
                NullSafe.get(tableSettings, TableSettings::getColumns)));

        if (dataStore != null) {
            final List<Column> dataStoreColumns = dataStore.getColumns();
            if (dataStoreColumns != null) {
                final Map<String, String> fieldToTypeMap = dataStoreColumns.stream()
                        .filter(c -> c.getName() != null && c.getDomainType() != null)
                        .collect(Collectors.toMap(Column::getName, Column::getDomainType, (v1, v2) -> v1));

                if (!fieldToTypeMap.isEmpty()) {
                    return columns.stream()
                            .map(column -> {
                                if (column.getDomainType() == null && column.getExpression() != null) {
                                    final List<String> keys = ParamUtil.getKeys(column.getExpression());
                                    if (keys.size() == 1) {
                                        final String fieldName = keys.getFirst();
                                        final String domainType = fieldToTypeMap.get(fieldName);
                                        if (domainType != null) {
                                            return column.copy().domainType(domainType).build();
                                        }
                                    }
                                }
                                return column;
                            })
                            .collect(Collectors.toList());
                }
            }
        }
        return columns;
    }
}
