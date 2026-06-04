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

package stroom.sqlstore.api;

import stroom.docref.DocRef;
import stroom.entity.shared.ExpressionCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.SearchProvider;
import stroom.util.shared.HasCrud;
import stroom.util.shared.ResultPage;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import java.util.List;

public interface UpdatableTemporalStore extends HasCrud<TemporalEntry, TemporalEntryId>, SearchProvider {

    QueryField MAP_FIELD = QueryField.createText("Map");
    QueryField KEY_FIELD = QueryField.createText("Key");
    QueryField TIME_FIELD = QueryField.createDate("EffectiveTime");
    QueryField VALUE_FIELD = QueryField.createText("Value");

    List<QueryField> FIELDS = List.of(
            MAP_FIELD,
            KEY_FIELD,
            TIME_FIELD,
            VALUE_FIELD
    );

    ResultPage<TemporalEntry> find(ExpressionCriteria criteria);

    void clear(String mapName);

    long count(DocRef docRef);
}
