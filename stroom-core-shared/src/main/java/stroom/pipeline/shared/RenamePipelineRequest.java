package stroom.pipeline.shared;

import stroom.docref.DocRef;

public class RenamePipelineRequest {
    private DocRef pipeline;
    private String newName;

    public RenamePipelineRequest() {}

    public RenamePipelineRequest(DocRef pipeline, String newName) {
        this.pipeline = pipeline;
        this.newName = newName;
    }

    public DocRef getPipeline() {
        return pipeline;
    }

    public void setPipeline(DocRef pipeline) {
        this.pipeline = pipeline;
    }

    public String getNewName() {
        return newName;
    }

    public void setNewName(String newName) {
        this.newName = newName;
    }
}
