package stroom.document.asset.client.view;

import stroom.document.asset.client.presenter.DocumentAssetEditAssetDialogPresenter.DocumentAssetEditAssetDialogView;
import stroom.document.asset.client.presenter.assets.DocumentAssetTreeItem;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View for the dialog that uploads files into the Visualisation web asset manager.
 */
public class DocumentAssetEditAssetDialogViewImpl extends ViewImpl implements
        DocumentAssetEditAssetDialogView {

    /** GWT widget */
    private final Widget widget;

    /** Whether this is a folder or file - useful in error messages */
    private boolean leaf;

    /** ID of the item */
    private String id;

    @UiField
    TextBox txtAssetName;

    /**
     * Injected constructor.
     */
    @Inject
    @SuppressWarnings("unused")
    public DocumentAssetEditAssetDialogViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    /**
     * Sets the tree item we're editing.
     */
    @Override
    public void setTreeItem(final DocumentAssetTreeItem assetTreeItem) {
        this.txtAssetName.setText(assetTreeItem.getText());
        this.id = assetTreeItem.getId();
        this.leaf = assetTreeItem.isLeaf();
    }

    /**
     * Returns the tree item we're editing.
     */
    @Override
    public String getText() {
        return txtAssetName.getText();
    }

    /**
     * Returns whether this is a file (true) or a folder (false)
     */
    @Override
    public boolean isLeaf() {
        return leaf;
    }

    /**
     * @return The ID of the asset being edited.
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * Interface to keep GWT UiBinder happy.
     */
    public interface Binder extends UiBinder<Widget, DocumentAssetEditAssetDialogViewImpl> {
        // No code
    }
}
