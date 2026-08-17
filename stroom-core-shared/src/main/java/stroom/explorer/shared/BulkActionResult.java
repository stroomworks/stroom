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

package stroom.explorer.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(Include.NON_NULL)
public class BulkActionResult {

    @JsonProperty
    private final List<ExplorerNode> explorerNodes;
    @JsonProperty
    private final String message;
    @JsonProperty
    private final List<String> warnings;

    @JsonCreator
    public BulkActionResult(@JsonProperty("explorerNodes") final List<ExplorerNode> explorerNodes,
                            @JsonProperty("message") final String message,
                            @JsonProperty("warnings") final List<String> warnings) {
        this.explorerNodes = explorerNodes;
        this.message = message;
        this.warnings = warnings;
    }

    public BulkActionResult(final List<ExplorerNode> explorerNodes,
                            final String message) {
        this(explorerNodes, message, null);
    }

    public List<ExplorerNode> getExplorerNodes() {
        return explorerNodes;
    }

    /**
     * @return why some of the requested items could not be actioned at all, or null/blank if they all
     * were. Distinct from {@link #getWarnings()}, which reports on items that <i>were</i> actioned.
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return things the action did that the user needs to know about, having succeeded. Chiefly a copy
     * whose references could not be repointed, since the new document is not the independent duplicate
     * the user is entitled to assume it is. May be null where there is nothing to say.
     */
    public List<String> getWarnings() {
        return warnings;
    }
}
