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

package stroom.pipeline.xslt;

import stroom.pipeline.shared.XsltReferenceCertainty;
import stroom.pipeline.shared.XsltReferenceReason;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an expression was determined to be worth.
 * <p>
 * Values and a reason are <b>not</b> mutually exclusive, and that is the point. A conditional whose
 * branches are {@code 'A'} and {@code @type} yields the value {@code A} <i>and</i> a
 * {@link XsltReferenceReason#DATA_DRIVEN} reason: one branch is known, the other is not, and reporting
 * only the known half would overstate what the parser found while reporting only the reason would
 * discard a real reference.
 *
 * @param values    The determined values, in a stable order. Never null, possibly empty. More than one
 *                  where an expression can yield several literals.
 * @param certainty Whether the values were written literally or folded. Never null.
 * @param reason    Why part or all of the expression could not be determined, or null if all of it was.
 */
record XsltValue(List<String> values, XsltReferenceCertainty certainty, @Nullable XsltReferenceReason reason) {

    XsltValue {
        Objects.requireNonNull(values, "Null values supplied");
        Objects.requireNonNull(certainty, "Null certainty supplied");
        values = List.copyOf(values);
    }

    static XsltValue resolved(final String value, final XsltReferenceCertainty certainty) {
        return new XsltValue(List.of(value), certainty, null);
    }

    static XsltValue resolved(final List<String> values, final XsltReferenceCertainty certainty) {
        return new XsltValue(values, certainty, null);
    }

    static XsltValue unresolved(final XsltReferenceReason reason) {
        return new XsltValue(
                List.of(),
                XsltReferenceCertainty.STATIC,
                Objects.requireNonNull(reason, "Null reason supplied"));
    }

    boolean hasValues() {
        return !values.isEmpty();
    }

    /**
     * @return this value, but folded through a variable or conditional, and so no longer written
     * literally at the point of use.
     */
    XsltValue asInferred() {
        return certainty == XsltReferenceCertainty.INFERRED
                ? this
                : new XsltValue(values, XsltReferenceCertainty.INFERRED, reason);
    }

    /**
     * Combine the outcomes of several branches, e.g. the arms of an {@code xsl:choose}.
     * <p>
     * The result is inferred whenever more than one branch contributed, since a value reached by
     * choosing a branch was not written at the point of use. Where branches disagree about why they
     * failed, the first reason wins - reasons are diagnostic, and one accurate reason is more use than a
     * list.
     */
    static XsltValue merge(final List<XsltValue> branches) {
        Objects.requireNonNull(branches, "Null branches supplied");
        if (branches.isEmpty()) {
            return unresolved(XsltReferenceReason.NON_LITERAL_BINDING);
        }
        if (branches.size() == 1) {
            return branches.getFirst();
        }

        final List<String> values = new ArrayList<>();
        XsltReferenceReason reason = null;
        for (final XsltValue branch : branches) {
            for (final String value : branch.values()) {
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
            if (reason == null) {
                reason = branch.reason();
            }
        }
        return new XsltValue(values, XsltReferenceCertainty.INFERRED, reason);
    }
}
