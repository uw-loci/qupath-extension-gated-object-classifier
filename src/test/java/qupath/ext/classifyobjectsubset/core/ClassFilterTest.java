package qupath.ext.classifyobjectsubset.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClassFilterTest {

    private static PathObject objWithClass(PathClass pc) {
        return PathObjects.createDetectionObject(ROIs.createRectangleROI(0, 0, 1, 1), pc);
    }

    @Test
    void emptyFilterAcceptsEverything() {
        ClassFilter filter = ClassFilter.acceptAll();
        assertThat(filter.isAcceptAll()).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("Tumor")))).isTrue();
        assertThat(filter.accepts(objWithClass(null))).isTrue();
    }

    @Test
    void unclassifiedSentinelMatchesNullClass() {
        ClassFilter filter = ClassFilter.of(Set.of(), true);
        assertThat(filter.includesUnclassified()).isTrue();
        assertThat(filter.accepts(objWithClass(null))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("Tumor")))).isFalse();
    }

    @Test
    void multiClassMembership() {
        PathClass tumor = PathClass.fromString("Tumor");
        PathClass stroma = PathClass.fromString("Stroma");
        PathClass other = PathClass.fromString("Other");

        Set<PathClass> classes = new LinkedHashSet<>();
        classes.add(tumor);
        classes.add(stroma);
        ClassFilter filter = ClassFilter.of(classes, false);

        assertThat(filter.accepts(objWithClass(tumor))).isTrue();
        assertThat(filter.accepts(objWithClass(stroma))).isTrue();
        assertThat(filter.accepts(objWithClass(other))).isFalse();
        assertThat(filter.accepts(objWithClass(null))).isFalse();
    }

    @Test
    void mixOfClassesAndUnclassified() {
        PathClass tumor = PathClass.fromString("Tumor");
        ClassFilter filter = ClassFilter.of(Set.of(tumor), true);

        assertThat(filter.accepts(objWithClass(tumor))).isTrue();
        assertThat(filter.accepts(objWithClass(null))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("Stroma")))).isFalse();
    }

    @Test
    void rejectsNullObject() {
        ClassFilter filter = ClassFilter.of(Set.of(PathClass.fromString("Tumor")), false);
        assertThat(filter.accepts(null)).isFalse();
    }

    // ---------------------------------------------------------------------
    // MatchMode.INCLUDE_DERIVED - issue #1, "apply to all cells of X* class"
    // ---------------------------------------------------------------------

    private static ClassFilter derived(PathClass... classes) {
        return ClassFilter.of(new LinkedHashSet<>(Set.of(classes)), false,
                ClassFilter.MatchMode.INCLUDE_DERIVED);
    }

    @Test
    void derivedMatchingAcceptsClassesBuiltOnTheCheckedOne() {
        PathClass tcell = PathClass.fromString("T cell");
        ClassFilter filter = derived(tcell);

        assertThat(filter.accepts(objWithClass(tcell))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        // Order of the components must not matter.
        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD8: T cell")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8: PD1")))).isTrue();

        assertThat(filter.accepts(objWithClass(PathClass.fromString("B cell")))).isFalse();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("B cell: CD20")))).isFalse();
        assertThat(filter.accepts(objWithClass(null))).isFalse();
    }

    @Test
    void derivedMatchingComparesWholeComponentNamesNotPrefixes() {
        // The reason this is set containment and not a "CD3*" glob: a prefix
        // match would silently swallow CD31, which is a different marker.
        ClassFilter filter = derived(PathClass.fromString("CD3"));

        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD3")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD3: CD8")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD31")))).isFalse();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD31: CD8")))).isFalse();
    }

    @Test
    void derivedMatchingOnACompositeAcceptsOnlySupersets() {
        ClassFilter filter = derived(PathClass.fromString("T cell: CD8"));

        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8: PD1")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("PD1: CD8: T cell")))).isTrue();
        // A single component is not a superset of the pair.
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell")))).isFalse();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("CD8")))).isFalse();
    }

    @Test
    void derivedMatchingAcceptsSeveralCheckedClassesIndependently() {
        ClassFilter filter = derived(PathClass.fromString("T cell"), PathClass.fromString("B cell"));

        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("B cell: CD20")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("Macrophage")))).isFalse();
    }

    @Test
    void derivedMatchingDoesNotLetTheUnclassifiedSentinelAcceptEverything() {
        // Every class trivially contains the empty component set, so if the
        // unclassified entry took part in containment this filter would accept
        // the whole image.
        ClassFilter filter = ClassFilter.of(Set.of(PathClass.fromString("T cell")), true,
                ClassFilter.MatchMode.INCLUDE_DERIVED);

        assertThat(filter.accepts(objWithClass(null))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("Macrophage")))).isFalse();
    }

    @Test
    void derivedMatchingWithOnlyUnclassifiedCheckedStaysExact() {
        ClassFilter filter = ClassFilter.of(Set.of(), true, ClassFilter.MatchMode.INCLUDE_DERIVED);

        assertThat(filter.accepts(objWithClass(null))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell")))).isFalse();
    }

    @Test
    void exactMatchingIsTheDefaultAndIgnoresDerivedClasses() {
        ClassFilter filter = ClassFilter.of(Set.of(PathClass.fromString("T cell")), false);

        assertThat(filter.includesDerived()).isFalse();
        assertThat(filter.matchMode()).isEqualTo(ClassFilter.MatchMode.EXACT);
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell")))).isTrue();
        assertThat(filter.accepts(objWithClass(PathClass.fromString("T cell: CD8")))).isFalse();
    }

    @Test
    void matchModeParticipatesInEquality() {
        Set<PathClass> classes = Set.of(PathClass.fromString("T cell"));
        ClassFilter exact = ClassFilter.of(classes, false, ClassFilter.MatchMode.EXACT);
        ClassFilter derivedFilter = ClassFilter.of(classes, false, ClassFilter.MatchMode.INCLUDE_DERIVED);

        assertThat(exact).isNotEqualTo(derivedFilter);
        assertThat(exact).isEqualTo(ClassFilter.of(classes, false, ClassFilter.MatchMode.EXACT));
    }

    @Test
    void repeatedAcceptsAgreeWithItself() {
        // The verdict is memoised per PathClass; a stale or wrong cache entry
        // would show up as the second call disagreeing with the first.
        ClassFilter filter = derived(PathClass.fromString("T cell"));
        PathObject hit = objWithClass(PathClass.fromString("T cell: CD8"));
        PathObject miss = objWithClass(PathClass.fromString("B cell"));

        for (int i = 0; i < 3; i++) {
            assertThat(filter.accepts(hit)).isTrue();
            assertThat(filter.accepts(miss)).isFalse();
        }
    }

    @Test
    void acceptedExposesUnmodifiableView() {
        ClassFilter filter = ClassFilter.of(Set.of(PathClass.fromString("Tumor")), true);
        Set<Optional<PathClass>> accepted = filter.accepted();
        assertThat(accepted).hasSize(2);
        assertThat(accepted).contains(Optional.empty());
    }
}
