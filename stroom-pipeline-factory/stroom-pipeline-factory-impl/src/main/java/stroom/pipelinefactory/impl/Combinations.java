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

package stroom.pipelinefactory.impl;

import java.util.List;

/// What each Pipeline Factory has met and how far it has looked (design 06 §3.2 and §3.4). A row per
/// combination is kept whatever becomes of its pipeline: it is what stops a pipeline a person deletes
/// from being made again.
public interface Combinations {

    /// @param factoryUuid The factory.
    /// @return The highest stream id it has read; 0 if it has read none.
    long lastMetaId(String factoryUuid);

    /// @param factoryUuid The factory.
    /// @param lastMetaId  The highest stream id it has now read.
    void lastMetaId(String factoryUuid, long lastMetaId);

    /// Record that a factory has met a combination, unless it already has.
    ///
    /// @param factoryUuid The factory.
    /// @param combination What it met.
    /// @return Whether this is the first time, and so whether the factory is to make its pipeline.
    boolean met(String factoryUuid, Combination combination);

    /// @param factoryUuid The factory.
    /// @param combination What it made a pipeline for.
    /// @param made        What it made.
    void made(String factoryUuid, Combination combination, Made made);

    /// @param factoryUuid The factory.
    /// @param combination What it could not make a pipeline for.
    /// @param message     Why, in a person's words.
    void failed(String factoryUuid, Combination combination, String message);

    /// @param factoryUuid The factory.
    /// @param beforeMs    Only those last tried before this time.
    /// @return The combinations it met but has no pipeline for, failed or stopped part-way, to try again.
    List<Combination> unmade(String factoryUuid, long beforeMs);

    /// @param factoryUuid The factory.
    /// @return Every combination it has met, oldest first.
    List<MetCombination> list(String factoryUuid);

    /// Forget a combination, so that its next stream makes its pipeline afresh.
    ///
    /// @param factoryUuid The factory.
    /// @param combination What to forget.
    /// @return Whether it had been met.
    boolean forget(String factoryUuid, Combination combination);

    /// Forget everything about a factory: what it met and how far it looked. Its pipelines stay, since
    /// they are a person's once made.
    ///
    /// @param factoryUuid The factory, which has been deleted.
    void forgetFactory(String factoryUuid);
}
