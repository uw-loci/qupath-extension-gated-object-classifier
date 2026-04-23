package qupath.ext.gatedobjclassifier.core;

import qupath.lib.objects.PathObject;
import qupath.lib.measurements.MeasurementList;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Filter that accepts an object when its measurement value satisfies a
 * comparison.
 *
 * <p>Objects that do not have the named measurement (or whose value is NaN)
 * are rejected silently. Callers that care about how many objects were
 * dropped can compare {@link #countAccepted(Collection)} against the input
 * collection size.</p>
 */
public final class MeasurementFilter {

    private final String measurementName;
    private final Comparator op;
    private final double value1;
    private final double value2;

    public MeasurementFilter(String measurementName, Comparator op, double value1, double value2) {
        this.measurementName = Objects.requireNonNull(measurementName, "measurementName");
        this.op = Objects.requireNonNull(op, "op");
        this.value1 = value1;
        this.value2 = value2;
    }

    public MeasurementFilter(String measurementName, Comparator op, double value1) {
        this(measurementName, op, value1, Double.NaN);
    }

    public String measurementName() {
        return measurementName;
    }

    public Comparator op() {
        return op;
    }

    public double value1() {
        return value1;
    }

    public double value2() {
        return value2;
    }

    public boolean accepts(PathObject object) {
        if (object == null) {
            return false;
        }
        MeasurementList ml = object.getMeasurementList();
        if (ml == null) {
            return false;
        }
        double value = ml.get(measurementName);
        if (Double.isNaN(value)) {
            return false;
        }
        return op.test(value, value1, value2);
    }

    public int countAccepted(Collection<? extends PathObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (PathObject o : objects) {
            if (accepts(o)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Discover the union of measurement names across the provided objects, in
     * sorted order. Useful for populating a measurement-name combo box.
     */
    public static Set<String> discoverMeasurementNames(Collection<? extends PathObject> objects) {
        Set<String> names = new TreeSet<>();
        if (objects == null) {
            return new LinkedHashSet<>();
        }
        for (PathObject o : objects) {
            MeasurementList ml = o.getMeasurementList();
            if (ml == null) {
                continue;
            }
            names.addAll(ml.getNames());
        }
        return new LinkedHashSet<>(names);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeasurementFilter)) return false;
        MeasurementFilter other = (MeasurementFilter) o;
        return Double.compare(value1, other.value1) == 0
                && Double.compare(value2, other.value2) == 0
                && measurementName.equals(other.measurementName)
                && op == other.op;
    }

    @Override
    public int hashCode() {
        return Objects.hash(measurementName, op, value1, value2);
    }

    @Override
    public String toString() {
        if (op.usesSecondValue()) {
            return "MeasurementFilter['" + measurementName + "' " + op.symbol() + " ["
                    + value1 + ", " + value2 + "]]";
        }
        return "MeasurementFilter['" + measurementName + "' " + op.symbol() + " " + value1 + "]";
    }
}
