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
 *   <li>{@code classes}     - {@code List} of {@link PathClass} entries
 *       to include (CUSTOM only). Each entry is either a String (parsed via
 *       {@code PathClass.fromString}, which treats ":" as a derived-class
 *       separator) or a nested {@code List<String>} of component names
 *       (parsed via {@code PathClass.fromCollection}, which preserves each
 *       element verbatim - use this form for class names that themselves
 *       contain ":"). The literal {@code "(unclassified)"} matches objects
 *       with no class.</li>
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

        Object classesRaw = opts.get("classes");
        if (classesRaw != null) {
            Set<PathClass> classes = new LinkedHashSet<>();
            boolean includeUnclassified = false;
            for (Object entry : readClassEntries(classesRaw)) {
                if (entry == null) continue;
                if (entry instanceof Collection<?> coll) {
                    // Atomic-per-element form: each element is one component
                    // name, never split on ':'. Lets class names like
                    // "CD3+: CD8+" round-trip as a single class.
                    List<String> components = new ArrayList<>();
                    for (Object o : coll) {
                        if (o == null) continue;
                        String s = o.toString().trim();
                        if (!s.isEmpty()) components.add(s);
                    }
                    if (components.isEmpty()) continue;
                    if (components.size() == 1
                            && ClassFilter.UNCLASSIFIED_LITERAL.equalsIgnoreCase(components.get(0))) {
                        includeUnclassified = true;
                    } else {
                        classes.add(PathClass.fromCollection(components));
                    }
                } else {
                    // Plain String entry: legacy / hand-edited form.
                    // Parsed via fromString, which splits on ':'.
                    String trimmed = entry.toString().trim();
                    if (trimmed.isEmpty()) continue;
                    if (ClassFilter.UNCLASSIFIED_LITERAL.equalsIgnoreCase(trimmed)) {
                        includeUnclassified = true;
                    } else {
                        if (trimmed.contains(":")) {
                            logger.warn("[gated-classifier] Class '{}' contains ':' and will be parsed as a derived class. "
                                    + "To treat it as a single class, encode it as a list, e.g. [\"{}\"].",
                                    trimmed, trimmed);
                        }
                        classes.add(PathClass.fromString(trimmed));
                    }
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

    /**
     * Iterate the raw {@code classes} option without flattening nested
     * collections, so each entry can be inspected as either a String
     * (legacy form, parsed via {@link PathClass#fromString}) or a
     * Collection (atomic-per-element form, reconstructed via
     * {@link PathClass#fromCollection}).
     */
    private static List<Object> readClassEntries(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof Collection) {
            List<Object> list = new ArrayList<>();
            for (Object o : (Collection<?>) raw) {
                list.add(o);
            }
            return list;
        }
        if (raw instanceof Object[]) {
            List<Object> list = new ArrayList<>();
            for (Object o : (Object[]) raw) {
                list.add(o);
            }
            return list;
        }
        return Collections.singletonList(raw);
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
