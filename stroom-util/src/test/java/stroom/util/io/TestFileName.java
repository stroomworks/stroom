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

package stroom.util.io;


import stroom.test.common.TestUtil;

import com.google.inject.TypeLiteral;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

class TestFileName {

    @TestFactory
    Stream<DynamicTest> test() {
        return TestUtil.buildDynamicTestStream()
                .withInputType(String.class)
                .withWrappedOutputType(new TypeLiteral<Tuple2<String, String>>() {
                })
                .withTestFunction(testCase -> {
                    final FileName fileName = FileName.parse(testCase.getInput());
                    return Tuple.of(fileName.getBaseName(), fileName.getExtension());
                })
                .withSimpleEqualityAssertion()
                .addCase("001.dat", Tuple.of("001", "dat"))
                .addCase("001.001.dat", Tuple.of("001.001", "dat"))
                .addCase("001", Tuple.of("001", ""))
                .addCase(".dat", Tuple.of("", "dat"))
                .build();
    }
}
