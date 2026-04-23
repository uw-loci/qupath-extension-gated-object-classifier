package qupath.ext.gatedobjclassifier.scripting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.gatedobjclassifier.core.ClassFilter;
import qupath.ext.gatedobjclassifier.core.Comparator;
import qupath.ext.gatedobjclassifier.core.GatedClassificationRunner;
import qupath.ext.gatedobjclassifier.core.GatingCriteria;
import qupath.ext.gatedobjclassifier.core.MeasurementFilter;
import qupath.ext.gatedobjclassifier.core.ObjectSourceMode;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.scripting.QP;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static facade used by the recorded workflow Groovy script. Keeps the script
 * small and stable by accepting a single classifier name plus an options map.
 *
 * <p>Recognised option keys (all optional except for {@code source}):</p>
 * <ul>
 *   <li>{@code source}      - one of {@code "ALL_COMPATIBLE"},
 *       {@code "SELECTED_ONLY"}, {@code "CUSTOM"}.</li>
 *   <li>{@code classes}     - {@code List<String>} of {@link PathClass}
 *       names to include (CUSTOM only); the literal
 *       {@code "(unclassified)"} matches objects with no class.</li>
 *   <li>{@code measurement} - measurement name (CUSTOM only).</li>
 *   <li>{@code op}          - {@link Comparator} name, e.g. {@code "GT"}.</li>
 *   <li>{@code value1}, {@code value2} - numeric thresholds.</li>
 *   <li>{@code preserveClass} - {@code true} to leave existing classes
 *       untouched (maps to {@code resetExistingClass=false}).</li>
 * </ul>
 */
public final class GatedObjectClassifierScripts {

    private static final Logger logger = LoggerFactory.getLogger(GatedObjectClassifierScripts.class);

    private GatedObjectClassifierScripts() {}

    /**
     * Apply the named project classifier to a gated subset of the current
     * image's objects. Designed to be called from a recorded workflow step.
     *
     * @return the number of objects whose classification changed
     */
    public static int runGatedClassifier(String classifierName, Map<String, ?> opts) {
        ImageData<BufferedImage> imageData = QP.getCurrentImageData();
        String imageLabel = describeImage(imageData);
        if (imageData == null) {
            logger.warn("[gated-classifier] No current image - skipping '{}'", classifierName);
            return 0;
        }

        ObjectClassifier<BufferedImage> classifier;
        try {
            classifier = QP.loadObjectClassifier(classifierName);
        } catch (IllegalArgumentException e) {
            logger.error("[gated-classifier] [{}] Unable to load classifier '{}': {}",
                    imageLabel, classifierName, e.getMessage());
            return 0;
        }

        GatingCriteria criteria = parseCriteria(opts);

        Collection<PathObject> selected = imageData.getHierarchy() != null
                ? imageData.getHierarchy().getSelectionModel().getSelectedObjects()
                : Collections.emptyList();

        // Batch runs with SELECTED_ONLY are almost always a mistake - call it
        // out clearly so the user can spot it in the project log.
        if (criteria.source() == ObjectSourceMode.SELECTED_ONLY && (selected == null || selected.isEmpty())) {
            logger.warn("[gated-classifier] [{}] source=SELECTED_ONLY but no objects are selected - "
                    + "this is expected during batch (Run for project) and the step is a no-op",
                    imageLabel);
            return 0;
        }

        GatedClassificationRunner.Result result = GatedClassificationRunner.run(
                imageData, classifier, classifierName, selected, criteria, false);

        if (!result.ranSuccessfully()) {
            logger.warn("[gated-classifier] [{}] '{}' classified 0 objects: {}",
                    imageLabel, classifierName,
                    result.warning == null ? "gated subset was empty" : result.warning);
        } else if (result.warning != null) {
            logger.warn("[gated-classifier] [{}] '{}' classified {} objects, {} changed - {}",
                    imageLabel, classifierName, result.nGated, result.nChanged, result.warning);
        } else {
            logger.info("[gated-classifier] [{}] '{}' classified {} objects, {} changed",
                    imageLabel, classifierName, result.nGated, result.nChanged);
        }
        return result.nChanged;
    }

    private static String describeImage(ImageData<BufferedImage> imageData) {
        if (imageData == null) {
            return "no-image";
        }
        try {
            var server = imageData.getServer();
            if (server != null) {
                String name = server.getMetadata() != null ? server.getMetadata().getName() : null;
                if (name == null || name.isBlank()) {
                    name = server.getPath();
                }
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return "image";
    }

    /** Visible for testing. Maps a Groovy options map to a {@link GatingCriteria}. */
    public static GatingCriteria parseCriteria(Map<String, ?> opts) {
        GatingCriteria.Builder b = GatingCriteria.builder();
        if (opts == null) {
            return b.build();
        }

        Object sourceRaw = opts.get("source");
        if (sourceRaw != null) {
            try {
                b.source(ObjectSourceMode.valueOf(sourceRaw.toString().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("Unknown source '{}', defaulting to ALL_COMPATIBLE", sourceRaw);
            }
        }

        List<String> classNames = readStringList(opts.get("classes"));
        if (classNames != null && !classNames.isEmpty()) {
            Set<PathClass> classes = new LinkedHashSet<>();
            boolean includeUnclassified = false;
            for (String label : classNames) {
                if (label == null) continue;
                String trimmed = label.trim();
                if (trimmed.isEmpty()) continue;
                if (ClassFilter.UNCLASSIFIED_LITERAL.equalsIgnoreCase(trimmed)) {
                    includeUnclassified = true;
                } else {
                    classes.add(PathClass.fromString(trimmed));
                }
            }
            ClassFilter cf = ClassFilter.of(classes, includeUnclassified);
            if (!cf.isAcceptAll()) {
                b.classFilter(cf);
            }
        }

        Object measurement = opts.get("measurement");
        if (measurement != null && !measurement.toString().isBlank()) {
            Object opRaw = opts.get("op");
            Comparator op = Comparator.GT;
            if (opRaw != null) {
                try {
                    op = Comparator.valueOf(opRaw.toString().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown comparator '{}', defaulting to GT", opRaw);
                }
            }
            double v1 = readDouble(opts.get("value1"), 0.0);
            double v2 = readDouble(opts.get("value2"), Double.NaN);
            b.measurementFilter(new MeasurementFilter(measurement.toString(), op, v1, v2));
        }

        Object preserve = opts.get("preserveClass");
        if (preserve instanceof Boolean) {
            b.preserveExistingClass((Boolean) preserve);
        } else if (preserve != null) {
            b.preserveExistingClass(Boolean.parseBoolean(preserve.toString()));
        }

        return b.build();
    }

    private static List<String> readStringList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Collection) {
            List<String> list = new ArrayList<>();
            for (Object o : (Collection<?>) raw) {
                if (o != null) {
                    list.add(o.toString());
                }
            }
            return list;
        }
        if (raw instanceof String[]) {
            List<String> list = new ArrayList<>();
            for (String s : (String[]) raw) {
                if (s != null) list.add(s);
            }
            return list;
        }
        return Collections.singletonList(raw.toString());
    }

    private static double readDouble(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            logger.warn("Cannot parse number '{}', using {}", raw, fallback);
            return fallback;
        }
    }
}
