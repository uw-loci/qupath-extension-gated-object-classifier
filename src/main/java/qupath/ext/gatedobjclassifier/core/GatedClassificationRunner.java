package qupath.ext.gatedobjclassifier.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a single gated-classification run.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Resolve the universe of compatible objects from the classifier;</li>
 *   <li>Compute the gated subset via {@link ObjectGater};</li>
 *   <li>Call {@link ObjectClassifier#classifyObjects(ImageData, Collection, boolean)};</li>
 *   <li>Fire a hierarchy classification-changed event so the UI updates and
 *       the project saves the result;</li>
 *   <li>Optionally append a {@link WorkflowScriptBuilder} step to the image
 *       history workflow so the user can copy the operation as a script.</li>
 * </ol>
 */
public final class GatedClassificationRunner {

    private static final Logger logger = LoggerFactory.getLogger(GatedClassificationRunner.class);

    private GatedClassificationRunner() {}

    public static Result run(ImageData<BufferedImage> imageData,
                             ObjectClassifier<BufferedImage> classifier,
                             String classifierName,
                             Collection<? extends PathObject> selectedObjects,
                             GatingCriteria criteria,
                             boolean recordWorkflow) {
        if (imageData == null) {
            return Result.error("No image data available.");
        }
        if (classifier == null) {
            return Result.error("Classifier was not provided.");
        }

        Collection<PathObject> universe = classifier.getCompatibleObjects(imageData);
        if (universe == null || universe.isEmpty()) {
            return new Result(0, 0, 0, "Classifier has no compatible objects in this image.");
        }

        // Snapshot to avoid concurrent modification during classification
        List<PathObject> gated = new ArrayList<>(
                ObjectGater.apply(universe, selectedObjects == null ? Collections.emptyList() : selectedObjects, criteria));
        if (gated.isEmpty()) {
            return new Result(universe.size(), 0, 0, "No objects match the current filters.");
        }

        // Surface missing-feature warnings the same way QuPath's built-in command does
        String missingWarning = null;
        try {
            Map<String, Integer> missing = classifier.getMissingFeatures(imageData, gated);
            if (missing != null && !missing.isEmpty()) {
                missingWarning = formatMissingFeatures(missing);
                logger.warn("Classifier has missing features: {}", missingWarning);
            }
        } catch (Exception e) {
            logger.debug("Unable to compute missing features", e);
        }

        boolean resetExistingClass = !criteria.preserveExistingClass();
        int nChanged;
        try {
            nChanged = classifier.classifyObjects(imageData, gated, resetExistingClass);
        } catch (RuntimeException e) {
            logger.error("Classifier threw while classifying gated objects", e);
            return Result.error("Classifier failed: " + e.getMessage());
        }

        if (nChanged > 0) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            if (hierarchy != null) {
                hierarchy.fireObjectClassificationsChangedEvent(classifier, gated);
            }
        }

        if (recordWorkflow) {
            try {
                imageData.getHistoryWorkflow().addStep(WorkflowScriptBuilder.build(classifierName, criteria));
            } catch (Exception e) {
                logger.warn("Failed to add workflow step for gated classification", e);
            }
        }

        logger.info("Gated classification: {} of {} objects classified, {} changed",
                gated.size(), universe.size(), nChanged);
        return new Result(universe.size(), gated.size(), nChanged, missingWarning);
    }

    private static String formatMissingFeatures(Map<String, Integer> missing) {
        StringBuilder sb = new StringBuilder("Missing features: ");
        boolean first = true;
        int shown = 0;
        for (var entry : missing.entrySet()) {
            if (!first) sb.append("; ");
            first = false;
            sb.append('\'').append(entry.getKey()).append("' (").append(entry.getValue()).append(" objects)");
            shown++;
            if (shown >= 5 && missing.size() > shown) {
                sb.append("; ... ").append(missing.size() - shown).append(" more");
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Outcome of a single run. {@code warning} is non-null when the run could
     * not classify anything (no compatible objects, empty subset) or when the
     * classifier reported missing features.
     */
    public static final class Result {
        public final int nUniverse;
        public final int nGated;
        public final int nChanged;
        public final String warning;

        public Result(int nUniverse, int nGated, int nChanged, String warning) {
            this.nUniverse = nUniverse;
            this.nGated = nGated;
            this.nChanged = nChanged;
            this.warning = warning;
        }

        public boolean ranSuccessfully() {
            return nGated > 0;
        }

        public static Result error(String message) {
            return new Result(0, 0, 0, message);
        }
    }
}
