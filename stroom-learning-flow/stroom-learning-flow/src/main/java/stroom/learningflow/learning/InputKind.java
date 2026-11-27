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

package stroom.learningflow.learning;

/// What kind of input a learning session is working on. It decides how the split question is put, and which
/// steps of the flow run, since a step can be limited to one kind of input. It is settled by the chain's first
/// element:
///
/// - `TEXT`: raw text, which a parser with a configuration (the Data Splitter) cuts into records.
/// - `XML`: XML that already contains the records.
/// - `JSON`: JSON, which a parser with no configuration turns into XML whose records are an array's items.
public enum InputKind {
    TEXT,
    XML,
    JSON
}
