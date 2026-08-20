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

package stroom.pathways.shared.pathway;

/**
 * The state of a single pathway lock as set at one level (constraint, node, pathway or doc).
 * <p>
 * Locks resolve most-specific-first: the effective state for a property is the nearest
 * explicit ({@link #LOCKED}/{@link #UNLOCKED}) setting walking up
 * constraint &rarr; node &rarr; pathway &rarr; doc. When every level is {@link #INHERIT} the
 * effective state falls back to {@link #UNLOCKED}.
 */
public enum LockState {

    /**
     * No explicit setting at this level; defer to a less-specific level (and ultimately to
     * {@link #UNLOCKED} if nothing is set anywhere).
     */
    INHERIT,

    /**
     * The property is locked at this level; the corresponding mutation/discovery is disallowed
     * and produces a violation.
     */
    LOCKED,

    /**
     * The property is explicitly unlocked at this level; the corresponding mutation/discovery is
     * allowed.
     */
    UNLOCKED
}
