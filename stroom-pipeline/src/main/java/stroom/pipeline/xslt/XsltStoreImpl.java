/*
 * Copyright 2017 Crown Copyright
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

import stroom.docref.DocRef;
import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.DependencyRemapper;
import stroom.docstore.api.StoreFactory;
import stroom.pipeline.shared.XsltDoc;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Set;

@Singleton
class XsltStoreImpl
        extends AbstractDocumentStore<XsltDoc>
        implements XsltStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XsltStoreImpl.class);

    private final XsltReferenceParser referenceParser;

    @Inject
    XsltStoreImpl(final StoreFactory storeFactory,
                  final SecurityContext securityContext,
                  final XsltSerialiser serialiser,
                  final XsltReferenceParser referenceParser) {
        // The superclass passes this::getDependencyRemapFunction to the store as a supplier, not as a
        // value, so it is not called until a document is saved - by which time the field below is set.
        super(storeFactory,
                securityContext,
                serialiser,
                XsltDoc.TYPE,
                XsltDoc::builder,
                XsltDoc::copy);
        this.referenceParser = Objects.requireNonNull(referenceParser, "Null referenceParser supplied");
    }

    /**
     * Record what the XSLT refers to, so that imports and dictionary reads appear in
     * {@code doc_dependency} like every other document type's dependencies.
     * <p>
     * Uses {@link stroom.docstore.api.DependencyRemapper#record} rather than
     * {@code remap}. An XSLT holds its references as names inside its body rather than as
     * {@code DocRef} fields, so this function can report a dependency but cannot apply a substitution to
     * it - rewriting the body would mean editing the author's text, formatting and quoting included.
     * Calling {@code remap} would set the remapper's changed flag on the copy path, and the store would
     * then write back a document whose body still pointed at the original target: a silent failure to
     * remap, plus a pointless new version. Recording without claiming a change avoids both.
     * <p>
     * The behaviour is unchanged from before this existed - copying an XSLT does not repoint its
     * references, and they resolve by name wherever the copy lands - but it is no longer silent. Where a
     * substitution was called for and could not be applied, the function says so through
     * {@code DependencyRemapper.warn}, and the copy result carries it to the user. See
     * {@link #warnAboutUnremappedReferences}.
     * <p>
     * <b>Problems are recorded as well as dependencies</b>, so they surface where people already look
     * (the explorer tree, the Dependencies screen and the Structure view) rather than only in the XSLT's
     * own References tab:
     * <ul>
     *     <li>A name that matches several documents records every match as <b>ambiguous</b>
     *     ({@code DependencyRemapper.recordAmbiguous}). At runtime an ambiguous import fails the
     *     compile, and an ambiguous dictionary name uses the match with the lowest UUID.</li>
     *     <li>A name that matches nothing records a dependency on a document that does not exist
     *     ({@link XsltReferences#notFoundTargets()}), which is reported as missing.</li>
     * </ul>
     * <p>
     * <b>Whose view of the documents is recorded.</b> On save and on rebuild the store runs this function
     * as the processing user, so names resolve against every document. That is how the runtime resolves
     * an import: an XSLT is compiled as the processing user ({@code AbstractDocPool}), so an import the
     * processing user finds ambiguous fails to compile for everyone. A dictionary name is resolved later,
     * as the user the pipeline runs as (a processor filter's run-as user, or the user stepping), who may
     * see fewer dictionaries. The processing user's view is the widest of these, so a recorded ambiguity
     * is one that at least one way of running the XSLT can meet, and a name it finds unambiguous is
     * unambiguous for every user. Resolving with the editor's permissions instead would record an import
     * as resolved when the runtime finds it ambiguous, because of a same-named XSLT the editor cannot see.
     * <p>
     * The cost is that a recorded problem can tell a user one thing about documents they cannot view:
     * that an ambiguous name has a match they cannot see. Nothing says which documents, or how many: the
     * Dependencies screen shows a dependency only to someone who can view both ends of it, and the
     * explorer's message and the References tab give only the name the XSLT itself uses. The runtime
     * already says as much, and more: an ambiguous import fails with "Found 2 XSLTs named …" in the
     * processing and stepping errors of anyone who can run the pipeline. Telling the author of a broken
     * stylesheet that it is broken, and why, is worth that.
     * <p>
     * The copy path is different, and deliberately so: it runs this function with the caller's own
     * permissions, so the warnings it raises (see {@link #warnAboutUnremappedReferences}) name only
     * documents the caller may view. Its recorded dependencies are discarded, and the copy's own are
     * recorded when the copy is saved, as the processing user.
     *
     * @return a function that records dependencies, warns about the ones it could not repoint, and
     * returns the document untouched.
     */
    @Override
    protected DependencyRemapFunction<XsltDoc> getDependencyRemapFunction() {
        return (doc, dependencyRemapper) -> {
            // Never throws: a malformed or half-written stylesheet still saves, with whatever was
            // legible recorded. Dependency extraction is a rebuildable index, not part of the document.
            final XsltReferences references = referenceParser.parse(doc.getData());
            final Set<DocRef> ambiguous = references.ambiguousTargets();
            references.documentTargets().forEach(target -> {
                if (ambiguous.contains(target)) {
                    dependencyRemapper.recordAmbiguous(target);
                } else {
                    dependencyRemapper.record(target);
                }
            });

            warnAboutUnremappedReferences(doc, references, dependencyRemapper);

            LOGGER.debug(() -> logSummary(doc, references));

            // Body returned untouched - see the note above on why nothing is rewritten.
            return doc;
        };
    }

    /**
     * Tell the user which of this document's references a copy failed to repoint.
     * <p>
     * Two things can go wrong, and they are worth separating because their consequences differ.
     * <ul>
     *     <li><b>Still pointing at the original.</b> The name resolves to exactly one document, and that
     *     document was itself part of the copy. The copy therefore uses the original rather than its
     *     new sibling, so the two sets of stylesheets are not independent and editing one affects the
     *     other. This happens when the copy is renamed, i.e. into the same folder.</li>
     *     <li><b>Now ambiguous.</b> The copy preserved the name of something the original also names,
     *     so the name matches more than one document. This is the more damaging case: the runtime
     *     resolves an ambiguous name arbitrarily or not at all - {@code CustomURIResolver} throws for
     *     an import - and it breaks the <b>original</b> as much as the copy, because both bodies name
     *     the same thing. It happens when the copy goes to a different folder, where the name is kept.</li>
     * </ul>
     * <p>
     * Nothing here re-resolves anything: it reports on what the parse already found, and the parse ran
     * with the caller's permissions, so every document named in a warning is one the user may view. That
     * makes the report incomplete rather than disclosing - a collision with a document they cannot see
     * is not mentioned - and no wording here should suggest the check saw everything.
     * <p>
     * Deliberately quiet about the ordinary case. An XSLT referring to something outside the copy is not
     * a problem, and warning about it would train the user to dismiss the dialog.
     */
    private static void warnAboutUnremappedReferences(final XsltDoc doc,
                                                      final XsltReferences references,
                                                      final DependencyRemapper dependencyRemapper) {
        for (final XsltReference reference : references.references()) {
            if (reference.target() != null && dependencyRemapper.wouldRemap(reference.target())) {
                dependencyRemapper.warn(describe(doc, reference)
                                        + " still refers to '" + reference.target().getName()
                                        + "' in its original location, because the reference is a name in "
                                        + "the stylesheet body and cannot be repointed automatically. "
                                        + "Edit the copy if it should use the copied document instead.");

            } else if (reference.candidates().stream().anyMatch(dependencyRemapper::wouldRemap)) {
                dependencyRemapper.warn(describe(doc, reference)
                                        + " now matches " + reference.candidates().size()
                                        + " documents named '" + reference.rawValue()
                                        + "', because the copy kept that name. The original stylesheet is "
                                        + "affected in the same way, and an ambiguous name cannot be "
                                        + "resolved reliably at runtime. Rename one of them.");
            }
        }
    }

    private static String describe(final XsltDoc doc, final XsltReference reference) {
        return "XSLT '" + doc.getName() + "'"
               + (reference.lineNumber() > 0
                ? " line " + reference.lineNumber()
                : "");
    }

    private static String logSummary(final XsltDoc doc, final XsltReferences references) {
        return "Parsed XSLT " + doc.getName()
               + ": " + references.documentTargets().size() + " document dependencies, "
               + references.unresolved().size() + " unresolved"
               + (references.hasParseFailure()
                ? ", not fully parseable (" + references.parseFailure() + ")"
                : "");
    }
}
