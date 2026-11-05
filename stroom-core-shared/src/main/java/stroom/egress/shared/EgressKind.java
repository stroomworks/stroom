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

package stroom.egress.shared;

import stroom.docref.HasDisplayValue;

/// The kinds of destination that data can be sent to, each governed by its own rules and mode.
public enum EgressKind implements HasDisplayValue {
    /// An HTTP or HTTPS endpoint, including the standard XSLT routes that fetch a URL.
    HTTP("HTTP", true),
    /// An email address.
    EMAIL("Email", false),
    /// The endpoint of an AI model.
    AI_MODEL("AI model", true),
    /// A file or directory on a file system.
    FILE("File", true),
    /// An object store such as S3.
    OBJECT_STORE("Object store", true),
    /// A message bus such as Kafka or SQS.
    MESSAGE_BUS("Message bus", true),
    /// A search backend such as Elasticsearch or Solr.
    SEARCH_BACKEND("Search backend", true);

    private final String displayValue;
    private final boolean prefixAllowed;

    EgressKind(final String displayValue, final boolean prefixAllowed) {
        this.displayValue = displayValue;
        this.prefixAllowed = prefixAllowed;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    /// Whether a rule of this kind may match by prefix. Email addresses may only match exactly.
    ///
    /// @return True if prefix matching is meaningful for the kind.
    public boolean isPrefixAllowed() {
        return prefixAllowed;
    }
}
