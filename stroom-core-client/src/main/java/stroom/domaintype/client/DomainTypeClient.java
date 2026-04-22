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

package stroom.domaintype.client;

import stroom.dispatch.client.RestFactory;
import stroom.domaintype.shared.DomainTypeResource;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;

import java.util.List;
import java.util.function.Consumer;

public class DomainTypeClient {

    private static final DomainTypeResource DOMAIN_TYPE_RESOURCE = GWT.create(DomainTypeResource.class);

    private final RestFactory restFactory;

    @Inject
    public DomainTypeClient(final RestFactory restFactory) {
        this.restFactory = restFactory;
    }

    public void fetchClassParts(final Consumer<List<String>> consumer) {
        restFactory
                .create(DOMAIN_TYPE_RESOURCE)
                .method(DomainTypeResource::fetchClassParts)
                .onSuccess(consumer)
                .exec();
    }

    public void fetchAttributeParts(final String classPart, final Consumer<List<String>> consumer) {
        restFactory
                .create(DOMAIN_TYPE_RESOURCE)
                .method(res -> res.fetchAttributeParts(classPart))
                .onSuccess(consumer)
                .exec();
    }
}
