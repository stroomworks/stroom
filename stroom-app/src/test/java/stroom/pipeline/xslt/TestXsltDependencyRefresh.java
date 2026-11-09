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

package stroom.pipeline.xslt;

import stroom.dictionary.api.DictionaryStore;
import stroom.docref.DocRef;
import stroom.docstore.impl.dao.DocDependencyDao;
import stroom.importexport.shared.Dependency;
import stroom.importexport.shared.DependencyCriteria;
import stroom.test.AbstractCoreIntegrationTest;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// An XSLT's recorded dependencies follow changes to the documents its names refer to, with the real
/// stores, entity events and database: [XsltStoreImpl] records them on save and [XsltDependencyRefresher]
/// records them again when another document takes or gives up one of those names.
///
/// This is where the order of entity event handlers is exercised for real: the refresher must not read a
/// name from the document name cache before the change is reflected in it.
class TestXsltDependencyRefresh extends AbstractCoreIntegrationTest {

    @Inject
    private XsltStore xsltStore;
    @Inject
    private DictionaryStore dictionaryStore;
    @Inject
    private DocDependencyDao docDependencyDao;

    @Test
    void secondDictionaryWithTheNameMakesTheReadAmbiguous() {
        final String name = uniqueName("Words");
        final DocRef words1 = dictionaryStore.createDocument(name);
        final DocRef xslt = createXslt(dictionaryReader(name));

        assertThat(dependenciesOf(xslt))
                .extracting(dep -> dep.getTo().getUuid(), Dependency::isOk, Dependency::isAmbiguous)
                .containsExactly(tuple(words1.getUuid(), true, false));

        // Nothing about the XSLT changes, but its reference now matches two dictionaries
        final DocRef words2 = dictionaryStore.createDocument(name);

        assertThat(dependenciesOf(xslt))
                .extracting(dep -> dep.getTo().getUuid(), Dependency::isAmbiguous)
                .containsExactlyInAnyOrder(
                        tuple(words1.getUuid(), true),
                        tuple(words2.getUuid(), true));
        assertThat(docDependencyDao.getAmbiguousDependencies()).containsKey(xslt);

        // Renaming one away resolves it again
        dictionaryStore.renameDocument(words2, uniqueName("Other words"));

        assertThat(dependenciesOf(xslt))
                .extracting(dep -> dep.getTo().getUuid(), Dependency::isAmbiguous)
                .containsExactly(tuple(words1.getUuid(), false));
        assertThat(docDependencyDao.getAmbiguousDependencies()).doesNotContainKey(xslt);
    }

    @Test
    void deletingOneOfTwoDictionariesResolvesTheRead() {
        final String name = uniqueName("Words");
        final DocRef words1 = dictionaryStore.createDocument(name);
        final DocRef words2 = dictionaryStore.createDocument(name);
        final DocRef xslt = createXslt(dictionaryReader(name));
        assertThat(dependenciesOf(xslt)).allMatch(Dependency::isAmbiguous);

        dictionaryStore.deleteDocument(words2);

        assertThat(dependenciesOf(xslt))
                .extracting(dep -> dep.getTo().getUuid(), Dependency::isOk, Dependency::isAmbiguous)
                .containsExactly(tuple(words1.getUuid(), true, false));
    }

    @Test
    void importOfAMissingXsltIsSatisfiedWhenItIsCreated() {
        final String name = uniqueName("Common");
        final DocRef importer = createXslt("""
                <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                  <xsl:import href="%s"/>
                </xsl:stylesheet>""".formatted(name));

        // Recorded as a dependency on a document that does not exist, so it is reported as missing
        assertThat(dependenciesOf(importer))
                .extracting(dep -> dep.getTo().getName(), Dependency::isOk)
                .containsExactly(tuple(name, false));
        assertThat(docDependencyDao.getBrokenDependencies(Set.of())).containsKey(importer);

        final DocRef common = xsltStore.createDocument(name);

        assertThat(dependenciesOf(importer))
                .extracting(dep -> dep.getTo().getUuid(), Dependency::isOk)
                .containsExactly(tuple(common.getUuid(), true));
        assertThat(docDependencyDao.getBrokenDependencies(Set.of())).doesNotContainKey(importer);
    }

    private DocRef createXslt(final String data) {
        final DocRef docRef = xsltStore.createDocument(uniqueName("XSLT"));
        xsltStore.writeDocument(xsltStore.readDocument(docRef).copy().data(data).build());
        return docRef;
    }

    private List<Dependency> dependenciesOf(final DocRef from) {
        final DependencyCriteria criteria = new DependencyCriteria();
        criteria.setQuickFilter("fromuuid:" + from.getUuid());
        return docDependencyDao.fetchDependencies(criteria, Set.of(), dep -> true).getValues();
    }

    private static String dictionaryReader(final String name) {
        return """
                <xsl:stylesheet version="2.0"
                                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                xmlns:stroom="stroom">
                  <xsl:template match="/">
                    <xsl:value-of select="stroom:dictionary('%s')"/>
                  </xsl:template>
                </xsl:stylesheet>""".formatted(name);
    }

    // Names are unique per test, so documents left by another test in the same database cannot match
    private static String uniqueName(final String prefix) {
        return prefix + " " + UUID.randomUUID();
    }
}
