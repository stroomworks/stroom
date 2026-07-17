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

package stroom.query.language.functions;

import java.util.stream.Stream;

class TestJq extends AbstractFunctionTest<Jq> {

    @Override
    Class<Jq> getFunctionType() {
        return Jq.class;
    }

    @Override
    Stream<TestCase> getTestCases() {
        final String json = "{\"foo\":\"bar\", \"arr\":[1,2,3], \"nested\":{\"a\":1}}";
        return Stream.of(
                TestCase.of(
                        "Simple field",
                        ValString.create("bar"),
                        ValString.create(json),
                        ValString.create(".foo")),
                TestCase.of(
                        "Nested field",
                        ValString.create("1"),
                        ValString.create(json),
                        ValString.create(".nested.a")),
                TestCase.of(
                        "Array extraction",
                        ValString.create("[1,2,3]"),
                        ValString.create(json),
                        ValString.create(".arr")),
                TestCase.of(
                        "Array element",
                        ValString.create("2"),
                        ValString.create(json),
                        ValString.create(".arr[1]")),
                TestCase.of(
                        // Field access on a key needing quoting (hyphens) uses
                        // ."key" — the form FloorMapQueryBuilder generates for
                        // e.g. tm-world-to-map. A bare "key" without the dot
                        // would be a jq string literal, not a lookup.
                        "Quoted hyphenated field",
                        ValString.create("[1,0,0,1,50,60]"),
                        ValString.create("{\"tm-world-to-map\":[1,0,0,1,50,60]}"),
                        ValString.create(".\"tm-world-to-map\"")),
                TestCase.of(
                        "Filter",
                        // Multiple nodes are returned in an array string with standard spacing
                        ValString.create("[2, 3]"),
                        ValString.create(json),
                        ValString.create(".arr[] | select(. > 1)")),
                TestCase.of(
                        "No match",
                        ValNull.INSTANCE,
                        ValString.create(json),
                        ValString.create(".missing")),
                TestCase.of(
                        "Invalid JSON",
                        // Error message from Jackson
                        ValString.create("was expecting double-quote to start field name"),
                        ValString.create("{unclosed"),
                        ValString.create(".")),
                TestCase.of(
                        "Invalid JQ",
                        ValString.create(
                                "An empty JQ expression has been defined for second argument of 'Jq' function"),
                        ValString.create(json),
                        ValString.create(""))
        );
    }
}
