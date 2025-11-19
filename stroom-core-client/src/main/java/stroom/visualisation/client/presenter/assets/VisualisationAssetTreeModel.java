package stroom.visualisation.client.presenter.assets;

import stroom.svg.shared.SvgImage;
import stroom.visualisation.client.presenter.tree.UpdatableTreeModel;
import stroom.visualisation.client.presenter.tree.UpdatableTreeNode;

import com.google.gwt.cell.client.Cell;
import com.google.gwt.view.client.SingleSelectionModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Tree model for the visualisation asset tree model. Extends the UpdatableTreeModel
 * to add support for icons.
 */
public class VisualisationAssetTreeModel extends UpdatableTreeModel {

    /** Map of extension to image */
    private static final Map<String, SvgImage> FILE_ICONS = new HashMap<>();

    public VisualisationAssetTreeModel(final SingleSelectionModel<UpdatableTreeNode> selectionModelCellTree) {
        super(selectionModelCellTree);
    }

    /*
     * Initialise the map of extension to file icon.
     */
    static {
        FILE_ICONS.put("png",  SvgImage.FILE_IMAGE);
        FILE_ICONS.put("jpg",  SvgImage.FILE_IMAGE);
        FILE_ICONS.put("jpeg", SvgImage.FILE_IMAGE);
        FILE_ICONS.put("gif",  SvgImage.FILE_IMAGE);
        FILE_ICONS.put("webp", SvgImage.FILE_IMAGE);
        FILE_ICONS.put("svg",  SvgImage.FILE_IMAGE);
        FILE_ICONS.put("css",  SvgImage.FILE_RAW);
        FILE_ICONS.put("htm",  SvgImage.FILE_FORMATTED);
        FILE_ICONS.put("html", SvgImage.FILE_FORMATTED);
    }

    /**
     * Adds
     * @param inputBoxSize The size of the input box, as set in the constructor.
     * @param value The node
     * @return The decorated cell to display different icons.
     */
    @Override
    protected Cell<UpdatableTreeNode> getCell(final int inputBoxSize, final UpdatableTreeNode value) {
        final Cell<UpdatableTreeNode> textCell = super.getCell(inputBoxSize, value);
        return new VisualisationAssetsIconCellDecorator(
                SvgImage.FOLDER,
                FILE_ICONS,
                SvgImage.FILE,
                textCell);
    }

}
