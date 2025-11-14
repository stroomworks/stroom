package stroom.visualisation.client.presenter;

import com.google.gwt.cell.client.Cell;
import com.google.gwt.cell.client.IconCellDecorator;
import com.google.gwt.core.client.GWT;
import com.google.gwt.i18n.client.LocaleInfo;
import com.google.gwt.resources.client.ImageResource;
import com.google.gwt.safecss.shared.SafeStyles;
import com.google.gwt.safecss.shared.SafeStylesBuilder;
import com.google.gwt.safehtml.client.SafeHtmlTemplates;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.user.client.ui.AbstractImagePrototype;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Presents an icon chosen based on the state of the VisualisationAssetItem.
 */
public class VisualisationAssetsIconCellDecorator extends IconCellDecorator<VisualisationAssetItem> {

    interface Template extends SafeHtmlTemplates {

        @Template("<div style=\"{0}position:absolute;top:50%;line-height:0px;\">{1}</div>")
        SafeHtml imageWrapperMiddle(SafeStyles styles, SafeHtml image);
    }

    /** Instance of templates */
    private static VisualisationAssetsIconCellDecorator.Template template;

    /** The icon to display for folders. That is, when !value.isLeaf() */
    private final SafeHtml folderIconHtml;

    /** Map of file extension to icons for non-folders */
    private final Map<String, SafeHtml> fileIconsHtml;

    /** Direction of text */
    private final String direction = LocaleInfo.getCurrentLocale().isRTL()
            ? "right" : "left";

    /**
     * Constructs a decorator to decorate a cell with icons.
     * @param folderIcon The icon if the value is a folder i.e. not a leaf
     * @param fileIcons The map of file extension to image when something is a leaf
     * @param defaultIcon The icon if nothing else matches
     * @param cell The cell we're wrapping.
     */
    public VisualisationAssetsIconCellDecorator(final ImageResource folderIcon,
                                                final Map<String, ImageResource> fileIcons,
                                                final ImageResource defaultIcon,
                                                final Cell<VisualisationAssetItem> cell) {
        super(defaultIcon, cell);

        if (template == null) {
            template = GWT.create(VisualisationAssetsIconCellDecorator.Template.class);
        }
        this.folderIconHtml = getImageHtml(folderIcon);
        this.fileIconsHtml = fileIcons.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> getImageHtml(entry.getValue())));
    }

    /**
     * Called from superclass to get the HTML for a given value.
     */
    protected SafeHtml getIconHtml(final VisualisationAssetItem value) {
        if (value.isLeaf()) {
            // Look for the file extension
            final String filename = value.getName();
            final int dotIndex = filename.lastIndexOf('.');
            if (dotIndex != -1) {
                // Got an extension - look it up
                final String extension = filename.substring(dotIndex + 1);
                final SafeHtml imgHtml = fileIconsHtml.get(extension);
                if (imgHtml != null) {
                    return imgHtml;
                } else {
                    // Ask for default icon
                    return super.getIconHtml(value);
                }
            } else {
                // Ask for default icon
                return super.getIconHtml(value);
            }
        } else {
            // Not a leaf so is a folder
            return folderIconHtml;
        }
    }

    /**
     * Returns the image HTML for a given image resource.
     * @param res The image resource we want HTML for.
     * @return The HTML to put in the page.
     */
    private SafeHtml getImageHtml(final ImageResource res) {
        final AbstractImagePrototype proto = AbstractImagePrototype.create(res);
        final SafeHtml image = proto.getSafeHtml();

        final SafeStylesBuilder cssStyles =
                new SafeStylesBuilder().appendTrustedString(direction + ":0px;");
        final int halfHeight = (int) Math.round(res.getHeight() / 2.0);
        cssStyles.appendTrustedString("margin-top:-" + halfHeight + "px;");
        return template.imageWrapperMiddle(cssStyles.toSafeStyles(), image);
    }

}
