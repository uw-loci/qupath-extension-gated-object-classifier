package qupath.ext.gatedobjclassifier.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.projects.Project;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Safe wrappers around {@link Project#getObjectClassifiers()} that swallow
 * {@link IOException}s and log them, so callers (the GUI) can stay focused on
 * presentation.
 *
 * <p>This is intentionally project-scoped only; loading from a file outside the
 * project is supported via {@link qupath.lib.scripting.QP#loadObjectClassifier}
 * and is deferred from v0.1 of the extension.</p>
 */
public final class ClassifierLoader {

    private static final Logger logger = LoggerFactory.getLogger(ClassifierLoader.class);

    private ClassifierLoader() {}

    /**
     * List the names of object classifiers saved in the project, sorted
     * alphabetically. Returns an empty list when no project is open or the
     * classifier directory is missing.
     */
    public static List<String> listNames(Project<BufferedImage> project) {
        if (project == null) {
            return Collections.emptyList();
        }
        try {
            var manager = project.getObjectClassifiers();
            if (manager == null) {
                return Collections.emptyList();
            }
            List<String> names = new ArrayList<>(manager.getNames());
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            return names;
        } catch (IOException e) {
            logger.warn("Unable to list object classifiers in project: {}", e.getMessage());
            logger.debug("Listing object classifiers failed", e);
            return Collections.emptyList();
        }
    }

    /**
     * Load a classifier from the project by name. Returns {@code null} if
     * either the project is missing, the name is missing, or the load fails.
     */
    public static ObjectClassifier<BufferedImage> load(Project<BufferedImage> project, String name) {
        if (project == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            var manager = project.getObjectClassifiers();
            if (manager == null || !manager.contains(name)) {
                return null;
            }
            return manager.get(name);
        } catch (IOException e) {
            logger.warn("Unable to load object classifier '{}': {}", name, e.getMessage());
            logger.debug("Loading object classifier failed", e);
            return null;
        }
    }
}
