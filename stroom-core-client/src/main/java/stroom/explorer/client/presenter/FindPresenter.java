package stroom.explorer.client.presenter;

import stroom.explorer.client.event.ShowFindEvent;
import stroom.explorer.client.presenter.FindPresenter.FindProxy;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerTreeFilter;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.annotations.ProxyCodeSplit;
import com.gwtplatform.mvp.client.annotations.ProxyEvent;
import com.gwtplatform.mvp.client.proxy.Proxy;

public class FindPresenter
        extends AbstractFindPresenter<FindProxy>
        implements ShowFindEvent.Handler {

    private boolean showing;

    @Inject
    public FindPresenter(final EventBus eventBus,
                         final FindView view,
                         final FindProxy proxy,
                         final FindDocResultListPresenter findResultListPresenter) {
        super(eventBus, view, proxy, findResultListPresenter);
    }

    @ProxyEvent
    @Override
    public void onShow(final ShowFindEvent event) {
        if (!showing) {
            showing = true;

            // Make sure we are set to focus text next time we show.
            getFindResultListPresenter().setFocusText(true);

            final ExplorerTreeFilter.Builder explorerTreeFilterBuilder =
                    getFindResultListPresenter().getExplorerTreeFilterBuilder();
            // Don't want favourites in the recent items as they are effectively duplicates
            explorerTreeFilterBuilder.includedRootTypes(ExplorerConstants.SYSTEM_TYPE);
            explorerTreeFilterBuilder.setNameFilter(
                    explorerTreeFilterBuilder.build().getNameFilter(),
                    true);

            // Refresh the results.
            refresh();

            final PopupSize popupSize = PopupSize.resizable(800, 600);
            ShowPopupEvent.builder(this)
                    .popupType(PopupType.CLOSE_DIALOG)
                    .popupSize(popupSize)
                    .caption("Find")
                    .onShow(e -> getView().focus())
                    .onHideRequest(HidePopupRequestEvent::hide)
                    .onHide(e -> showing = false)
                    .fire();
        }
    }

    @ProxyCodeSplit
    public interface FindProxy extends Proxy<FindPresenter> {

    }
}
