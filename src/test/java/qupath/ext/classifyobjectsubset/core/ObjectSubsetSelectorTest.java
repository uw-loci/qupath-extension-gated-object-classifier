package qupath.ext.classifyobjectsubset.core;

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

class ObjectSubsetSelectorTest {

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
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(u.size());
    }

    @Test
    void selectedOnlyIntersectsWithUniverse() {
        var u = universe();
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.SELECTED_ONLY).build();

        // Pick two objects from the universe + one foreign object
        PathObject foreign = obj(PathClass.fromString("Other"), 99);
        List<PathObject> selection = List.of(u.get(0), u.get(2), foreign);

        var subset = ObjectSubsetSelector.apply(u, selection, crit);
        assertThat(subset).containsExactly(u.get(0), u.get(2));
    }

    @Test
    void selectedOnlyEmptyWhenNoSelection() {
        var u = universe();
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.SELECTED_ONLY).build();
        assertThat(ObjectSubsetSelector.apply(u, Collections.emptyList(), crit)).isEmpty();
    }

    /**
     * A gated multiplex hierarchy: one bare marker class plus several classes
     * built on top of it, alongside an unrelated marker whose name shares a
     * prefix with it.
     */
    private static List<PathObject> multiplexUniverse() {
        return List.of(
                obj(PathClass.fromString("CD3"), 10),
                obj(PathClass.fromString("CD3: CD8"), 20),
                obj(PathClass.fromString("CD8: CD3"), 30),
                obj(PathClass.fromString("CD3: CD8: PD1"), 40),
                obj(PathClass.fromString("CD31"), 50),
                obj(PathClass.fromString("CD31: CD8"), 60),
                obj(PathClass.fromString("CD20"), 70),
                obj(null, 80)
        );
    }

    @Test
    void customWithDerivedClassFilterReachesEveryClassBuiltOnTheCheckedOne() {
        var u = multiplexUniverse();
        ClassFilter cf = ClassFilter.of(Set.of(PathClass.fromString("CD3")), false,
                ClassFilter.MatchMode.INCLUDE_DERIVED);
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();

        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);

        // CD3 and everything derived from it, in universe order; never CD31.
        assertThat(subset).containsExactly(u.get(0), u.get(1), u.get(2), u.get(3));
    }

    @Test
    void customWithDerivedClassFilterCombinesWithAMeasurementThreshold() {
        var u = multiplexUniverse();
        ClassFilter cf = ClassFilter.of(Set.of(PathClass.fromString("CD3")), false,
                ClassFilter.MatchMode.INCLUDE_DERIVED);
        var crit = SubsetCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .classFilter(cf)
                .measurementFilter(new MeasurementFilter("area", Comparator.GT, 25))
                .build();

        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);

        assertThat(subset).containsExactly(u.get(2), u.get(3));
    }

    @Test
    void exactClassFilterOnTheSameUniverseTakesOnlyTheBareClass() {
        var u = multiplexUniverse();
        ClassFilter cf = ClassFilter.of(Set.of(PathClass.fromString("CD3")), false);
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();

        assertThat(ObjectSubsetSelector.apply(u, Collections.emptyList(), crit))
                .containsExactly(u.get(0));
    }

    @Test
    void customWithClassFilterOnly() {
        var u = universe();
        PathClass tumor = PathClass.fromString("Tumor");
        ClassFilter cf = ClassFilter.of(Set.of(tumor), false);
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(2).allSatisfy(o -> assertThat(o.getPathClass()).isEqualTo(tumor));
    }

    @Test
    void customWithMeasurementFilterOnly() {
        var u = universe();
        MeasurementFilter mf = new MeasurementFilter("area", Comparator.GE, 30.0);
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.CUSTOM).measurementFilter(mf).build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(3); // 30, 40, 50
    }

    @Test
    void customAndCombinationOfFilters() {
        var u = universe();
        PathClass stroma = PathClass.fromString("Stroma");
        ClassFilter cf = ClassFilter.of(Set.of(stroma), false);
        MeasurementFilter mf = new MeasurementFilter("area", Comparator.GT, 30.0);
        var crit = SubsetCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .classFilter(cf)
                .measurementFilter(mf)
                .build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(1);
        assertThat(subset.get(0).getPathClass()).isEqualTo(stroma);
        assertThat(subset.get(0).getMeasurementList().get("area")).isEqualTo(40.0);
    }

    @Test
    void customWithMultipleMeasurementFiltersAreAndCombined() {
        // area > 20 AND area < 50 -> keeps 30 and 40 (drops 10, 20, 50)
        var u = universe();
        var crit = SubsetCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .measurementFilter(new MeasurementFilter("area", Comparator.GT, 20.0))
                .measurementFilter(new MeasurementFilter("area", Comparator.LT, 50.0))
                .build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(2);
        assertThat(subset).allSatisfy(o -> {
            double a = o.getMeasurementList().get("area");
            assertThat(a).isGreaterThan(20.0).isLessThan(50.0);
        });
    }

    @Test
    void customWithContradictoryMeasurementFiltersMatchNothing() {
        var u = universe();
        var crit = SubsetCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .measurementFilter(new MeasurementFilter("area", Comparator.GT, 40.0))
                .measurementFilter(new MeasurementFilter("area", Comparator.LT, 20.0))
                .build();
        assertThat(ObjectSubsetSelector.apply(u, Collections.emptyList(), crit)).isEmpty();
    }

    @Test
    void customWithUnclassifiedSentinel() {
        var u = universe();
        ClassFilter cf = ClassFilter.of(Set.of(), true);
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.CUSTOM).classFilter(cf).build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(1);
        assertThat(subset.get(0).getPathClass()).isNull();
    }

    @Test
    void emptyUniverseReturnsEmpty() {
        var crit = SubsetCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
        assertThat(ObjectSubsetSelector.apply(new ArrayList<>(), Collections.emptyList(), crit)).isEmpty();
    }

    @Test
    void filtersIgnoredWhenSourceIsNotCustom() {
        var u = universe();
        // Class filter set, but source is ALL_COMPATIBLE - filter should be ignored
        ClassFilter cf = ClassFilter.of(Set.of(PathClass.fromString("Tumor")), false);
        var crit = SubsetCriteria.builder()
                .source(ObjectSourceMode.ALL_COMPATIBLE)
                .classFilter(cf)
                .build();
        var subset = ObjectSubsetSelector.apply(u, Collections.emptyList(), crit);
        assertThat(subset).hasSize(u.size());
    }
}
