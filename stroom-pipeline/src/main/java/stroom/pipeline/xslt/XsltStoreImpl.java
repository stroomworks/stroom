package stroom.pipeline.xslt;

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.DependencyRemapper;
import stroom.docstore.api.StoreFactory;
import stroom.pipeline.shared.XsltDoc;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Objects;

@Singleton
class XsltStoreImpl
        extends AbstractDocumentStore<XsltDoc>
        implements XsltStore {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(XsltStoreImpl.class);

    private final XsltReferenceParser referenceParser;

    @Inject
    XsltStoreImpl(final StoreFactory storeFactory,
                  final XsltSerialiser serialiser,
                  final XsltReferenceParser referenceParser) {
        // The superclass passes this::getDependencyRemapFunction to the store as a supplier, not as a
        // value, so it is not called until a document is saved - by which time the field below is set.
        super(storeFactory,
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
            references.documentTargets().forEach(dependencyRemapper::record);

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
