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

package stroom.meta.impl;

import stroom.data.retention.api.DataRetentionRulesProvider;
import stroom.data.retention.shared.DataRetentionRule;
import stroom.data.retention.shared.DataRetentionRules;
import stroom.expression.matcher.ExpressionMatcher;
import stroom.expression.matcher.ExpressionMatcherFactory;
import stroom.meta.shared.DataRetentionFields;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.query.api.ExpressionOperator;
import stroom.util.date.DateUtil;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// TODO STROOMWORKS-LOCAL WORKAROUND - PREFER UPSTREAM ON MERGE FROM master
// Local change from "Provisional fix to Authentication Exception". The commit message calls it
// provisional, so treat it as a stopgap: if upstream has addressed the same authentication
// exception, take their fix and delete this. Diff against origin/master before resolving.
class StreamAttributeMapRetentionRuleDecorator {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamAttributeMapRetentionRuleDecorator.class);

    private final List<DataRetentionRule> rules;
    private final ExpressionMatcher expressionMatcher;
    private final StreamAttributeMapConverter streamAttributeMapConverter;

    /**
     * Non-null if we failed to load the retention rules, in which case we report the failure in the
     * retention columns rather than failing the whole request.
     */
    private final String rulesLoadFailureMessage;

    @Inject
    public StreamAttributeMapRetentionRuleDecorator(final ExpressionMatcherFactory expressionMatcherFactory,
                                                    final DataRetentionRulesProvider dataRetentionRulesProvider,
                                                    final StreamAttributeMapConverter streamAttributeMapConverter) {
        this.streamAttributeMapConverter = streamAttributeMapConverter;
        this.expressionMatcher = expressionMatcherFactory.create(MetaFields.getFieldMap());

        // Retention info is only decoration on the rows we have already found, so a failure to load the
        // rules must not stop the caller seeing its data. Degrade to no rules and report it per row.
        List<DataRetentionRule> rules = Collections.emptyList();
        String rulesLoadFailureMessage = null;
        try {
            rules = Optional.ofNullable(dataRetentionRulesProvider)
                    .map(DataRetentionRulesProvider::getOrCreate)
                    .map(DataRetentionRules::getRules)
                    .orElse(Collections.emptyList());
        } catch (final RuntimeException e) {
            rulesLoadFailureMessage = Objects.requireNonNullElseGet(
                    e.getMessage(), () -> e.getClass().getSimpleName());
            LOGGER.error("Error loading data retention rules, retention info will be unavailable: {}",
                    rulesLoadFailureMessage, e);
        }
        this.rules = rules;
        this.rulesLoadFailureMessage = rulesLoadFailureMessage;
    }

    void addMatchingRetentionRuleInfo(final Meta meta, final Map<String, String> attributeMap) {
        if (rulesLoadFailureMessage != null) {
            setErrorRetentionRuleInfo(attributeMap, rulesLoadFailureMessage);
            return;
        }
        try {
            int index = -1;

            // If there are no active rules then we aren't going to process anything.
            if (NullSafe.hasItems(rules)) {
                // Create an attribute map we can match on.
                final Map<String, Object> map = streamAttributeMapConverter.createAttributeMap(meta, attributeMap);
                index = findMatchingRuleIndex(map);
            }

            if (index > -1) {
                final DataRetentionRule rule = rules.get(index);
                attributeMap.put(DataRetentionFields.RETENTION_AGE, rule.getAgeString());

                String keepUntil = DataRetentionRule.FOREVER;
                if (meta != null) {
                    final long millis = meta.getCreateMs();
                    final LocalDateTime createTime = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDateTime();
                    final Long ms = DataRetentionAgeUtil.plus(createTime, rule);
                    if (ms != null) {
                        keepUntil = DateUtil.createNormalDateTimeString(ms);
                    }
                }

                attributeMap.put(DataRetentionFields.RETENTION_UNTIL, keepUntil);
                attributeMap.put(DataRetentionFields.RETENTION_RULE, rule.toString());
            } else {
                attributeMap.put(DataRetentionFields.RETENTION_AGE, DataRetentionRule.FOREVER);
                attributeMap.put(DataRetentionFields.RETENTION_UNTIL, DataRetentionRule.FOREVER);
                attributeMap.put(DataRetentionFields.RETENTION_RULE, "None");
            }
        } catch (final RuntimeException e) {
            final String msg = Objects.requireNonNullElseGet(e.getMessage(), () -> e.getClass().getSimpleName());
            setErrorRetentionRuleInfo(attributeMap, msg);
        }
    }

    private void setErrorRetentionRuleInfo(final Map<String, String> attributeMap, final String msg) {
        attributeMap.put(DataRetentionFields.RETENTION_AGE, DataRetentionRule.FOREVER);
        attributeMap.put(DataRetentionFields.RETENTION_UNTIL, DataRetentionRule.FOREVER);
        attributeMap.put(DataRetentionFields.RETENTION_RULE, "Error - " + msg);
    }

    private int findMatchingRuleIndex(final Map<String, Object> attributeMap) {
        RuntimeException lastException = null;

        for (int i = 0; i < rules.size(); i++) {
            try {
                final DataRetentionRule rule = rules.get(i);
                // We will ignore rules that are not enabled or have no enabled expression.
                if (rule.isEnabled()) {
                    final ExpressionOperator expression = rule.getExpression();
                    if (NullSafe.test(expression, ExpressionOperator::enabled)) {
                        if (expressionMatcher.match(attributeMap, expression)) {
                            return i;
                        }
                    }
                }
            } catch (final RuntimeException e) {
                lastException = e;
                LOGGER.debug(e.getMessage(), e);
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        return -1;
    }
}
