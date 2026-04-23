package qupath.ext.gatedobjclassifier.core;

/**
 * The strategy used to choose which objects the classifier will be applied to.
 */
public enum ObjectSourceMode {

    /** All objects the classifier reports as compatible with the current image. */
    ALL_COMPATIBLE("All compatible objects"),

    /** The objects currently selected in the QuPath hierarchy. */
    SELECTED_ONLY("Selected objects only"),

    /** All compatible objects, narrowed by class and/or measurement filters. */
    CUSTOM("Custom filter");

    private final String displayName;

    ObjectSourceMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
