package qupath.ext.gatedobjclassifier.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of which objects should be classified and how.
 *
 * <p>Combines an {@link ObjectSourceMode} with an optional class filter and
 * zero or more measurement filters (AND-combined), plus a
 * {@code preserveExistingClass} flag that maps to the
 * {@code resetExistingClass} parameter on
 * {@link qupath.lib.classifiers.object.ObjectClassifier#classifyObjects}.</p>
 *
 * <p>Multiple measurement filters let the user gate on several thresholds at
 * once (e.g. "DAB mean &gt; 0.2 AND cell area &lt; 200"); an object must pass
 * every filter to be included.</p>
 */
public final class GatingCriteria {

    private final ObjectSourceMode source;
    private final ClassFilter classFilter;
    private final List<MeasurementFilter> measurementFilters;
    private final boolean preserveExistingClass;

    private GatingCriteria(Builder b) {
        this.source = Objects.requireNonNull(b.source, "source");
        this.classFilter = b.classFilter;
        this.measurementFilters = List.copyOf(b.measurementFilters);
        this.preserveExistingClass = b.preserveExistingClass;
    }

    public ObjectSourceMode source() {
        return source;
    }

    public Optional<ClassFilter> classFilter() {
        return Optional.ofNullable(classFilter);
    }

    /**
     * The measurement filters to AND-combine, in the order they were added.
     * Empty when no measurement gating is requested.
     */
    public List<MeasurementFilter> measurementFilters() {
        return measurementFilters;
    }

    public boolean preserveExistingClass() {
        return preserveExistingClass;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ObjectSourceMode source = ObjectSourceMode.ALL_COMPATIBLE;
        private ClassFilter classFilter;
        private final List<MeasurementFilter> measurementFilters = new ArrayList<>();
        private boolean preserveExistingClass = false;

        public Builder source(ObjectSourceMode source) {
            this.source = source;
            return this;
        }

        public Builder classFilter(ClassFilter classFilter) {
            this.classFilter = (classFilter != null && classFilter.isAcceptAll()) ? null : classFilter;
            return this;
        }

        /** Append a single measurement filter; {@code null} is ignored. */
        public Builder measurementFilter(MeasurementFilter measurementFilter) {
            if (measurementFilter != null) {
                this.measurementFilters.add(measurementFilter);
            }
            return this;
        }

        /** Replace the measurement filters with the given list (nulls dropped). */
        public Builder measurementFilters(List<MeasurementFilter> filters) {
            this.measurementFilters.clear();
            if (filters != null) {
                for (MeasurementFilter mf : filters) {
                    if (mf != null) {
                        this.measurementFilters.add(mf);
                    }
                }
            }
            return this;
        }

        public Builder preserveExistingClass(boolean value) {
            this.preserveExistingClass = value;
            return this;
        }

        public GatingCriteria build() {
            return new GatingCriteria(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GatingCriteria)) return false;
        GatingCriteria other = (GatingCriteria) o;
        return preserveExistingClass == other.preserveExistingClass
                && source == other.source
                && Objects.equals(classFilter, other.classFilter)
                && measurementFilters.equals(other.measurementFilters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, classFilter, measurementFilters, preserveExistingClass);
    }

    @Override
    public String toString() {
        return "GatingCriteria[source=" + source
                + ", classFilter=" + classFilter
                + ", measurementFilters=" + measurementFilters
                + ", preserveExistingClass=" + preserveExistingClass + "]";
    }
}
