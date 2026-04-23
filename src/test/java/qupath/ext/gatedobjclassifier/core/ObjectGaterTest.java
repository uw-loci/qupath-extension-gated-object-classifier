package qupath.ext.gatedobjclassifier.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectGaterTest {

    private static PathObject obj(PathClass pc, double area) {
        PathObject o = PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 1, 1), pc);
        o.getMeasurementList().put("area", area);
        return o;
    }

    private static List<PathObject> universe() {
        PathClass tumor = PathClass.fromString("Tumor");
        PathClass stroma = PathClass.fromString("Stroma");
        return List.of(
                obj(tumor, 10),
                obj(tumor, 20),
                obj(stroma, 30),
                obj(stroma, 40),
                obj(null, 50)
        );
    }

    @Test
    void allCompatibleReturnsEntireUniverse() {
        var u = universe();
        var crit = GatingCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(u.size());
    }

    @Test
    void selectedOnlyIntersectsWithUniverse() {
        var u = universe();
        var crit = GatingCriteria.builder().source(ObjectSourceMode.SELECTED_ONLY).build();

        // Pick two objects from the universe + one foreign object
        PathObject foreign = obj(PathClass.fromString("Other"), 99);
        List<PathObject> selection = List.of(u.get(0), u.get(2), foreign);

        var gated = ObjectGater.apply(u, selection, crit);
        assertThat(gated).containsExactly(u.get(0), u.get(2));
    }

    @Test
    void selectedOnlyEmptyWhenNoSelection() {
        var u = universe();
        var crit = GatingCriteria.builder().source(ObjectSourceMode.SELECTED_ONLY).build();
        assertThat(ObjectGater.apply(u, Collections.emptyList(), crit)).isEmpty();
    }

    @Test
    void customWithClassFilterOnly() {
        var u = universe();
        PathClass tumor = PathClass.fromString("Tumor");
        ClassFilter cf = ClassFilter.of(Set.of(tumor), false);
        var crit = GatingCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(2).allSatisfy(o -> assertThat(o.getPathClass()).isEqualTo(tumor));
    }

    @Test
    void customWithMeasurementFilterOnly() {
        var u = universe();
        MeasurementFilter mf = new MeasurementFilter("area", Comparator.GE, 30.0);
        var crit = GatingCriteria.builder().source(ObjectSourceMode.CUSTOM).measurementFilter(mf).build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(3); // 30, 40, 50
    }

    @Test
    void customAndCombinationOfFilters() {
        var u = universe();
        PathClass stroma = PathClass.fromString("Stroma");
        ClassFilter cf = ClassFilter.of(Set.of(stroma), false);
        MeasurementFilter mf = new MeasurementFilter("area", Comparator.GT, 30.0);
        var crit = GatingCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .classFilter(cf)
                .measurementFilter(mf)
                .build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(1);
        assertThat(gated.get(0).getPathClass()).isEqualTo(stroma);
        assertThat(gated.get(0).getMeasurementList().get("area")).isEqualTo(40.0);
    }

    @Test
    void customWithUnclassifiedSentinel() {
        var u = universe();
        ClassFilter cf = ClassFilter.of(Set.of(), true);
        var crit = GatingCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(1);
        assertThat(gated.get(0).getPathClass()).isNull();
    }

    @Test
    void emptyUniverseReturnsEmpty() {
        var crit = GatingCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
        assertThat(ObjectGater.apply(new ArrayList<>(), Collections.emptyList(), crit)).isEmpty();
    }

    @Test
    void filtersIgnoredWhenSourceIsNotCustom() {
        var u = universe();
        // Class filter set, but source is ALL_COMPATIBLE - filter should be ignored
        ClassFilter cf = ClassFilter.of(Set.of(PathClass.fromString("Tumor")), false);
        var crit = GatingCriteria.builder()
                .source(ObjectSourceMode.ALL_COMPATIBLE)
                .classFilter(cf)
                .build();
        var gated = ObjectGater.apply(u, Collections.emptyList(), crit);
        assertThat(gated).hasSize(u.size());
    }
}
