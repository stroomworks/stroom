/*
 * Copyright 2024 Crown Copyright
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

package stroom.util.shared.string;

import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static stroom.util.shared.string.CIKeys.getCommonKey;

class TestCIKeys {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestCIKeys.class);

    @Test
    void test() {
        // ACCEPT is a common key, so any way we get/create it should give us the same string instances.
        final CIKey key1 = CIKeys.ACCEPT;
        final CIKey key2 = CIKey.of(key1.get());
        final CIKey key3 = CIKey.ofLowerCase(key1.getAsLowerCase());
        final CIKey key4 = CIKey.of("accept");
        // Explicitly don't try to get a common key
        final CIKey dynamicKey1 = CIKey.ofDynamicKey("accept");
        final CIKey dynamicKey2 = CIKey.ofDynamicKey("accept");

        assertThat(key1)
                .isSameAs(key2);
        assertThat(key1)
                .isSameAs(key3);
        assertThat(key1)
                .isSameAs(key4);

        // Different instances
        assertThat(key1)
                .isNotSameAs(dynamicKey1);
        assertThat(key1)
                .isNotSameAs(dynamicKey2);
        assertThat(dynamicKey1)
                .isNotSameAs(dynamicKey2);

        assertThat(key1.get())
                .isSameAs(key2.get());
        assertThat(key1.get())
                .isSameAs(key3.get());
        assertThat(key1.get())
                .isSameAs(key4.get());

        assertThat(key1.getAsLowerCase())
                .isSameAs(key2.getAsLowerCase());
        assertThat(key1.getAsLowerCase())
                .isSameAs(key3.getAsLowerCase());
        assertThat(key1.getAsLowerCase())
                .isSameAs(key4.getAsLowerCase());
    }

    @Test
    void testIntern() {
        // Use a uuid so we know it won't be in the static map
        final String key = UUID.randomUUID().toString().toUpperCase();

        assertThat(getCommonKey(key))
                .isNull();

        final CIKey ciKey1 = CIKeys.internCommonKey(key);

        // Intern again
        final CIKey ciKey2 = CIKeys.internCommonKey(key);

        assertThat(ciKey2)
                .isEqualTo(ciKey1);
        assertThat(ciKey2)
                .isSameAs(ciKey1);

        final CIKey ciKey3 = getCommonKey(key);

        assertThat(ciKey3)
                .isEqualTo(ciKey1);
        assertThat(ciKey3)
                .isSameAs(ciKey1);

        final CIKey ciKey4 = CIKeys.getCommonKeyByLowerCase(key.toLowerCase());

        assertThat(ciKey4)
                .isEqualTo(ciKey1);
        assertThat(ciKey4)
                .isSameAs(ciKey1);
    }
}
