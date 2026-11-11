/*
 * Copyright 2018 Crown Copyright
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

import stroom.docstore.api.DocumentStoreBinder;
import stroom.pipeline.shared.XsltDoc;
import stroom.util.entityevent.EntityEvent;
import stroom.util.guice.GuiceUtil;
import stroom.util.guice.RestResourcesBinder;

import com.google.inject.AbstractModule;

import javax.xml.transform.URIResolver;

public class XsltModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(URIResolver.class).to(CustomURIResolver.class);

        // Injected rather than constructed, so it can be substituted in tests. The store uses it to
        // record what an XSLT refers to, and the References tab to report it.
        bind(XsltReferenceParser.class).to(XsltReferenceParserImpl.class);
        bind(XsltReferenceLookup.class).to(XsltReferenceLookupImpl.class);
        bind(XsltReferenceCheckService.class);

        DocumentStoreBinder.create(binder())
                .bind(XsltDoc.TYPE, XsltStore.class, XsltStoreImpl.class);

        RestResourcesBinder.create(binder())
                .bind(XsltResourceImpl.class);

        GuiceUtil.buildMultiBinder(binder(), EntityEvent.Handler.class)
                .addBinding(XsltDependencyRefresher.class);
    }
}
