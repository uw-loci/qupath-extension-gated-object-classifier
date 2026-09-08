package qupath.ext.classifyobjectsubset.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates a single subset-classification run.
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Resolve the universe of compatible objects from the classifier;</li>
 *   <li>Compute the object subset via {@link ObjectSubsetSelector};</li>
 *   <li>Call {@link ObjectClassifier#classifyObjects(ImageData, Collection, boolean)};</li>
 *   <li>Fire a hierarchy classification-changed event so the UI updates and
 *       the project saves the result;</li>
 *   <li>Optionally append a {@link WorkflowScriptBuilder} step to the image
 *       history workflow so the user can copy the operation as a script.</li>
 * </ol>
 */
public final class SubsetClassificationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SubsetClassificationRunner.class);

    private SubsetClassificationRunner() {}

    public static Result run(ImageData<BufferedImage> imageData,
                             ObjectClassifier<BufferedImage> classifier,
                             String classifierName,
                             Collection<? extends PathObject> selectedObjects,
                             SubsetCriteria criteria,
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
        List<PathObject> subset = new ArrayList<>(
                ObjectSubsetSelector.apply(universe, selectedObjects == null ? Collections.emptyList() : selectedObjects, criteria));
        if (subset.isEmpty()) {
            return new Result(universe.size(), 0, 0, "No objects match the current filters.");
        }

        // Surface missing-feature warnings the same way QuPath's built-in command does
        String missingWarning = null;
        try {
            Map<String, Integer> missing = classifier.getMissingFeatures(imageData, subset);
            if (missing != null && !missing.isEmpty()) {
                missingWarning = formatMissingFeatures(missing);
                logger.warn("Classifier has missing features: {}", missingWarning);
            }
        } catch (Exception e) {
            logger.debug("Unable to compute missing features", e);
        }

        boolean resetExistingClass = !criteria.preserveExistingClass();

        // Snapshot the classifications so we can report how many actually
        // changed. ObjectClassifier.classifyObjects is documented as returning
        // "the number of objects whose classification was changed", but the
        // implementations do not honour that - OpenCVMLClassifier ends its loop
        // with `counter += tempObjectList.size()`, i.e. it counts objects
        // PROCESSED. Passing that straight through made the notification read
        // "132,331 objects classified, 132,331 changed" on every run (issue #3).
        PathClass[] classesBefore = new PathClass[subset.size()];
        for (int i = 0; i < classesBefore.length; i++) {
            classesBefore[i] = subset.get(i).getPathClass();
        }

        int nProcessed;
        try {
            nProcessed = classifier.classifyObjects(imageData, subset, resetExistingClass);
        } catch (RuntimeException e) {
            logger.error("Classifier threw while classifying the object subset", e);
            return Result.error("Classifier failed: " + e.getMessage());
        }

        int nChanged = 0;
        for (int i = 0; i < classesBefore.length; i++) {
            if (!Objects.equals(classesBefore[i], subset.get(i).getPathClass())) {
                nChanged++;
            }
        }

        // A zero return with a non-empty subset is how the classifier reports
        // that it bailed out entirely (interrupted, or no feature extractor).
        // Without this the run would be announced as a success that changed
        // nothing, which looks identical to "the classifier agreed with every
        // existing class".
        if (nProcessed == 0) {
            logger.warn("Classifier processed 0 of {} objects - it may have been interrupted, "
                    + "or it has no feature extractor", subset.size());
            return new Result(universe.size(), subset.size(), 0,
                    "The classifier did not process any objects. It may have been interrupted, "
                    + "or it cannot extract features for these objects.");
        }

        if (nChanged > 0) {
            PathObjectHierarchy hierarchy = imageData.getHierarchy();
            if (hierarchy != null) {
                hierarchy.fireObjectClassificationsChangedEvent(classifier, subset);
            }
        }

        if (recordWorkflow) {
            try {
                imageData.getHistoryWorkflow().addStep(WorkflowScriptBuilder.build(classifierName, criteria));
            } catch (Exception e) {
                logger.warn("Failed to add workflow step for subset classification", e);
            }
        }

        logger.info("Subset classification: {} of {} objects classified, {} changed",
                subset.size(), universe.size(), nChanged);
        return new Result(universe.size(), subset.size(), nChanged, missingWarning);
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
     *
     * <p>{@code nSelected} is how many objects the classifier was applied to;
     * {@code nChanged} is how many of those ended up with a different
     * classification than they started with, counted here rather than taken
     * from the classifier's return value.</p>
     */
    public static final class Result {
        public final int nUniverse;
        public final int nSelected;
        public final int nChanged;
        public final String warning;

        public Result(int nUniverse, int nSelected, int nChanged, String warning) {
            this.nUniverse = nUniverse;
            this.nSelected = nSelected;
            this.nChanged = nChanged;
            this.warning = warning;
        }

        public boolean ranSuccessfully() {
            return nSelected > 0;
        }

        public static Result error(String message) {
            return new Result(0, 0, 0, message);
        }
    }
}
