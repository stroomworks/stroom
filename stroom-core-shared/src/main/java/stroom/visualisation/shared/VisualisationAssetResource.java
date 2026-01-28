package stroom.visualisation.shared;

import stroom.util.shared.RestResource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

@Tag(name = "VisualisationAssets")
@Path("/visualisationAssets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface VisualisationAssetResource extends RestResource, DirectRestService {

    /**
     * Fetches the assets associated with a visualisation doc.
     * @param ownerDocId The ID of the owning doc.
     * @return Assets associated with the doc.
     */
    @GET
    @Path("/fetchAssets/{ownerDocId}")
    @Operation(
            summary = "Fetch the assets belonging to a visualisation doc by the doc's UUID",
            operationId = "fetchVisualisationAssets")
    VisualisationAssets fetchDraftAssets(@PathParam("ownerDocId") String ownerDocId)
            throws RuntimeException;

    /**
     * Puts the assets into the database.
     * @param ownerDocId The ID of the document that owns the assets.
     * @param assets The assets to store in the database.
     * @return Whether it worked.
     */
    @PUT
    @Path("/updateAssets/{ownerId}")
    @Operation(
            summary = "Update all asset information and store uploaded files",
            operationId = "updateAssets")
    Boolean updateDraftAssets(@PathParam("ownerDocId") String ownerDocId,
                              @Parameter(description = "assets", required = true) VisualisationAssets assets)
            throws RuntimeException;

    @PUT
    @Path("/saveDraftToLive/{ownerDocId}")
    @Operation(
            summary = "Save draft assets to main store to make them live",
            operationId = "saveDraftToLive")
    Boolean saveDraftToLive(@PathParam("ownerDocId") String ownerDocId)
        throws RuntimeException;

    @PUT
    @Path("/revertDraftFromLive/{ownerDocId}")
    @Operation(
            summary = "Revert draft assets to get rid of any changes",
            operationId = "revertDraftFromLive")
    Boolean revertDraftFromLive(@PathParam("ownerDocId") String ownerDocId)
        throws RuntimeException;

}
