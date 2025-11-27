/*
 * Copyright 2017 Crown Copyright
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

package stroom.visualisation.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.Doc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Description(
        "Defines a data visualisation that can be used in a [Dashboard]({{< relref \"#dashboard\" >}}) " +
        "Document.\n" +
        "The Visualisation defines the settings that will be available to the user when it is embedded in a " +
        "Dashboard.\n" +
        "A Visualisation is dependent on a [Script]({{< relref \"#script\" >}}) Document for the Javascript " +
        "code to make it work.")
@JsonPropertyOrder({
        "type",
        "uuid",
        "name",
        "version",
        "createTimeMs",
        "updateTimeMs",
        "createUser",
        "updateUser",
        "description",
        "functionName",
        "scriptRef",
        "settings",
        "assets"})
@JsonInclude(Include.NON_NULL)
public class VisualisationDoc extends Doc {

    public static final String TYPE = "Visualisation";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.VISUALISATION_DOCUMENT_TYPE;

    @JsonProperty
    private String description;
    @JsonProperty
    private String functionName;
    @JsonProperty
    private DocRef scriptRef;
    @JsonProperty
    private String settings;
    @JsonProperty
    private List<VisualisationAsset> assets;

    public VisualisationDoc() {
    }

    @JsonCreator
    public VisualisationDoc(@JsonProperty("type") final String type,
                            @JsonProperty("uuid") final String uuid,
                            @JsonProperty("name") final String name,
                            @JsonProperty("version") final String version,
                            @JsonProperty("createTimeMs") final Long createTimeMs,
                            @JsonProperty("updateTimeMs") final Long updateTimeMs,
                            @JsonProperty("createUser") final String createUser,
                            @JsonProperty("updateUser") final String updateUser,
                            @JsonProperty("description") final String description,
                            @JsonProperty("functionName") final String functionName,
                            @JsonProperty("scriptRef") final DocRef scriptRef,
                            @JsonProperty("settings") final String settings,
                            @JsonProperty("assets") final List<VisualisationAsset> assets) {
        super(type, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.functionName = functionName;
        this.scriptRef = scriptRef;
        this.settings = settings;
        this.assets = assets;
    }

    /**
     * @return A new {@link DocRef} for this document's type with the supplied uuid.
     */
    public static DocRef getDocRef(final String uuid) {
        return DocRef.builder(TYPE)
                .uuid(uuid)
                .build();
    }

    /**
     * @return A new builder for creating a {@link DocRef} for this document's type.
     */
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(final String functionName) {
        this.functionName = functionName;
    }

    public DocRef getScriptRef() {
        return scriptRef;
    }

    public void setScriptRef(final DocRef scriptRef) {
        this.scriptRef = scriptRef;
    }

    public String getSettings() {
        return settings;
    }

    public void setSettings(final String settings) {
        this.settings = settings;
    }

    public List<VisualisationAsset> getAssets() {
        return assets == null ? null : Collections.unmodifiableList(assets);
    }

    public void setAssets(final List<VisualisationAsset> assets) {
        if (this.assets == null) {
            this.assets = new ArrayList<>(assets.size());
        } else {
            this.assets.clear();
        }
        this.assets.addAll(assets);
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final VisualisationDoc that = (VisualisationDoc) o;
        return Objects.equals(description, that.description) && Objects.equals(functionName,
                that.functionName) && Objects.equals(scriptRef, that.scriptRef) && Objects.equals(
                settings,
                that.settings) && Objects.equals(assets, that.assets);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), description, functionName, scriptRef, settings, assets);
    }
}
