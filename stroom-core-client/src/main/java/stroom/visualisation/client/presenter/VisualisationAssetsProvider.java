package stroom.visualisation.client.presenter;

import stroom.entity.client.presenter.AbstractTabProvider;

import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Provider;

/**
 * Provides a way to get the VisualisationAssetsPresenter within the VisualisationPresenter.
 * Ensures that the dirty handler is hooked up.
 * @param <D> The document type.
 */
public abstract class VisualisationAssetsProvider<D> extends AbstractTabProvider<D, VisualisationAssetsPresenter> {

    private final Provider<VisualisationAssetsPresenter> presenterProvider;

    public VisualisationAssetsProvider(final EventBus eventBus,
                                       final Provider<VisualisationAssetsPresenter> presenterProvider) {
        super(eventBus);
        this.presenterProvider = presenterProvider;
    }

    @Override
    protected final VisualisationAssetsPresenter createPresenter() {
        final VisualisationAssetsPresenter assetsPresenter = presenterProvider.get();
        registerHandler(assetsPresenter.addDirtyHandler(event -> fireDirtyEvent(true)));
        return assetsPresenter;
    }
}
