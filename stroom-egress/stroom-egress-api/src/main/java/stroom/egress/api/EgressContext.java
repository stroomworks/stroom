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

package stroom.egress.api;

import java.util.Optional;

/// Says which pipeline and feed a request is being made for, so that blocked attempts can be traced to them.
///
/// The Saxon hooks are installed when a stylesheet is compiled, which is before any pipeline or feed is known, so
/// the context is carried per call in a thread-local rather than being captured by the hook.
///
/// @param pipeline The pipeline name, or null.
/// @param feed     The feed name, or null.
public record EgressContext(String pipeline, String feed) {

    private static final ThreadLocal<EgressContext> CURRENT = new ThreadLocal<>();

    /// Sets the context for the current thread. It must be cleared with [#clear()] in a finally block.
    ///
    /// @param context The context.
    public static void set(final EgressContext context) {
        CURRENT.set(context);
    }

    /// Clears the context for the current thread.
    public static void clear() {
        CURRENT.remove();
    }

    /// The context for the current thread.
    ///
    /// @return The context, if one has been set.
    public static Optional<EgressContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
