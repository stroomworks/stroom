package stroom.security.client.presenter;

import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.explorer.shared.AdvancedDocumentFindRequest;
import stroom.explorer.shared.ExplorerResource;
import stroom.explorer.shared.FindResult;
import stroom.query.api.ExpressionOperator;
import stroom.security.shared.BulkDocumentPermissionChangeRequest;
import stroom.security.shared.DocumentPermission;
import stroom.task.client.TaskMonitorFactory;
import stroom.util.shared.PageRequest;
import stroom.util.shared.ResultPage;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Set;
import java.util.function.Consumer;

public class ExplorerClient extends AbstractRestClient {

    private static final ExplorerResource EXPLORER_RESOURCE = GWT.create(ExplorerResource.class);

    @Inject
    public ExplorerClient(final EventBus eventBus,
                          final RestFactory restFactory) {
        super(eventBus, restFactory);
    }

    public void changeDocumentPermissions(final BulkDocumentPermissionChangeRequest request,
                                         final Consumer<Boolean> consumer,
                                          final TaskMonitorFactory taskMonitorFactory) {
        restFactory
                .create(EXPLORER_RESOURCE)
                .method(res -> res.changeDocumentPermissions(request))
                .onSuccess(consumer)
                .onFailure(new DefaultErrorHandler(this, () -> consumer.accept(false)))
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }

    public void advancedFind(final ExpressionOperator expression,
                             final Consumer<ResultPage<FindResult>> consumer,
                             final TaskMonitorFactory taskMonitorFactory) {
        final AdvancedDocumentFindRequest advancedDocumentFindRequest = new AdvancedDocumentFindRequest.Builder()
                .requiredPermissions(Set.of(DocumentPermission.OWNER))
                .expression(expression)
                .pageRequest(PageRequest.unlimited())
                .build();
        restFactory
                .create(EXPLORER_RESOURCE)
                .method(res -> res.advancedFind(advancedDocumentFindRequest))
                .onSuccess(consumer)
                .taskMonitorFactory(taskMonitorFactory)
                .exec();
    }
}
