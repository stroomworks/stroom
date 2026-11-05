/*
 * Copyright 2026 Crown Copyright
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

package stroom.egress.impl;

import stroom.db.util.JooqUtil;
import stroom.egress.api.EgressRule;
import stroom.egress.impl.db.EgressDbConnProvider;
import stroom.egress.shared.EgressKind;
import stroom.egress.shared.EgressMatch;
import stroom.egress.shared.EgressMode;

import jakarta.inject.Inject;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// The database side of the egress policy. It uses jOOQ's plain-SQL form, so that it does not depend on generated
/// classes: the schema is two small tables, created by the Flyway migration in `stroom-egress-impl-db`.
class EgressDaoImpl implements EgressDao {

    private static final Table<Record> RULE = DSL.table("egress_rule");
    private static final Field<Long> RULE_ID = DSL.field("id", Long.class);
    private static final Field<String> RULE_KIND = DSL.field("kind", String.class);
    private static final Field<String> RULE_DESTINATION = DSL.field("destination", String.class);
    private static final Field<String> RULE_MATCH = DSL.field("match_type", String.class);
    private static final Field<String> RULE_COMMENT = DSL.field("comment", String.class);
    private static final Field<Boolean> RULE_ENABLED = DSL.field("enabled", Boolean.class);
    private static final Field<Long> RULE_CREATE_TIME = DSL.field("create_time_ms", Long.class);
    private static final Field<String> RULE_CREATE_USER = DSL.field("create_user", String.class);
    private static final Field<Long> RULE_UPDATE_TIME = DSL.field("update_time_ms", Long.class);
    private static final Field<String> RULE_UPDATE_USER = DSL.field("update_user", String.class);

    private static final Table<Record> SETTING = DSL.table("egress_setting");
    private static final Field<String> SETTING_KIND = DSL.field("kind", String.class);
    private static final Field<String> SETTING_MODE = DSL.field("mode", String.class);
    private static final Field<Boolean> SETTING_KILL = DSL.field("kill_switch", Boolean.class);
    private static final Field<Long> SETTING_UPDATE_TIME = DSL.field("update_time_ms", Long.class);
    private static final Field<String> SETTING_UPDATE_USER = DSL.field("update_user", String.class);

    private final EgressDbConnProvider connectionProvider;

    @Inject
    EgressDaoImpl(final EgressDbConnProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public List<EgressRule> fetchRules() {
        return JooqUtil.contextResult(connectionProvider, context -> context
                .select(RULE_ID, RULE_KIND, RULE_DESTINATION, RULE_MATCH, RULE_COMMENT, RULE_ENABLED)
                .from(RULE)
                .orderBy(RULE_ID)
                .fetch(record -> new EgressRule(
                        record.get(RULE_ID),
                        EgressKind.valueOf(record.get(RULE_KIND)),
                        record.get(RULE_DESTINATION),
                        EgressMatch.valueOf(record.get(RULE_MATCH)),
                        record.get(RULE_COMMENT),
                        Boolean.TRUE.equals(record.get(RULE_ENABLED)))));
    }

    @Override
    public EgressRule saveRule(final EgressRule rule, final String user) {
        final long now = System.currentTimeMillis();
        return JooqUtil.contextResult(connectionProvider, context -> {
            if (rule.id() == null) {
                context
                        .insertInto(RULE)
                        .set(RULE_KIND, rule.kind().name())
                        .set(RULE_DESTINATION, rule.destination())
                        .set(RULE_MATCH, rule.match().name())
                        .set(RULE_COMMENT, rule.comment())
                        .set(RULE_ENABLED, rule.enabled())
                        .set(RULE_CREATE_TIME, now)
                        .set(RULE_CREATE_USER, user)
                        .set(RULE_UPDATE_TIME, now)
                        .set(RULE_UPDATE_USER, user)
                        .execute();
                // The plain-SQL form has no generated key to return, so ask the connection for it.
                final Long id = context
                        .select(DSL.field("LAST_INSERT_ID()", Long.class))
                        .fetchOne(0, Long.class);
                return new EgressRule(
                        id, rule.kind(), rule.destination(), rule.match(), rule.comment(), rule.enabled());
            }
            final int updated = context
                    .update(RULE)
                    .set(RULE_KIND, rule.kind().name())
                    .set(RULE_DESTINATION, rule.destination())
                    .set(RULE_MATCH, rule.match().name())
                    .set(RULE_COMMENT, rule.comment())
                    .set(RULE_ENABLED, rule.enabled())
                    .set(RULE_UPDATE_TIME, now)
                    .set(RULE_UPDATE_USER, user)
                    .where(RULE_ID.eq(rule.id()))
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("There is no egress rule with id " + rule.id());
            }
            return rule;
        });
    }

    @Override
    public boolean deleteRule(final long id) {
        return JooqUtil.contextResult(connectionProvider, context -> {
            final int deleted = context
                    .deleteFrom(RULE)
                    .where(RULE_ID.eq(id))
                    .execute();
            return deleted > 0;
        });
    }

    @Override
    public Map<EgressKind, EgressMode> fetchModes() {
        final Map<EgressKind, EgressMode> modes = new EnumMap<>(EgressKind.class);
        JooqUtil.context(connectionProvider, context -> context
                .select(SETTING_KIND, SETTING_MODE)
                .from(SETTING)
                .fetch()
                .forEach(record -> modes.put(
                        EgressKind.valueOf(record.get(SETTING_KIND)),
                        EgressMode.valueOf(record.get(SETTING_MODE)))));
        return modes;
    }

    @Override
    public Map<EgressKind, Boolean> fetchKillSwitches() {
        final Map<EgressKind, Boolean> killed = new EnumMap<>(EgressKind.class);
        JooqUtil.context(connectionProvider, context -> context
                .select(SETTING_KIND, SETTING_KILL)
                .from(SETTING)
                .fetch()
                .forEach(record -> killed.put(
                        EgressKind.valueOf(record.get(SETTING_KIND)),
                        Boolean.TRUE.equals(record.get(SETTING_KILL)))));
        return killed;
    }

    @Override
    public void saveMode(final EgressKind kind, final EgressMode mode, final String user) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connectionProvider, context -> context
                .insertInto(SETTING)
                .set(SETTING_KIND, kind.name())
                .set(SETTING_MODE, mode.name())
                .set(SETTING_KILL, false)
                .set(SETTING_UPDATE_TIME, now)
                .set(SETTING_UPDATE_USER, user)
                .onDuplicateKeyUpdate()
                .set(SETTING_MODE, mode.name())
                .set(SETTING_UPDATE_TIME, now)
                .set(SETTING_UPDATE_USER, user)
                .execute());
    }

    @Override
    public void saveKilled(final EgressKind kind, final boolean killed, final String user) {
        final long now = System.currentTimeMillis();
        JooqUtil.context(connectionProvider, context -> context
                .insertInto(SETTING)
                .set(SETTING_KIND, kind.name())
                .set(SETTING_MODE, EgressMode.REPORT_ONLY.name())
                .set(SETTING_KILL, killed)
                .set(SETTING_UPDATE_TIME, now)
                .set(SETTING_UPDATE_USER, user)
                .onDuplicateKeyUpdate()
                .set(SETTING_KILL, killed)
                .set(SETTING_UPDATE_TIME, now)
                .set(SETTING_UPDATE_USER, user)
                .execute());
    }
}
