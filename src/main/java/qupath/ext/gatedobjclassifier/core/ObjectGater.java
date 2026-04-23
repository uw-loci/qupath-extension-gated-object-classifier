package qupath.ext.gatedobjclassifier.core;

import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Pure subset logic that turns a candidate {@code universe} of objects into the
 * gated set the classifier should be applied to.
 *
 * <p>Stateless and free of QuPath GUI / JavaFX dependencies so it can be unit
 * tested without a running QuPath instance.</p>
 */
public final class ObjectGater {

    private ObjectGater() {}

    /**
     * Apply the {@link GatingCriteria} to a {@code universe} of candidate
     * objects (typically the result of
     * {@code classifier.getCompatibleObjects(imageData)}).
     *
     * <p>For {@link ObjectSourceMode#SELECTED_ONLY} the universe is intersected
     * with {@code selected}; class and measurement filters are then AND-applied
     * if present.</p>
     *
     * @param universe all classifier-compatible objects in the image
     * @param selected currently selected objects in the hierarchy (may be
     *                 {@code null} or empty)
     * @param criteria the gating criteria
     * @return the gated subset, in the same iteration order as {@code universe}
     */
    public static List<PathObject> apply(Collection<? extends PathObject> universe,
                                         Collection<? extends PathObject> selected,
                                         GatingCriteria criteria) {
        if (universe == null || universe.isEmpty()) {
            return Collections.emptyList();
        }

        Collection<? extends PathObject> base;
        if (criteria.source() == ObjectSourceMode.SELECTED_ONLY) {
            if (selected == null || selected.isEmpty()) {
                return Collections.emptyList();
            }
            // identity intersection - PathObject equality is identity-based but be defensive
            IdentityHashMap<PathObject, Boolean> selectedSet = new IdentityHashMap<>(selected.size());
            for (PathObject o : selected) {
                selectedSet.put(o, Boolean.TRUE);
            }
            List<PathObject> intersected = new ArrayList<>();
            for (PathObject o : universe) {
                if (selectedSet.containsKey(o)) {
                    intersected.add(o);
                }
            }
            base = intersected;
        } else {
            base = universe;
        }

        boolean applyClass = criteria.source() == ObjectSourceMode.CUSTOM
                && criteria.classFilter().isPresent()
                && !criteria.classFilter().get().isAcceptAll();
        boolean applyMeasurement = criteria.source() == ObjectSourceMode.CUSTOM
                && criteria.measurementFilter().isPresent();

        if (!applyClass && !applyMeasurement) {
            return new ArrayList<>(base);
        }

        ClassFilter cf = applyClass ? criteria.classFilter().orElse(null) : null;
        MeasurementFilter mf = applyMeasurement ? criteria.measurementFilter().orElse(null) : null;
        List<PathObject> out = new ArrayList<>();
        for (PathObject o : base) {
            if (cf != null && !cf.accepts(o)) continue;
            if (mf != null && !mf.accepts(o)) continue;
            out.add(o);
        }
        return out;
    }

    /**
     * Convenience overload taking the universe and selection as plain
     * {@link Set}s.
     */
    public static List<PathObject> apply(Set<? extends PathObject> universe,
                                         Set<? extends PathObject> selected,
                                         GatingCriteria criteria) {
        return apply((Collection<? extends PathObject>) universe,
                (Collection<? extends PathObject>) selected,
                criteria);
    }
}
