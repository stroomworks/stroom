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

package stroom.query.language;

import stroom.query.api.datasource.QueryField;
import stroom.query.planner.port.FieldInfoSource;

import java.util.List;
import java.util.Optional;

/**
 * A {@link FieldInfoSource} that never resolves anything - for tests of {@link OptimisingQueryCompiler#create}
 * (Task 1.4's byte-parity path, which never reads this dependency) that don't care about {@code explain()}
 * (Task 4.1). Not lambda-compatible like the single-method cost ports since it has two methods.
 */
final class EmptyFieldInfoSource implements FieldInfoSource {

    static final EmptyFieldInfoSource INSTANCE = new EmptyFieldInfoSource();

    @Override
    public List<QueryField> getFields(final String dataSourceName) {
        return List.of();
    }

    @Override
    public Optional<QueryField> getTimeField(final String dataSourceName) {
        return Optional.empty();
    }
}
