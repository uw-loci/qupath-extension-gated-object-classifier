package qupath.ext.gatedobjclassifier;

import javafx.application.Platform;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.gatedobjclassifier.ui.GatedClassifierDialog;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;

import java.util.ResourceBundle;

/**
 * QuPath extension that applies a saved object classifier to a gated subset of
 * objects in the current image.
 *
 * <p>Object subsets can be defined by class membership, measurement value
 * (below / above / between thresholds), the current viewer selection, or any
 * AND combination of these. Each application is recorded as a workflow step
 * so it can be copied into a script and run for an entire project.</p>
 *
 * @author Michael Nelson
 */
public class GatedObjectClassifierExtension implements QuPathExtension, GitHubProject {

    private static final Logger logger = LoggerFactory.getLogger(GatedObjectClassifierExtension.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.gatedobjclassifier.ui.strings");

    private static final String EXTENSION_NAME = resources.getString("name");
    private static final String EXTENSION_DESCRIPTION = resources.getString("description");
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final GitHubRepo EXTENSION_REPOSITORY =
            GitHubRepo.create(EXTENSION_NAME, "MichaelSNelson", "qupath-extension-gated-object-classifier");

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }

    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }

    @Override
    public GitHubRepo getRepository() {
        return EXTENSION_REPOSITORY;
    }

    @Override
    public void installExtension(QuPathGUI qupath) {
        logger.info("Installing extension: {}", EXTENSION_NAME);
        Platform.runLater(() -> addMenuItems(qupath));
    }

    private void addMenuItems(QuPathGUI qupath) {
        var extensionMenu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);

        MenuItem applyItem = new MenuItem(resources.getString("menu.apply"));
        applyItem.disableProperty().bind(qupath.imageDataProperty().isNull());
        applyItem.setOnAction(e -> {
            logger.info("Opening Gated Object Classifier dialog");
            GatedClassifierDialog.showDialog(qupath);
        });
        extensionMenu.getItems().add(applyItem);

        logger.info("Menu items added for extension: {}", EXTENSION_NAME);
    }
}
