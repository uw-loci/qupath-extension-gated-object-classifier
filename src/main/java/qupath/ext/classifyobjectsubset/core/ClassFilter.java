package qupath.ext.classifyobjectsubset.core;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accept-set of {@link PathClass} values, where {@link Optional#empty()}
 * represents the "unclassified" sentinel (a {@code PathObject} whose
 * {@code getPathClass()} returns {@code null}).
 *
 * <p>An empty accept-set means "accept everything" so the filter is
 * effectively disabled.</p>
 *
 * <p>The {@link MatchMode} decides how an accepted entry is compared against an
 * object's class. {@link MatchMode#EXACT} requires the whole classification to
 * be equal; {@link MatchMode#INCLUDE_DERIVED} accepts any classification whose
 * name components are a superset of the accepted entry's, so "T cell" also
 * matches "T cell: CD8" and "CD8: T cell". Component names are compared whole,
 * so "CD3" never matches "CD31".</p>
 *
 * <p>The literal string used in recorded workflow scripts to denote the
 * unclassified sentinel is {@value #UNCLASSIFIED_LITERAL}.</p>
 */
public final class ClassFilter {

    public static final String UNCLASSIFIED_LITERAL = "(unclassified)";

    /** How an accepted entry is compared against an object's classification. */
    public enum MatchMode {
        /** The object's whole classification must equal an accepted entry. */
        EXACT,
        /**
         * The object's classification must <i>contain</i> all name components of
         * an accepted entry, so derived classes match their ancestors.
         */
        INCLUDE_DERIVED
    }

    private final Set<Optional<PathClass>> accepted;
    private final MatchMode matchMode;

    /**
     * Component-name sets of the accepted classes, precomputed once so the
     * per-object hot loop is a set-containment test rather than a walk up the
     * parent chain. Empty when {@link #matchMode} is {@link MatchMode#EXACT}.
     */
    private final List<Set<String>> derivedTargets;

    /**
     * Memoises the containment verdict per {@link PathClass}. A hierarchy holds
     * hundreds of thousands of objects but only tens of distinct classes, so
     * this turns an O(objects x accepted) scan into one hash lookup per object.
     */
    private final Map<PathClass, Boolean> derivedMatchCache;

    private ClassFilter(Set<Optional<PathClass>> accepted, MatchMode matchMode) {
        this.accepted = Collections.unmodifiableSet(new LinkedHashSet<>(accepted));
        this.matchMode = matchMode == null ? MatchMode.EXACT : matchMode;
        if (this.matchMode == MatchMode.INCLUDE_DERIVED) {
            List<Set<String>> targets = new ArrayList<>();
            for (Optional<PathClass> entry : this.accepted) {
                // The unclassified sentinel never participates in containment:
                // every class trivially contains the empty component set, so
                // letting it through here would accept every object.
                entry.ifPresent(pc -> {
                    Set<String> components = pc.toSet();
                    if (components != null && !components.isEmpty()) {
                        targets.add(components);
                    }
                });
            }
            this.derivedTargets = List.copyOf(targets);
            this.derivedMatchCache = this.derivedTargets.isEmpty()
                    ? Collections.emptyMap() : new ConcurrentHashMap<>();
        } else {
            this.derivedTargets = Collections.emptyList();
            this.derivedMatchCache = Collections.emptyMap();
        }
    }

    /** Filter that accepts every object regardless of class. */
    public static ClassFilter acceptAll() {
        return new ClassFilter(Collections.emptySet(), MatchMode.EXACT);
    }

    public static ClassFilter of(Set<Optional<PathClass>> accepted) {
        return of(accepted, MatchMode.EXACT);
    }

    public static ClassFilter of(Set<Optional<PathClass>> accepted, MatchMode matchMode) {
        return new ClassFilter(accepted == null ? Collections.emptySet() : accepted, matchMode);
    }

    /**
     * Build a filter from a mixed set of {@link PathClass} entries plus an
     * optional flag indicating whether unclassified objects should be accepted,
     * matching each entry exactly.
     */
    public static ClassFilter of(Set<PathClass> pathClasses, boolean includeUnclassified) {
        return of(pathClasses, includeUnclassified, MatchMode.EXACT);
    }

    /**
     * Build a filter from a mixed set of {@link PathClass} entries plus an
     * optional flag indicating whether unclassified objects should be accepted.
     *
     * @param matchMode whether accepted entries match exactly or also match
     *                  classifications derived from them
     */
    public static ClassFilter of(Set<PathClass> pathClasses, boolean includeUnclassified, MatchMode matchMode) {
        Set<Optional<PathClass>> set = new LinkedHashSet<>();
        if (pathClasses != null) {
            for (PathClass pc : pathClasses) {
                if (pc != null) {
                    set.add(Optional.of(pc));
                }
            }
        }
        if (includeUnclassified) {
            set.add(Optional.empty());
        }
        return new ClassFilter(set, matchMode);
    }

    /** True when the filter accepts every object (no constraint set). */
    public boolean isAcceptAll() {
        return accepted.isEmpty();
    }

    public Set<Optional<PathClass>> accepted() {
        return accepted;
    }

    public MatchMode matchMode() {
        return matchMode;
    }

    /** True when derived classifications also match their accepted ancestors. */
    public boolean includesDerived() {
        return matchMode == MatchMode.INCLUDE_DERIVED;
    }

    public boolean accepts(PathObject object) {
        if (isAcceptAll()) {
            return true;
        }
        if (object == null) {
            return false;
        }
        PathClass pathClass = object.getPathClass();
        if (pathClass == null || pathClass == PathClass.NULL_CLASS) {
            return includesUnclassified();
        }
        if (accepted.contains(Optional.of(pathClass))) {
            return true;
        }
        if (derivedTargets.isEmpty()) {
            return false;
        }
        return derivedMatchCache.computeIfAbsent(pathClass, this::containsAnyTarget);
    }

    private boolean containsAnyTarget(PathClass pathClass) {
        Set<String> components = pathClass.toSet();
        if (components == null || components.isEmpty()) {
            return false;
        }
        for (Set<String> target : derivedTargets) {
            if (components.containsAll(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the {@link PathClass} entries (excluding the unclassified
     * sentinel), in insertion order.
     */
    public Set<PathClass> pathClasses() {
        Set<PathClass> classes = new LinkedHashSet<>();
        for (Optional<PathClass> entry : accepted) {
            entry.ifPresent(classes::add);
        }
        return Collections.unmodifiableSet(classes);
    }

    public boolean includesUnclassified() {
        return accepted.contains(Optional.<PathClass>empty());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClassFilter)) return false;
        ClassFilter other = (ClassFilter) o;
        return accepted.equals(other.accepted) && matchMode == other.matchMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(accepted, matchMode);
    }

    @Override
    public String toString() {
        if (isAcceptAll()) {
            return "ClassFilter[accept all]";
        }
        Set<String> labels = new HashSet<>();
        for (Optional<PathClass> entry : accepted) {
            labels.add(entry.map(PathClass::toString).orElse(UNCLASSIFIED_LITERAL));
        }
        return "ClassFilter" + labels + (includesDerived() ? "[+derived]" : "");
    }
}
