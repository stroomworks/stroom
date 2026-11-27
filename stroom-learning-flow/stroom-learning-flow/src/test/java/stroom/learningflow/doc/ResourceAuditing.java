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

package stroom.learningflow.doc;

import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Checks that every REST method of a Smart Pipelines resource says what kind of audit event it is.
///
/// Without a method-level [AutoLogged], the auto-logger guesses the event type from the first word of the
/// method's name: `fetch` is a View and `find` a Search, but `approve`, `retract` or `giveUp` are recorded as
/// an "Uncategorised remote API call", which tells an auditor nothing about what was done.
final class ResourceAuditing {

    private static final Set<OperationType> GUESSED_OR_MISSING = Set.of(
            OperationType.ALLOCATE_AUTOMATICALLY,
            OperationType.UNKNOWN,
            OperationType.UNLOGGED);

    private ResourceAuditing() {
        // Static checks only
    }

    /// Asserts that each REST method the interface declares (every abstract method it declares itself)
    /// carries an [AutoLogged] on the implementation
    /// that names a real event type, and that each PROCESS event says what was done.
    ///
    /// @param resourceInterface The JAX-RS resource interface.
    /// @param implementation    Its implementation.
    static void assertEveryCallIsAudited(final Class<?> resourceInterface, final Class<?> implementation) {
        final List<Method> restMethods = Arrays.stream(resourceInterface.getDeclaredMethods())
                .filter(ResourceAuditing::isRestMethod)
                .toList();
        assertThat(restMethods)
                .describedAs("REST methods of %s", resourceInterface.getSimpleName())
                .isNotEmpty();

        for (final Method restMethod : restMethods) {
            final Method implemented = implementationOf(implementation, restMethod);
            final AutoLogged autoLogged = implemented.getAnnotation(AutoLogged.class);
            assertThat(autoLogged)
                    .describedAs("%s.%s has no @AutoLogged of its own, so its audit event type is guessed "
                                 + "from its name", implementation.getSimpleName(), restMethod.getName())
                    .isNotNull();
            assertThat(autoLogged.value())
                    .describedAs("%s.%s audit event type", implementation.getSimpleName(), restMethod.getName())
                    .isNotIn(GUESSED_OR_MISSING);
            if (autoLogged.value() == OperationType.PROCESS) {
                assertThat(autoLogged.verb())
                        .describedAs("%s.%s is a PROCESS event, so says what was done",
                                implementation.getSimpleName(), restMethod.getName())
                        .isNotBlank();
            }
        }
    }

    // Every abstract method a resource interface declares is one of its REST calls
    private static boolean isRestMethod(final Method method) {
        return Modifier.isAbstract(method.getModifiers()) && !method.isSynthetic();
    }

    private static Method implementationOf(final Class<?> implementation, final Method restMethod) {
        try {
            return implementation.getMethod(restMethod.getName(), restMethod.getParameterTypes());
        } catch (final NoSuchMethodException e) {
            throw new AssertionError(implementation.getSimpleName() + " does not implement "
                                     + restMethod.getName(), e);
        }
    }
}
