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

package stroom.config.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the back-compatibility of the asset config keys.
 *
 * <p>Generalising the visualisation-asset subsystem into {@code stroom.document.asset} renamed
 * {@code visualisationAsset}/{@code visualisationAssetDb} to {@code documentAsset}/
 * {@code documentAssetDb}. Stroom deliberately enables
 * {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} (see {@code App.initialize}), so
 * without an alias an existing {@code config.yml} carrying the old key would fail to start with
 * an "Unrecognized field" error naming a property the operator never chose to set.</p>
 *
 * <p>The {@code @JsonAlias} on the two constructor parameters is the only thing preventing that.
 * Deleting it - or renaming the parameters without carrying the alias across - reintroduces the
 * startup failure, which is why these tests assert the alias directly rather than going through
 * a config file.</p>
 *
 * <p>Note that the alias covers the YAML surface only. {@code ConfigMapper} derives property
 * paths from {@code @JsonProperty} alone and never consults the alias, so overrides held in the
 * {@code config} database table are re-pointed by the
 * {@code V07_13_00_001__document_asset_property_rename.sql} migration instead.</p>
 */
class TestAppConfigDeprecatedAssetKeys {

    /**
     * Mirrors the mapper Stroom actually parses config with - strict about unknown properties.
     * A lenient mapper would pass these tests whether or not the alias existed.
     */
    private static ObjectMapper strictMapper() {
        return new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    void testDeprecatedVisualisationAssetDbKeyStillBinds() throws Exception {
        final String json = """
                {"visualisationAssetDb":{"connection":{"jdbcDriverUrl":"jdbc:mysql://legacy:3306/stroom"}}}""";

        final AppConfig appConfig = strictMapper().readValue(json, AppConfig.class);

        assertThat(appConfig.getDocumentAssetDbConfig()).isNotNull();
        assertThat(appConfig.getDocumentAssetDbConfig().getConnectionConfig().getUrl())
                .isEqualTo("jdbc:mysql://legacy:3306/stroom");
    }

    @Test
    void testDeprecatedVisualisationAssetKeyStillBinds() throws Exception {
        final String json = """
                {"visualisationAsset":{"assetCacheDir":"my_asset_cache"}}""";

        final AppConfig appConfig = strictMapper().readValue(json, AppConfig.class);

        assertThat(appConfig.getDocumentAsset()).isNotNull();
        assertThat(appConfig.getDocumentAsset().getAssetCacheDir()).isEqualTo("my_asset_cache");
    }

    @Test
    void testCurrentKeysBind() throws Exception {
        final String json = """
                {"documentAsset":{"assetCacheDir":"my_asset_cache"},\
                "documentAssetDb":{"connection":{"jdbcDriverUrl":"jdbc:mysql://current:3306/stroom"}}}""";

        final AppConfig appConfig = strictMapper().readValue(json, AppConfig.class);

        assertThat(appConfig.getDocumentAsset().getAssetCacheDir()).isEqualTo("my_asset_cache");
        assertThat(appConfig.getDocumentAssetDbConfig().getConnectionConfig().getUrl())
                .isEqualTo("jdbc:mysql://current:3306/stroom");
    }

    /**
     * Supplying both names is <strong>not</strong> an error, and the winner is simply whichever
     * appears last in the file - here the deprecated one. Jackson treats an alias as another
     * spelling of the same property, so the second occurrence overwrites the first; there is no
     * ambiguity check to appeal to.
     *
     * <p>Pinned because it is the trap in this migration: an operator who adds the new key
     * without deleting the old one, and happens to add it above, keeps running on their old
     * value with nothing said. The upgrade note must therefore tell people to <em>rename</em>
     * the key, not to add the new one alongside.</p>
     */
    @Test
    void testSupplyingBothNamesIsLastOneWinsNotAnError() throws Exception {
        final String oldKeyLast = """
                {"documentAsset":{"assetCacheDir":"new"},"visualisationAsset":{"assetCacheDir":"old"}}""";
        final String newKeyLast = """
                {"visualisationAsset":{"assetCacheDir":"old"},"documentAsset":{"assetCacheDir":"new"}}""";

        assertThat(strictMapper().readValue(oldKeyLast, AppConfig.class)
                .getDocumentAsset().getAssetCacheDir())
                .isEqualTo("old");
        assertThat(strictMapper().readValue(newKeyLast, AppConfig.class)
                .getDocumentAsset().getAssetCacheDir())
                .isEqualTo("new");
    }

    /**
     * Serialisation must always emit the new name, so a config rewritten by stroom completes the
     * migration rather than perpetuating the old key.
     */
    @Test
    void testSerialisesUnderTheCurrentName() throws Exception {
        final String json = """
                {"visualisationAsset":{"assetCacheDir":"my_asset_cache"}}""";
        final ObjectMapper mapper = strictMapper();

        final AppConfig appConfig = mapper.readValue(json, AppConfig.class);
        final String written = mapper.writeValueAsString(appConfig);

        assertThat(written).contains("\"documentAsset\"");
        assertThat(written).doesNotContain("\"visualisationAsset\"");
    }
}
