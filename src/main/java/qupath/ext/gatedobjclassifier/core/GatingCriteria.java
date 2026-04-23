package qupath.ext.gatedobjclassifier.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of which objects should be classified and how.
 *
 * <p>Combines an {@link ObjectSourceMode} with optional class and measurement
 * filters, plus a {@code preserveExistingClass} flag that maps to the
 * {@code resetExistingClass} parameter on
 * {@link qupath.lib.classifiers.object.ObjectClassifier#classifyObjects}.</p>
 */
public final class GatingCriteria {

    private final ObjectSourceMode source;
    private final ClassFilter classFilter;
    private final MeasurementFilter measurementFilter;
    private final boolean preserveExistingClass;

    private GatingCriteria(Builder b) {
        this.source = Objects.requireNonNull(b.source, "source");
        this.classFilter = b.classFilter;
        this.measurementFilter = b.measurementFilter;
        this.preserveExistingClass = b.preserveExistingClass;
    }

    public ObjectSourceMode source() {
        return source;
    }

    public Optional<ClassFilter> classFilter() {
        return Optional.ofNullable(classFilter);
    }

    public Optional<MeasurementFilter> measurementFilter() {
        return Optional.ofNullable(measurementFilter);
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
        private MeasurementFilter measurementFilter;
        private boolean preserveExistingClass = false;

        public Builder source(ObjectSourceMode source) {
            this.source = source;
            return this;
        }

        public Builder classFilter(ClassFilter classFilter) {
            this.classFilter = (classFilter != null && classFilter.isAcceptAll()) ? null : classFilter;
            return this;
        }

        public Builder measurementFilter(MeasurementFilter measurementFilter) {
            this.measurementFilter = measurementFilter;
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
                && Objects.equals(measurementFilter, other.measurementFilter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, classFilter, measurementFilter, preserveExistingClass);
    }

    @Override
    public String toString() {
        return "GatingCriteria[source=" + source
                + ", classFilter=" + classFilter
                + ", measurementFilter=" + measurementFilter
                + ", preserveExistingClass=" + preserveExistingClass + "]";
    }
}
