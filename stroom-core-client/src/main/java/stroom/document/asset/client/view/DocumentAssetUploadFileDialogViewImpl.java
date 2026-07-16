package stroom.document.asset.client.view;

import stroom.document.asset.client.presenter.DocumentAssetUploadFileDialogPresenter.DocumentAssetUploadFileDialogView;
import stroom.widget.form.client.CustomFileUpload;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View for the dialog that uploads files into the Visualisation web asset manager.
 */
public class DocumentAssetUploadFileDialogViewImpl extends ViewImpl implements
        DocumentAssetUploadFileDialogView {

    /**
     * GWT widget
     */
    private final Widget widget;

    @UiField
    CustomFileUpload fileUpload;
    @UiField
    Label lblPath;

    /**
     * Injected constructor.
     */
    @Inject
    @SuppressWarnings("unused")
    public DocumentAssetUploadFileDialogViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    /**
     * Sets the path where this file will be placed. Provided as an aid for the
     * user so they know where stuff will go.
     *
     * @param path The path to display to the user.
     */
    @Override
    public void setPath(final String path) {
        lblPath.setText(path);
    }

    /**
     * Gets the file upload widget.
     */
    @Override
    public CustomFileUpload getFileUpload() {
        return fileUpload;
    }

    /**
     * Interface to keep GWT UiBinder happy.
     */
    public interface Binder extends UiBinder<Widget, DocumentAssetUploadFileDialogViewImpl> {
        // No code
    }
}
