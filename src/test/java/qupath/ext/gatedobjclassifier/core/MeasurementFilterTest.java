package qupath.ext.gatedobjclassifier.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MeasurementFilterTest {

    private static PathObject objWithMeasurement(String name, double value) {
        PathObject obj = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 1, 1));
        obj.getMeasurementList().put(name, value);
        return obj;
    }

    @Test
    void greaterThan() {
        MeasurementFilter filter = new MeasurementFilter("area", Comparator.GT, 10.0);
        assertThat(filter.accepts(objWithMeasurement("area", 11.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("area", 10.0))).isFalse();
        assertThat(filter.accepts(objWithMeasurement("area", 9.0))).isFalse();
    }

    @Test
    void greaterOrEqual() {
        MeasurementFilter filter = new MeasurementFilter("area", Comparator.GE, 10.0);
        assertThat(filter.accepts(objWithMeasurement("area", 10.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("area", 9.999))).isFalse();
    }

    @Test
    void betweenInclusive() {
        MeasurementFilter filter = new MeasurementFilter("intensity", Comparator.BETWEEN, 5.0, 10.0);
        assertThat(filter.accepts(objWithMeasurement("intensity", 5.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("intensity", 7.5))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("intensity", 10.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("intensity", 4.99))).isFalse();
        assertThat(filter.accepts(objWithMeasurement("intensity", 10.01))).isFalse();
    }

    @Test
    void betweenAcceptsOutOfOrderBounds() {
        MeasurementFilter filter = new MeasurementFilter("x", Comparator.BETWEEN, 10.0, 5.0);
        assertThat(filter.accepts(objWithMeasurement("x", 7.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("x", 11.0))).isFalse();
    }

    @Test
    void missingMeasurementRejected() {
        MeasurementFilter filter = new MeasurementFilter("missing", Comparator.GT, 0.0);
        PathObject obj = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 1, 1));
        // No measurement added at all
        assertThat(filter.accepts(obj)).isFalse();
    }

    @Test
    void nanRejected() {
        MeasurementFilter filter = new MeasurementFilter("x", Comparator.GT, 0.0);
        assertThat(filter.accepts(objWithMeasurement("x", Double.NaN))).isFalse();
    }

    @Test
    void countAccepted() {
        MeasurementFilter filter = new MeasurementFilter("v", Comparator.GE, 5.0);
        List<PathObject> objects = List.of(
                objWithMeasurement("v", 1.0),
                objWithMeasurement("v", 5.0),
                objWithMeasurement("v", 7.0),
                objWithMeasurement("v", 10.0)
        );
        assertThat(filter.countAccepted(objects)).isEqualTo(3);
    }

    @Test
    void discoverMeasurementNames() {
        var objects = List.of(
                objWithMeasurement("alpha", 1),
                objWithMeasurement("beta", 2)
        );
        // Add second measurement to the first object
        objects.get(0).getMeasurementList().put("gamma", 3);

        var names = MeasurementFilter.discoverMeasurementNames(objects);
        assertThat(names).containsExactlyInAnyOrder("alpha", "beta", "gamma");
    }

    @Test
    void notEqualSemantics() {
        MeasurementFilter filter = new MeasurementFilter("v", Comparator.NE, 0.0);
        assertThat(filter.accepts(objWithMeasurement("v", 0.0))).isFalse();
        assertThat(filter.accepts(objWithMeasurement("v", 1.0))).isTrue();
        assertThat(filter.accepts(objWithMeasurement("v", -1.0))).isTrue();
    }
}
