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

package stroom.docstore.api;

import stroom.docref.DocRef;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects, and optionally substitutes, the {@link DocRef}s held inside a document.
 * <p>
 * A document type contributes a {@link DependencyRemapFunction} that visits its own DocRef-bearing
 * fields and passes each one through this class. That single visitor serves two callers, which use it
 * in quite different ways:
 * <ul>
 *     <li><b>Recording</b> — on every save. Constructed with no remappings, so {@link #remap(DocRef)}
 *     is the identity function; the caller keeps {@link #getDependencies()} and discards the returned
 *     document. This is what populates {@code doc_dependency}.</li>
 *     <li><b>Remapping</b> — on copy. Constructed with a real old-to-new map, so {@code remap} returns
 *     replacements; the caller keeps the returned document and writes it back if
 *     {@link #isChanged()}.</li>
 * </ul>
 */
public class DependencyRemapper {

    private final Map<DocRef, DocRef> remappings;
    private final Set<DocRef> dependencies;
    private final AtomicBoolean changed;

    public DependencyRemapper(final Map<DocRef, DocRef> remappings) {
        this.remappings = remappings;
        this.dependencies = new HashSet<>();
        this.changed = new AtomicBoolean();
    }

    public DependencyRemapper() {
        this.remappings = Collections.emptyMap();
        this.dependencies = new HashSet<>();
        this.changed = new AtomicBoolean();
    }

    public DocRef remap(final DocRef docRef) {
        final DocRef remap = remappings.getOrDefault(docRef, docRef);
        changed.compareAndSet(false, !Objects.equals(remap, docRef));
        if (remap != null) {
            dependencies.add(remap);
        }
        return remap;
    }

    /**
     * Record a dependency on {@code docRef} <b>without</b> reporting that the document changed, i.e.
     * without affecting {@link #isChanged()}.
     * <p>
     * <b>Why this exists.</b> {@link #remap(DocRef)} deliberately couples two facts — <i>"this
     * document depends on that one"</i> and <i>"I have substituted it"</i> — because for every
     * document type that holds its dependencies as {@link DocRef} fields those are the same event: the
     * visitor rebuilds the field from the returned value, so reporting and substituting always happen
     * together. {@link #isChanged()} can therefore be set inside {@code remap} and trusted by the copy
     * path to decide whether a write is needed.
     * <p>
     * That coupling breaks for a document whose references are <b>not</b> addressable fields. An XSLT
     * refers to other documents by name, in strings inside its body ({@code stroom:dictionary('Foo')},
     * {@code xsl:import/@href}), so its visitor can report {@code A → B} but cannot apply the
     * substitution without rewriting the author's text — formatting, comments and quoting included.
     * Were it to call {@code remap} to record the dependency, a matching remapping would set the
     * changed flag, and the copy path would write back a document whose body still points at the
     * original target: a silent failure to remap, plus a redundant version and audit entry.
     * <p>
     * Use this method when the caller <b>cannot</b> apply a substitution, and {@link #remap(DocRef)}
     * when it can. A caller using this method is responsible for making the un-remapped reference
     * visible to the user by its own means, since nothing here will report it.
     *
     * @param docRef The dependency to record. Must not be null — unlike {@code remap}, which tolerates
     *               a null field value, this method is called with a reference the caller has already
     *               resolved.
     * @throws NullPointerException if {@code docRef} is null.
     */
    public void record(@NonNull final DocRef docRef) {
        Objects.requireNonNull(docRef, "Null docRef supplied");
        dependencies.add(docRef);
    }

    public ExpressionOperator remapExpression(final ExpressionOperator expressionOperator) {
        final ExpressionOperator.Builder builder = ExpressionOperator
                .builder()
                .enabled(expressionOperator.getEnabled())
                .op(expressionOperator.getOp());
        if (expressionOperator.getChildren() != null) {
            final List<ExpressionItem> children = new ArrayList<>();
            expressionOperator.getChildren().forEach(expressionItem -> {
                switch (expressionItem) {
                    case final ExpressionOperator operator -> children.add(remapExpression(operator));
                    case final ExpressionTerm expressionTerm -> {
                        final ExpressionTerm termCopy = expressionTerm.copy()
                                .docRef(remap(expressionTerm.getDocRef()))
                                .build();
                        children.add(termCopy);
                    }
                }
            });
            builder.children(children);
        }
        return builder.build();
    }

    public Set<DocRef> getDependencies() {
        return dependencies;
    }

    public boolean isChanged() {
        return changed.get();
    }
}
