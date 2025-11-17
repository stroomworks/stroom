package stroom.visualisation.client.presenter;

import stroom.svg.shared.SvgImage;
import stroom.widget.util.client.SvgImageUtil;

import com.google.gwt.cell.client.Cell;
import com.google.gwt.cell.client.IconCellDecorator;
import com.google.gwt.core.client.GWT;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;

import java.util.Map;

/**
 * Presents an icon chosen based on the state of the VisualisationAssetItem.
 */
public class VisualisationAssetsIconCellDecorator extends IconCellDecorator<VisualisationAssetItem> {

    interface Template extends SafeHtmlTemplates {

        @Template("<div class=\"visualisation-assets-icon-wrapper\">{0}</div>")
        SafeHtml divWrapper(SafeHtml value);
    }

    /** Instance of templates */
    private static VisualisationAssetsIconCellDecorator.Template template;

    /** The icon to display for folders. That is, when !value.isLeaf() */
    private final SvgImage folderIcon;

    /** Map of file extension to icons for non-folders */
    private final Map<String, SvgImage> fileIcons;

    /** Icon to use when nothing else matches */
    private final SvgImage defaultIcon;

    /** Dummy image resource to keep superclass constructor happy (hack!) */
    private static final ImageResource DUMMY_IMG = new VisualisationAssetsImageResource(0, 0, "");

    /**
     * Constructs a decorator to decorate a cell with icons.
     * @param folderIcon The icon if the value is a folder i.e. not a leaf
     * @param fileIcons The map of file extension to image when something is a leaf
     * @param defaultIcon The icon if nothing else matches
     * @param cell The cell we're wrapping.
     */
    public VisualisationAssetsIconCellDecorator(final SvgImage folderIcon,
                                                final Map<String, SvgImage> fileIcons,
                                                final SvgImage defaultIcon,
                                                final Cell<VisualisationAssetItem> cell) {
        super(DUMMY_IMG, cell);

        if (template == null) {
            template = GWT.create(VisualisationAssetsIconCellDecorator.Template.class);
        }
        this.folderIcon = folderIcon;
        this.fileIcons = fileIcons;
        this.defaultIcon = defaultIcon;
    }

    /**
     * Called from superclass to get the HTML for a given value.
     */
    protected SafeHtml getIconHtml(final VisualisationAssetItem value) {
        final String cssClassName = "visualisation-assets-icon";

        if (value.isLeaf()) {
            // Look for the file extension
            final String filename = value.getName();
            final int dotIndex = filename.lastIndexOf('.');
            if (dotIndex != -1) {
                // Got an extension - look it up
                final String extension = filename.substring(dotIndex + 1);
                final SvgImage extIcon = fileIcons.get(extension);
                if (extIcon != null) {
                    return template.divWrapper(SvgImageUtil.toSafeHtml(extIcon, cssClassName));
                } else {
                    // Default
                    return template.divWrapper(SvgImageUtil.toSafeHtml(defaultIcon,
                            cssClassName));
                }
            } else {
                // Default
                return template.divWrapper(SvgImageUtil.toSafeHtml(defaultIcon,
                        cssClassName));
            }
        } else {
            // Not a leaf so is a folder
            return template.divWrapper(SvgImageUtil.toSafeHtml(folderIcon,
                    cssClassName));
        }
    }

}
