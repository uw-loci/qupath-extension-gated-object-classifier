package qupath.ext.classifyobjectsubset.core;

import qupath.lib.objects.PathObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Pure subset logic that turns a candidate {@code universe} of objects into the
 * selected set the classifier should be applied to.
 *
 * <p>Stateless and free of QuPath GUI / JavaFX dependencies so it can be unit
 * tested without a running QuPath instance.</p>
 */
public final class ObjectSubsetSelector {

    private ObjectSubsetSelector() {}

    /**
     * Apply the {@link SubsetCriteria} to a {@code universe} of candidate
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
     * @param criteria the subset criteria
     * @return the object subset, in the same iteration order as {@code universe}
     */
    public static List<PathObject> apply(Collection<? extends PathObject> universe,
                                         Collection<? extends PathObject> selected,
                                         SubsetCriteria criteria) {
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
                && !criteria.measurementFilters().isEmpty();

        if (!applyClass && !applyMeasurement) {
            return new ArrayList<>(base);
        }

        ClassFilter cf = applyClass ? criteria.classFilter().orElse(null) : null;
        List<MeasurementFilter> mfs = applyMeasurement
                ? criteria.measurementFilters() : Collections.emptyList();
        List<PathObject> out = new ArrayList<>();
        for (PathObject o : base) {
            if (cf != null && !cf.accepts(o)) continue;
            boolean passesAll = true;
            for (MeasurementFilter mf : mfs) {
                if (!mf.accepts(o)) {
                    passesAll = false;
                    break;
                }
            }
            if (!passesAll) continue;
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
                                         SubsetCriteria criteria) {
        return apply((Collection<? extends PathObject>) universe,
                (Collection<? extends PathObject>) selected,
                criteria);
    }
}
