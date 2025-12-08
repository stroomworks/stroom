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
     * @param ownerId The ref of the owning doc.
     * @return Assets associated with the doc.
     */
    @GET
    @Path("/fetchAssets/{ownerId}")
    @Operation(
            summary = "Fetch the assets belonging to a visualisation doc by the doc's UUID",
            operationId = "fetchVisualisationAssets")
    VisualisationAssets fetchAssets(@PathParam("ownerId") String ownerId)
            throws RuntimeException;

    /**
     * Puts the assets into the database.
     * @param assets The assets to store in the database.
     * @return Whether it worked.
     */
    @PUT
    @Path("/updateAssets/{ownerId}")
    @Operation(
            summary = "Update all asset information and store uploaded files",
            operationId = "updateAssets")
    Boolean updateAssets(@PathParam("ownerId") String ownerId,
                         @Parameter(description = "assets", required = true) VisualisationAssets assets)
            throws RuntimeException;

}
