/*
 * Copyright 2016 Crown Copyright
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
import stroom.docstore.api.DocDependencies;
import stroom.docstore.api.DocumentStore;
import stroom.pipeline.shared.XsltDoc;

import java.util.List;

public interface XsltStore extends DocumentStore<XsltDoc> {
    List<DocRef> list();

    /// Read an XSLT and extract what it refers to, as it is recorded when the XSLT is saved: resolved as
    /// the processing user, with ambiguous names marked. No document permission check is applied.
    ///
    /// @param docRef The XSLT to read.
    /// @return Its dependencies.
    DocDependencies getDocDependencies(DocRef docRef);
}
