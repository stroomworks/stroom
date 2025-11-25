package stroom.visualisation.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.AbstractSubmitCompleteHandler;
import stroom.dispatch.client.RestFactory;
import stroom.importexport.client.presenter.ImportUtil;
import stroom.util.client.Console;
import stroom.util.shared.NullSafe;
import stroom.util.shared.ResourceKey;
import stroom.visualisation.client.presenter.VisualisationAssetsUploadFileDialogPresenter.VisualisationAssetsUploadFileDialogView;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.event.shared.HasHandlers;
import com.google.gwt.user.client.ui.FileUpload;
import com.google.gwt.user.client.ui.FormPanel;
import com.google.gwt.user.client.ui.FormPanel.SubmitEvent;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

public class VisualisationAssetsUploadFileDialogPresenter
        extends MyPresenterWidget<VisualisationAssetsUploadFileDialogView>
        implements HasHandlers {

    /** Allows us to stop the hide request for the dialog */
    private HidePopupRequestEvent currentHideRequest;

    // TODO
    private final RestFactory restFactory;

    /** What to call when the upload has been successful */
    private VisualisationAssetsAddFileCallback addFileCallback;

    /** Where this file is being added */
    private UpdatableTreeNode parentFolderNode;

    /** Width of dialog */
    private static final int DIALOG_WIDTH = 300;

    /** Height of dialog */
    private static final int DIALOG_HEIGHT = 300;

    /** Server end of REST */
    //private static final VisualisationResource VISUALISATION_RESOURCE = GWT.create(VisualisationResource.class);

    /**
     * Thing that responds to submit events within the page, when the file is uploaded.
     */
    final AbstractSubmitCompleteHandler submitCompleteHandler =
            new AbstractSubmitCompleteHandler("Uploading file", this) {

                @Override
                public void onSubmit(final SubmitEvent event) {
                    Console.info("Submit handler: onSubmit()");
                    if (!checkValid()) {
                        event.cancel();
                        currentHideRequest.reset();
                    } else {
                        super.onSubmit(event);
                    }
                }

                @Override
                protected void onSuccess(final ResourceKey resourceKey) {
                    Console.info("Submit handler: onSuccess(" + resourceKey.getKey() + ")");
                    final String fileName = parseFakeFilename(getView().getFileUpload().getFilename());
                    Console.info("Filename: " + fileName + "; name: " + getView().getFileUpload().getName());

                    addFileCallback.addUploadedFile(parentFolderNode, fileName, resourceKey);
                    currentHideRequest.hide();

                    // Do REST call so server knows about file uploaded
                    /*
                    final UploadFileRequest request = new UploadFileRequest(resourceKey, fileName);
                    restFactory
                            .create(VISUALISATION_RESOURCE)
                            .method(res -> res.upload(request))
                            .onSuccess(result ->
                                    AlertEvent.fireInfo(VisualisationAssetsAddDialogPresenter.this,
                                            "Uploaded file",
                                            () -> {
                                                currentHideRequest.hide();
                                            }))
                            .onFailure(throwable -> AlertEvent.fireError(
                                    VisualisationAssetsAddDialogPresenter.this,
                                    "Error uploading file: " + throwable.getMessage(),
                                    currentHideRequest::reset))
                            .taskMonitorFactory(VisualisationAssetsAddDialogPresenter.this)
                            .exec();

                     */
                }

                @Override
                protected void onFailure(final String message) {
                    Console.info("Submit handler: onFailure()");
                    AlertEvent.fireError(VisualisationAssetsUploadFileDialogPresenter.this,
                            message,
                            currentHideRequest::reset);
                }
            };

    /**
     * Injected constructor.
     */
    @SuppressWarnings("unused")
    @Inject
    public VisualisationAssetsUploadFileDialogPresenter(final EventBus eventBus,
                                                        final VisualisationAssetsUploadFileDialogView view,
                                                        final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        final FormPanel form = view.getForm();

        // Setup the form for file upload
        Console.info("Posting files to " + ImportUtil.getImportFileURL());
        form.setAction(ImportUtil.getImportFileURL());
        form.setEncoding(FormPanel.ENCODING_MULTIPART);
        form.setMethod(FormPanel.METHOD_POST);


    }

    /**
     * Call to prepare and show the dialog.
     *
     * @param path The path that we're adding the item at.
     */
    public void fireShowPopup(final VisualisationAssetsAddFileCallback addFileCallback,
                              final UpdatableTreeNode parentFolderNode,
                              final String path) {

        this.getView().setPath(path);
        this.parentFolderNode = parentFolderNode;
        this.addFileCallback = addFileCallback;
        this.currentHideRequest = null;

        // Register the handler that gets events about the upload of the file
        // Handlers need to be re-registered for each upload - they only work once
        final FormPanel form = this.getView().getForm();
        registerHandler(form.addSubmitHandler(submitCompleteHandler));
        registerHandler(form.addSubmitCompleteHandler(submitCompleteHandler));

        final ShowPopupEvent.Builder builder = ShowPopupEvent.builder(this);
        builder.popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(DIALOG_WIDTH, DIALOG_HEIGHT))
                .caption("Add File")
                .modal(true)
                .onHideRequest(e -> {
                    Console.info("Hide request");
                    currentHideRequest = e;
                    if (e.isOk()) {
                        Console.info("OK button pressed");
                        if (checkValid()) {
                            // Submit the form
                            Console.info("Submitting form");
                            getView().getForm().submit();
                        } else {
                            e.reset();
                        }
                    } else {
                        e.hide();
                    }
                }).fire();
    }

    /**
     * Checks if the data in the dialog is valid. If not shows a warning dialog and returns false.
     * @return true if everything is ok. False if not ok.
     */
    public boolean checkValid() {
        final String filename = getView().getFileUpload().getFilename();
        if (NullSafe.isBlankString(filename)) {
            AlertEvent.fireWarn(this, "File not set", null);
            return false;
        }

        return true;
    }

    /**
     * Removes any paths from the filename returned by the browser.
     * Chrome returns a path like C:\fakepath\actual-filename.ext on Linux.
     * Not sure about other browsers.
     * @param fakeFilename The filename given by the browser.
     * @return The filename part of the path.
     */
    private String parseFakeFilename(final String fakeFilename) {
        String filename = fakeFilename;

        final int iSlash = fakeFilename.lastIndexOf('\\');
        if ((iSlash != -1) && (iSlash + 1 < fakeFilename.length())) {
            filename = fakeFilename.substring(iSlash + 1);
        }
        return filename;
    }

    public interface VisualisationAssetsUploadFileDialogView extends View {

        /**
         * Sets the path where this asset will be added.
         */
        void setPath(String path);

        /**
         * Gets the file upload widget.
         */
        FileUpload getFileUpload();

        /**
         * Gets the panel that the file upload widget is in.
         */
        FormPanel getForm();

    }

}
