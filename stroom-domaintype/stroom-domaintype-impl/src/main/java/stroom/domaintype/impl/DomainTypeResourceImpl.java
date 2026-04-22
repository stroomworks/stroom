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

package stroom.domaintype.impl;

import stroom.docref.DocRef;
import stroom.docstore.api.DocumentResourceHelper;
import stroom.domaintype.shared.DomainType;
import stroom.domaintype.shared.DomainTypeDoc;
import stroom.domaintype.shared.DomainTypeResource;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@AutoLogged(OperationType.UNLOGGED)
public class DomainTypeResourceImpl implements DomainTypeResource {

    private final Provider<DomainTypeStore> domainTypeStore;
    private final Provider<DocumentResourceHelper> documentResourceHelper;

    @Inject
    public DomainTypeResourceImpl(final Provider<DomainTypeStore> domainTypeStore,
                                  final Provider<DocumentResourceHelper> documentResourceHelper) {
        this.domainTypeStore = domainTypeStore;
        this.documentResourceHelper = documentResourceHelper;
    }

    @Override
    public DomainTypeDoc fetch(final String uuid) {
        return documentResourceHelper.get().read(domainTypeStore.get(), getDocRef(uuid));
    }

    @Override
    public DomainTypeDoc update(final String uuid, final DomainTypeDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new IllegalArgumentException("Unexpected UUID");
        }
        return documentResourceHelper.get().update(domainTypeStore.get(), doc);
    }

    @Override
    public List<String> fetchClassParts() {

        return domainTypeStore.get()
                .list()
                .stream()
                .map(docRef -> domainTypeStore.get().readDocument(docRef))
                .filter(Objects::nonNull)
                .map(DomainTypeDoc::getDomainTypes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(DomainType::getClassPart)
                .filter(classPart -> classPart != null && !classPart.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public List<String> fetchAttributeParts(final String classPart) {
        if (classPart == null || classPart.isBlank()) {
            return List.of();
        }

        final boolean isWildcard = "*".equals(classPart);
        return domainTypeStore.get()
                .list()
                .stream()
                .map(docRef -> domainTypeStore.get().readDocument(docRef))
                .filter(Objects::nonNull)
                .map(DomainTypeDoc::getDomainTypes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(dt -> isWildcard || classPart.equals(dt.getClassPart()))
                .map(DomainType::getAttributePart)
                .filter(attrPart -> attrPart != null && !attrPart.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder(DomainTypeDoc.TYPE)
                .uuid(uuid)
                .build();
    }
}
