package stroom.contentstore.shared;

import stroom.util.shared.PageRequest;
import stroom.util.shared.RestResource;
import stroom.util.shared.ResultPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.fusesource.restygwt.client.DirectRestService;

/**
 * Interface for the REST API for the App Store functionality.
 */
@Tag(name = "ContentStoreContentPacks")
@Path("/contentstore")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface ContentStoreResource extends RestResource, DirectRestService {

    /**
     * Method returns a paged list of Content Packs available from the
     * App Store.
     * @return a list of available content packs.
     */
    @POST
    @Path("/list")
    @Operation(
            summary = "Lists Content Store Content Packs",
            operationId = "listContentStoreContentPacks")
    ResultPage<ContentStoreContentPack> list(PageRequest pageRequest);

    /**
     * Checks to see if the content pack has already resulted in the
     * creation of a GitRepoDoc object. Note that this does not do a
     * full match; it looks at the GitRepo URL, branch and path.
     * Importing a Content Pack more than once will result in
     * confusion within Stroom as the UUIDs can only exist in one place.
     * @param contentPack The content pack to check.
     * @return true if the content pack already exists, false otherwise.
     */
    @POST
    @Path("/exists")
    @Operation(
            summary = "Checks to see if a GitRepoDoc exists for the Content Pack",
            operationId = "GitRepoDocExistsForContentPack")
    boolean exists(ContentStoreContentPack contentPack);

    /**
     * Creates a GitRepoDoc from a Content Pack.
     * @param createGitRepoRequest The request
     * @return Generic response.
     */
    @POST
    @Path("/create")
    @Operation(
            summary = "Creates a GitRepoDoc from a Content Pack",
            operationId = "createGitRepoFromContentPack")
    ContentStoreResponse create(ContentStoreCreateGitRepoRequest createGitRepoRequest);

    /**
     * Checks to see if upgraded content is available for this
     * content pack. Content packs that are not of installation status
     * INSTALLED will be ignored and False returned.
     * @param contentPack The content pack to check.
     * @return retval.getValue() == true if upgraded content is available,
     * false otherwise.
     */
    @POST
    @Path("checkContentUpgradeAvailable")
    @Operation(
            summary = "Checks if updated content is available",
            operationId = "checkContentUpgradeAvailable")
    ContentStoreValueResponse<Boolean> checkContentUpgradeAvailable(ContentStoreContentPack contentPack);

}
