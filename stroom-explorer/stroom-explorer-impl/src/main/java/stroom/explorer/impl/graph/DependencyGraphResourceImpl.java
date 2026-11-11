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

package stroom.explorer.impl.graph;

import stroom.dependency.shared.DependencyGraph;
import stroom.dependency.shared.DependencyGraphRequest;
import stroom.dependency.shared.DependencyGraphResource;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// Serves a folder's dependency graph to the Structure view.
@AutoLogged(OperationType.VIEW)
public class DependencyGraphResourceImpl implements DependencyGraphResource {

    private final Provider<DependencyGraphService> dependencyGraphServiceProvider;

    @Inject
    DependencyGraphResourceImpl(final Provider<DependencyGraphService> dependencyGraphServiceProvider) {
        this.dependencyGraphServiceProvider = dependencyGraphServiceProvider;
    }

    @Override
    public DependencyGraph fetchGraph(final DependencyGraphRequest request) {
        return dependencyGraphServiceProvider.get().fetchGraph(request);
    }
}
