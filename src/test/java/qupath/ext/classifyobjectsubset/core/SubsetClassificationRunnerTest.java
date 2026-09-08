package qupath.ext.classifyobjectsubset.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the "N objects classified, M changed" numbers.
 *
 * <p>{@code ObjectClassifier.classifyObjects} is documented as returning the
 * number of objects whose classification changed, but the implementations
 * disagree: {@code SimpleClassifier} and {@code CompositeClassifier} do count
 * changes, while {@code OpenCVMLClassifier} -- the kind "Train object
 * classifier" produces, and so the kind most users run -- ends its loop with
 * {@code counter += tempObjectList.size()} and returns objects PROCESSED.
 * Because the value means different things depending on the classifier, the
 * runner counts the change itself rather than trusting it. The fake classifier
 * below reproduces the OpenCVMLClassifier behaviour, which is the case that
 * made the two numbers identical on every run (issue #3).</p>
 */
class SubsetClassificationRunnerTest {

    private static final PathClass TUMOR = PathClass.fromString("Tumor");
    private static final PathClass STROMA = PathClass.fromString("Stroma");

    private ImageData<BufferedImage> imageData;
    private List<PathObject> objects;

    /** Applies {@code assign} to each object, then reports objects PROCESSED. */
    private static final class FakeClassifier implements ObjectClassifier<BufferedImage> {

        private final Function<PathObject, PathClass> assign;
        private final int processedOverride;

        FakeClassifier(Function<PathObject, PathClass> assign) {
            this(assign, -1);
        }

        FakeClassifier(Function<PathObject, PathClass> assign, int processedOverride) {
            this.assign = assign;
            this.processedOverride = processedOverride;
        }

        @Override
        public Collection<PathClass> getPathClasses() {
            return List.of(TUMOR, STROMA);
        }

        @Override
        public int classifyObjects(ImageData<BufferedImage> imageData, boolean resetExistingClass) {
            return classifyObjects(imageData, getCompatibleObjects(imageData), resetExistingClass);
        }

        @Override
        public int classifyObjects(ImageData<BufferedImage> imageData,
                                   Collection<? extends PathObject> pathObjects,
                                   boolean resetExistingClass) {
            if (processedOverride >= 0) {
                return processedOverride;
            }
            for (PathObject o : pathObjects) {
                PathClass pc = assign.apply(o);
                if (pc == null) {
                    o.resetPathClass();
                } else {
                    o.setPathClass(pc);
                }
            }
            // Upstream behaviour: the count of objects seen, NOT of changes.
            return pathObjects.size();
        }

        @Override
        public Collection<PathObject> getCompatibleObjects(ImageData<BufferedImage> imageData) {
            return new ArrayList<>(imageData.getHierarchy().getDetectionObjects());
        }

        @Override
        public Map<String, Integer> getMissingFeatures(ImageData<BufferedImage> imageData,
                                                       Collection<? extends PathObject> pathObjects) {
            return Collections.emptyMap();
        }
    }

    @BeforeEach
    void setUp() {
        PathObjectHierarchy hierarchy = new PathObjectHierarchy();
        objects = new ArrayList<>();
        // Six detections: three already Tumor, three already Stroma.
        for (int i = 0; i < 6; i++) {
            PathObject o = PathObjects.createDetectionObject(
                    ROIs.createRectangleROI(i, 0, 1, 1), i < 3 ? TUMOR : STROMA);
            objects.add(o);
        }
        hierarchy.addObjects(objects);
        // ImageData requires a real server; a 1x1 wrapped BufferedImage is the
        // cheapest one that satisfies it. Nothing here reads pixels.
        var server = new WrappedBufferedImageServer(
                "test", new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        imageData = new ImageData<>(server, hierarchy, null);
    }

    private SubsetCriteria allCompatible() {
        return SubsetCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
    }

    @Test
    void changedCountsOnlyObjectsWhoseClassActuallyChanged() {
        // Everything becomes Tumor: the three Stroma objects change, the three
        // that were already Tumor do not.
        var result = SubsetClassificationRunner.run(
                imageData, new FakeClassifier(o -> TUMOR), "fake",
                Collections.emptyList(), allCompatible(), false);

        assertThat(result.nSelected).isEqualTo(6);
        assertThat(result.nChanged).isEqualTo(3);
    }

    @Test
    void changedIsZeroWhenTheClassifierAgreesWithEveryExistingClass() {
        var result = SubsetClassificationRunner.run(
                imageData, new FakeClassifier(PathObject::getPathClass), "fake",
                Collections.emptyList(), allCompatible(), false);

        assertThat(result.nSelected).isEqualTo(6);
        assertThat(result.nChanged).isZero();
        // Still a successful run - it classified 6 objects, they just agreed.
        assertThat(result.ranSuccessfully()).isTrue();
    }

    @Test
    void changedEqualsSelectedOnlyWhenEveryObjectReallyChanged() {
        var result = SubsetClassificationRunner.run(
                imageData,
                new FakeClassifier(o -> TUMOR.equals(o.getPathClass()) ? STROMA : TUMOR),
                "fake", Collections.emptyList(), allCompatible(), false);

        assertThat(result.nSelected).isEqualTo(6);
        assertThat(result.nChanged).isEqualTo(6);
    }

    @Test
    void clearingAClassCountsAsAChange() {
        var result = SubsetClassificationRunner.run(
                imageData, new FakeClassifier(o -> null), "fake",
                Collections.emptyList(), allCompatible(), false);

        assertThat(result.nChanged).isEqualTo(6);
        assertThat(objects.get(0).getPathClass()).isNull();
    }

    @Test
    void aClassifierThatProcessesNothingIsReportedAsAFailureNotAQuietNoOp() {
        // Zero returned with a non-empty subset is how the classifier signals it
        // bailed out (interrupted, or no feature extractor).
        var result = SubsetClassificationRunner.run(
                imageData, new FakeClassifier(o -> TUMOR, 0), "fake",
                Collections.emptyList(), allCompatible(), false);

        assertThat(result.nChanged).isZero();
        assertThat(result.warning).contains("did not process any objects");
    }
}
