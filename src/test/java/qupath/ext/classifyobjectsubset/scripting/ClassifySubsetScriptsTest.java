package qupath.ext.classifyobjectsubset.scripting;

import org.junit.jupiter.api.Test;
import qupath.ext.classifyobjectsubset.core.ClassFilter;
import qupath.ext.classifyobjectsubset.core.Comparator;
import qupath.ext.classifyobjectsubset.core.ObjectSourceMode;
import qupath.ext.classifyobjectsubset.core.SubsetCriteria;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the options-map contract between a recorded workflow script and
 * {@link ClassifySubsetScripts#parseCriteria(Map)}. The maps here are shaped
 * exactly as {@code WorkflowScriptBuilder} renders them, so a key that stops
 * round-tripping fails here rather than silently changing what a saved script
 * classifies.
 */
class ClassifySubsetScriptsTest {

    private static PathObject objWithClass(PathClass pc) {
        return PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 1, 1), pc);
    }

    private static Map<String, Object> opts(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void classesRoundTripAsExactMatchesByDefault() {
        SubsetCriteria criteria = ClassifySubsetScripts.parseCriteria(opts(
                "source", "CUSTOM",
                "classes", List.of(List.of("T cell"))));

        assertThat(criteria.source()).isEqualTo(ObjectSourceMode.CUSTOM);
        ClassFilter cf = criteria.classFilter().orElseThrow();
        assertThat(cf.includesDerived()).isFalse();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("T cell")))).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isFalse();
    }

    @Test
    void includeDerivedRoundTrips() {
        SubsetCriteria criteria = ClassifySubsetScripts.parseCriteria(opts(
                "source", "CUSTOM",
                "classes", List.of(List.of("T cell")),
                "includeDerived", Boolean.TRUE));

        ClassFilter cf = criteria.classFilter().orElseThrow();
        assertThat(cf.includesDerived()).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("CD8: T cell")))).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("B cell")))).isFalse();
    }

    @Test
    void includeDerivedAcceptsAHandEditedStringValue() {
        SubsetCriteria criteria = ClassifySubsetScripts.parseCriteria(opts(
                "source", "CUSTOM",
                "classes", List.of(List.of("T cell")),
                "includeDerived", "true"));

        assertThat(criteria.classFilter().orElseThrow().includesDerived()).isTrue();
    }

    @Test
    void includeDerivedDoesNotSwallowEverythingWhenCombinedWithUnclassified() {
        SubsetCriteria criteria = ClassifySubsetScripts.parseCriteria(opts(
                "source", "CUSTOM",
                "classes", List.of(List.of("T cell"), ClassFilter.UNCLASSIFIED_LITERAL),
                "includeDerived", Boolean.TRUE));

        ClassFilter cf = criteria.classFilter().orElseThrow();
        assertThat(cf.includesUnclassified()).isTrue();
        assertThat(cf.accepts(objWithClass(null))).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        assertThat(cf.accepts(objWithClass(PathClass.fromString("Macrophage")))).isFalse();
    }

    @Test
    void preserveClassAcceptsBooleanAndString() {
        assertThat(ClassifySubsetScripts.parseCriteria(opts("preserveClass", Boolean.TRUE))
                .preserveExistingClass()).isTrue();
        assertThat(ClassifySubsetScripts.parseCriteria(opts("preserveClass", "true"))
                .preserveExistingClass()).isTrue();
        assertThat(ClassifySubsetScripts.parseCriteria(opts())
                .preserveExistingClass()).isFalse();
    }

    @Test
    void flatAndListedMeasurementsAreAndCombined() {
        SubsetCriteria criteria = ClassifySubsetScripts.parseCriteria(opts(
                "source", "CUSTOM",
                "measurement", "DAB: Cell: Mean",
                "op", "GT",
                "value1", 0.2,
                "measurements", List.of(Map.of(
                        "measurement", "Cell: Area", "op", "LT", "value1", 200.0))));

        assertThat(criteria.measurementFilters()).hasSize(2);
        assertThat(criteria.measurementFilters().get(0).measurementName()).isEqualTo("DAB: Cell: Mean");
        assertThat(criteria.measurementFilters().get(0).op()).isEqualTo(Comparator.GT);
        assertThat(criteria.measurementFilters().get(1).measurementName()).isEqualTo("Cell: Area");
    }
}
