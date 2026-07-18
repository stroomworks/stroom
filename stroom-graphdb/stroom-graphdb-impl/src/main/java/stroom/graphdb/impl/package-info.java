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

/**
 * The internal stores a {@link stroom.graphdb.shared.GraphDbDoc} owns and encapsulates (see
 * {@code docs/temporal-cypher-graph-implementation-plan.md}, Task PoC.0), plus the docstore/cache wiring for the
 * document itself. Nothing in this package is user-addressable except through the owning document.
 */
@NullMarked
package stroom.graphdb.impl;

import org.jspecify.annotations.NullMarked;
