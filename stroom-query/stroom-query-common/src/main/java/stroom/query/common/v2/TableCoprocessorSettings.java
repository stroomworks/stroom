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

package stroom.query.common.v2;

import stroom.query.api.TableSettings;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(Include.NON_NULL)
public class TableCoprocessorSettings implements CoprocessorSettings {
    @JsonProperty
    private final int coprocessorId;
    @JsonProperty
    private final String[] componentIds;
    @JsonProperty
    private final TableSettings tableSettings;

    @JsonCreator
    public TableCoprocessorSettings(@JsonProperty("coprocessorId") final int coprocessorId,
                                    @JsonProperty("componentIds") final String[] componentIds,
                                    @JsonProperty("tableSettings") final TableSettings tableSettings) {
        this.coprocessorId = coprocessorId;
        this.componentIds = componentIds;
        this.tableSettings = tableSettings;
    }

    @Override
    public int getCoprocessorId() {
        return coprocessorId;
    }

    public String[] getComponentIds() {
        return componentIds;
    }

    public TableSettings getTableSettings() {
        return tableSettings;
    }
}
