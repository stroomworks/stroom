/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.sqlstore.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Request DTO for {@link SqlTemporalStoreResource#fetchAtTime}.
 *
 * <p>Specifies which map to query and the point in time at which the store
 * should be read. For each key in the map the server returns the single entry
 * whose {@code effective_time} is the greatest value that is still
 * {@code ≤ timeTo}, giving a consistent snapshot of the map as it appeared
 * at {@code timeTo}.</p>
 *
 * <h3>Choosing {@code timeTo}</h3>
 * <p>Pass the timeline slider position <strong>exactly</strong> — no buffer
 * should be added. The user has explicitly chosen this instant; adding any
 * margin would silently return data from the future relative to their
 * chosen time.</p>
 *
 * <p>For a "right now" query (e.g. initial load before the user moves the
 * slider), use {@code System.currentTimeMillis() + ONE_DAY_MS} so that entries
 * with effective times a short way in the future are not silently missed.
 * The {@code ONE_DAY_MS} constant is defined on this class for convenience.</p>
 *
 * @see SqlTemporalStoreResource#fetchAll(String) for an unconstrained "show all"
 *      query that does not require a {@code timeTo} value
 */
@JsonInclude(Include.NON_NULL)
public class FetchAtTimeRequest {

    /**
     * Name of the temporal-store map to query.
     * Must match the {@code map_name} column value used when the entries were
     * written; this is typically the name of the {@code SqlTemporalStoreDoc}.
     */
    @JsonProperty
    private final String mapName;

    /**
     * Upper bound (inclusive) on the effective-time axis, in milliseconds since
     * the Unix epoch.
     *
     * <p>For each key in the map the server selects the entry with the greatest
     * {@code effective_time} that is {@code ≤ timeTo}, giving a snapshot of the
     * store as it appeared at this instant.</p>
     *
     * <p><strong>Caution:</strong> do not pass {@link Long#MAX_VALUE}. It
     * exceeds JavaScript's safe integer range (2⁵³) and is silently corrupted
     * during JSON serialisation, arriving at the server as a value slightly
     * above {@code Long.MAX_VALUE} and causing a deserialisation error.</p>
     */
    @JsonProperty
    private final long timeTo;

    @JsonCreator
    public FetchAtTimeRequest(
            @JsonProperty("mapName") final String mapName,
            @JsonProperty("timeTo") final long timeTo) {
        this.mapName = mapName;
        this.timeTo = timeTo;
    }

    /**
     * Returns the name of the temporal-store map to query.
     *
     * @return the map name; never {@code null} (validated on construction)
     */
    public String getMapName() {
        return mapName;
    }

    /**
     * Returns the upper bound (inclusive) of the effective-time axis, in
     * milliseconds since the Unix epoch.
     *
     * @return the upper time bound; never {@code null} (primitive {@code long})
     */
    public long getTimeTo() {
        return timeTo;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FetchAtTimeRequest that = (FetchAtTimeRequest) o;
        return timeTo == that.timeTo
                && Objects.equals(mapName, that.mapName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mapName, timeTo);
    }

    @Override
    public String toString() {
        return "FetchAtTimeRequest{" +
                "mapName='" + mapName + '\'' +
                ", timeTo=" + timeTo +
                '}';
    }
}
