package qupath.ext.gatedobjclassifier.core;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Accept-set of {@link PathClass} values, where {@link Optional#empty()}
 * represents the "unclassified" sentinel (a {@code PathObject} whose
 * {@code getPathClass()} returns {@code null}).
 *
 * <p>An empty accept-set means "accept everything" so the filter is
 * effectively disabled.</p>
 *
 * <p>The literal string used in recorded workflow scripts to denote the
 * unclassified sentinel is {@value #UNCLASSIFIED_LITERAL}.</p>
 */
public final class ClassFilter {

    public static final String UNCLASSIFIED_LITERAL = "(unclassified)";

    private final Set<Optional<PathClass>> accepted;

    private ClassFilter(Set<Optional<PathClass>> accepted) {
        this.accepted = Collections.unmodifiableSet(new LinkedHashSet<>(accepted));
    }

    /** Filter that accepts every object regardless of class. */
    public static ClassFilter acceptAll() {
        return new ClassFilter(Collections.emptySet());
    }

    public static ClassFilter of(Set<Optional<PathClass>> accepted) {
        return new ClassFilter(accepted == null ? Collections.emptySet() : accepted);
    }

    /**
     * Build a filter from a mixed set of {@link PathClass} entries plus an
     * optional flag indicating whether unclassified objects should be accepted.
     */
    public static ClassFilter of(Set<PathClass> pathClasses, boolean includeUnclassified) {
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
        return new ClassFilter(set);
    }

    /** True when the filter accepts every object (no constraint set). */
    public boolean isAcceptAll() {
        return accepted.isEmpty();
    }

    public Set<Optional<PathClass>> accepted() {
        return accepted;
    }

    public boolean accepts(PathObject object) {
        if (isAcceptAll()) {
            return true;
        }
        if (object == null) {
            return false;
        }
        return accepted.contains(Optional.ofNullable(object.getPathClass()));
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
        return accepted.equals(other.accepted);
    }

    @Override
    public int hashCode() {
        return accepted.hashCode();
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
        return "ClassFilter" + labels;
    }
}
