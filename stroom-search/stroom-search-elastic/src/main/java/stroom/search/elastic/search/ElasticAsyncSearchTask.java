/*
 * Copyright 2017 Crown Copyright
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

package stroom.search.elastic.search;

import stroom.query.api.DateTimeSettings;
import stroom.query.api.Query;
import stroom.query.api.QueryKey;
import stroom.query.common.v2.CoprocessorSettings;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class ElasticAsyncSearchTask {

    private final QueryKey key;
    private final String searchName;
    private final Query query;
    @JsonProperty
    private final List<CoprocessorSettings> settings;
    private final DateTimeSettings dateTimeSettings;

    public ElasticAsyncSearchTask(final QueryKey key,
                                  final String searchName,
                                  final Query query,
                                  @JsonProperty("settings") final List<CoprocessorSettings> settings,
                                  final DateTimeSettings dateTimeSettings) {
        this.key = key;
        this.searchName = searchName;
        this.query = query;
        this.settings = settings;
        this.dateTimeSettings = dateTimeSettings;
    }

    public QueryKey getKey() {
        return key;
    }

    public String getSearchName() {
        return searchName;
    }

    public Query getQuery() {
        return query;
    }

    public List<CoprocessorSettings> getSettings() {
        return settings;
    }

    public DateTimeSettings getDateTimeSettings() {
        return dateTimeSettings;
    }
}
