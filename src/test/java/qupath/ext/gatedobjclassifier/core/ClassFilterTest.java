package qupath.ext.gatedobjclassifier.core;

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

    @Test
    void acceptedExposesUnmodifiableView() {
        ClassFilter filter = ClassFilter.of(Set.of(PathClass.fromString("Tumor")), true);
        Set<Optional<PathClass>> accepted = filter.accepted();
        assertThat(accepted).hasSize(2);
        assertThat(accepted).contains(Optional.empty());
    }
}
