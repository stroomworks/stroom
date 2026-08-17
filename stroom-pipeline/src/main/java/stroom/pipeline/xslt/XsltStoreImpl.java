package stroom.pipeline.xslt;

import stroom.docstore.api.AbstractDocumentStore;
import stroom.docstore.api.DependencyRemapFunction;
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
     * The consequence to be aware of is unchanged from today's behaviour: copying an XSLT does not
     * repoint its references, and they resolve by name in their new location. Making that visible to the
     * user is separate work - it needs a way to ask the remapper whether a substitution would have
     * applied, which it does not currently offer.
     *
     * @return a function that records dependencies and returns the document untouched.
     */
    @Override
    protected DependencyRemapFunction<XsltDoc> getDependencyRemapFunction() {
        return (doc, dependencyRemapper) -> {
            // Never throws: a malformed or half-written stylesheet still saves, with whatever was
            // legible recorded. Dependency extraction is a rebuildable index, not part of the document.
            final XsltReferences references = referenceParser.parse(doc.getData());
            references.documentTargets().forEach(dependencyRemapper::record);

            LOGGER.debug(() -> logSummary(doc, references));

            // Body returned untouched - see the note above on why nothing is rewritten.
            return doc;
        };
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
