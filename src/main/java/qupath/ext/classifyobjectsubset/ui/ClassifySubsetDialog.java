package qupath.ext.classifyobjectsubset.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.util.converter.DoubleStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.classifyobjectsubset.core.ClassFilter;
import qupath.ext.classifyobjectsubset.core.ClassifierLoader;
import qupath.ext.classifyobjectsubset.core.Comparator;
import qupath.ext.classifyobjectsubset.core.SubsetClassificationRunner;
import qupath.ext.classifyobjectsubset.core.SubsetCriteria;
import qupath.ext.classifyobjectsubset.core.MeasurementFilter;
import qupath.ext.classifyobjectsubset.core.ObjectSubsetSelector;
import qupath.ext.classifyobjectsubset.core.ObjectSourceMode;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.projects.Project;
import javafx.beans.value.ChangeListener;

import java.awt.image.BufferedImage;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Modeless dialog for the Classify Object Subset extension.
 *
 * <p>Lets the user pick a project classifier and a subset strategy
 * (all compatible / current selection / class+measurement custom filter),
 * then applies the classifier to the object subset and records the operation
 * as a workflow step.</p>
 *
 * <p>Built as a single class (rather than several small {@code Pane} classes)
 * because the controls share a lot of cross-cutting state - source mode
 * enables/disables filters, every change recomputes the preview - and the
 * extra plumbing for property bridges would not pay for itself.</p>
 */
public final class ClassifySubsetDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClassifySubsetDialog.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.classifyobjectsubset.ui.strings");

    /**
     * Debounce window for the preview recount. Long enough to swallow a burst
     * of checkbox clicks or keystrokes, short enough to feel immediate.
     */
    private static final long PREVIEW_DEBOUNCE_MS = 150;

    private static final String DOC_URL =
            "https://github.com/MichaelSNelson/qupath-extension-classify-object-subset#readme";

    private final QuPathGUI qupath;
    private final ImageData<BufferedImage> imageData;
    private final Stage stage;
    private PathObjectSelectionListener hierarchySelectionListener;
    private PathObjectHierarchyListener hierarchyChangeListener;
    private ChangeListener<ImageData<BufferedImage>> imageSwitchListener;

    // --- Classifier section
    private final ComboBox<String> classifierCombo = new ComboBox<>();
    private final Label classifierClassesLabel = new Label();
    private ObjectClassifier<BufferedImage> currentClassifier;
    private List<PathObject> universeCache = Collections.emptyList();
    private List<PathClass> universeClassesCache = Collections.emptyList();
    private List<String> universeMeasurementsCache = Collections.emptyList();

    // --- Source section
    private final ToggleGroup sourceGroup = new ToggleGroup();
    private final RadioButton sourceAll = new RadioButton(resources.getString("label.source.all"));
    private final RadioButton sourceSelected = new RadioButton(resources.getString("label.source.selected"));
    private final RadioButton sourceCustom = new RadioButton(resources.getString("label.source.custom"));
    private final Label selectedCountLabel = new Label();

    // --- Filter section
    private final TitledPane filtersPane = new TitledPane();
    // Class filter: a checkbox list so the user can tick classes directly
    // instead of ctrl-clicking a multi-select list.
    private final ListView<ClassEntry> classListView = new ListView<>();
    private final CheckBox includeUnclassifiedCheck = new CheckBox(resources.getString("label.filter.class.includeUnclassified"));
    private final CheckBox includeDerivedCheck = new CheckBox(resources.getString("label.filter.class.includeDerived"));
    private final TextField classFindField = new TextField();
    private final Label classCheckedLabel = new Label();
    /**
     * Master list of class rows. {@link #classListView} shows a
     * {@link FilteredList} view of it, so the Find box narrows what is visible
     * without disturbing which rows are checked.
     */
    private final ObservableList<ClassEntry> allClassEntries = FXCollections.observableArrayList();
    private final FilteredList<ClassEntry> shownClassEntries = new FilteredList<>(allClassEntries, e -> true);
    // Classes whose checkbox is ticked. Tracked separately from the ListView
    // items so the ticks survive a repopulation (classifier / hierarchy change).
    private final Set<PathClass> checkedClasses = new LinkedHashSet<>();
    // Measurement thresholds: a dynamic list of rows, AND-combined. "Add
    // threshold" appends a row; each row carries its own remove button.
    private final VBox measurementRowsBox = new VBox(6);
    private final Label measurementEmptyLabel = new Label(resources.getString("label.filter.measurement.empty"));
    private final Button addThresholdButton = new Button(resources.getString("label.filter.measurement.add"));
    private final List<MeasurementRow> measurementRows = new ArrayList<>();

    // --- Options
    private final CheckBox preserveClassCheck = new CheckBox(resources.getString("label.options.preserveClass"));

    // --- Preview
    private final Label previewLabel = new Label();
    private final Label warningLabel = new Label();
    private final Button showSelectionButton = new Button(resources.getString("label.preview.show"));

    // --- Actions
    private final Button applyButton = new Button(resources.getString("button.apply"));
    private final Button closeButton = new Button(resources.getString("button.close"));

    // Preview scheduling. Every control change asks for a recount; on a
    // hierarchy of hundreds of thousands of objects that pass is far too slow
    // to run inline on the FX thread for each click, so requests are coalesced
    // by a short debounce and the count itself runs on a background thread.
    private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(PREVIEW_DEBOUNCE_MS));
    private final AtomicLong previewGeneration = new AtomicLong();
    private ExecutorService previewExecutor;
    /** Set while a bulk check/uncheck runs, so one recount follows the batch. */
    private boolean suppressPreview;

    private ClassifySubsetDialog(QuPathGUI qupath, ImageData<BufferedImage> imageData) {
        this.qupath = qupath;
        this.imageData = imageData;
        this.stage = new Stage();
        configureStage();
    }

    /**
     * Opens the dialog. Validates that an image is open before showing.
     * Safe to call from any thread; the dialog is shown on the JavaFX
     * application thread.
     */
    public static void showDialog(QuPathGUI qupath) {
        if (qupath == null) {
            return;
        }
        Runnable openTask = () -> {
            ImageData<BufferedImage> imageData = qupath.getImageData();
            if (imageData == null) {
                Dialogs.showWarningNotification(resources.getString("dialog.title"),
                        resources.getString("warning.noImage"));
                return;
            }
            new ClassifySubsetDialog(qupath, imageData).stage.show();
        };
        if (Platform.isFxApplicationThread()) {
            openTask.run();
        } else {
            Platform.runLater(openTask);
        }
    }

    private void configureStage() {
        stage.setTitle(resources.getString("dialog.title"));
        stage.initOwner(qupath.getStage());
        stage.initModality(Modality.NONE);

        BorderPane root = new BorderPane();
        // Pick up the active theme's background colour (light or dark) so the
        // BorderPane, ScrollPane viewport, and button bar don't show modena's
        // white default behind the themed TitledPanes.
        root.setStyle("-fx-background-color: -fx-base;");

        VBox center = new VBox(10);
        center.setPadding(new Insets(12));
        // VBox must be transparent so the themed -fx-base shows through any
        // empty space when the dialog is taller than its content.
        center.setStyle("-fx-background-color: transparent;");
        center.getChildren().addAll(
                buildHeader(),
                new Separator(),
                buildClassifierSection(),
                buildSourceSection(),
                buildFiltersSection(),
                buildOptionsSection(),
                buildPreviewSection()
        );

        // Wrap the content in a ScrollPane so (a) the dialog can shrink without
        // clipping controls and (b) if the user grows or shrinks the window,
        // a vertical scrollbar appears when the content doesn't fit instead of
        // silently hiding buttons. Horizontal width always fits.
        ScrollPane scroll = new ScrollPane(center);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // The ScrollPane's .viewport and .corner sub-regions are painted white
        // by modena.css - styling them requires an actual CSS selector (inline
        // setStyle on the ScrollPane doesn't cascade into the viewport). The
        // .subset-scroll class is targeted by a tiny stylesheet attached to the
        // scene below so the viewport and corner become transparent and the
        // themed -fx-base from the BorderPane shows through everywhere.
        scroll.getStyleClass().add("subset-scroll");
        root.setCenter(scroll);

        // Modena's .scroll-pane > .viewport rule is applied lazily - the
        // first paint shows the default white viewport, and our data-URL
        // stylesheet only takes visible effect after the first layout pass
        // (e.g. when the user scrolls or resizes). Force one redundant CSS +
        // layout pass after the stage is shown so the transparent viewport
        // appears from the very first frame.
        stage.setOnShown(shownEvt -> Platform.runLater(() -> {
            // Lay out the ROOT, not just the ScrollPane. On Windows with display
            // scaling the first layout pass can leave the root at its pre-CSS
            // preferred size while the stage is larger, and everything the root
            // does not cover is painted by the Scene - whose default fill is
            // white, so it reads as a bright L down the right and along the
            // bottom of a dark-themed dialog (issue #2). Resizing the window by
            // any amount fixed it, which is the tell that the geometry was fine
            // and only the layout pass was missing.
            root.applyCss();
            root.layout();
            // Belt and braces: repaint any area the root still does not cover in
            // the theme's own colour rather than white.
            applyThemedSceneFill(stage.getScene(), root);
            scroll.applyCss();
            scroll.requestLayout();
        }));
        root.setBottom(buildButtonBar());
        BorderPane.setMargin(root.getBottom(), new Insets(0, 12, 12, 12));

        Scene scene = new Scene(root);
        // Inline stylesheet (data URL) to neutralise modena's white viewport
        // and corner painting on our ScrollPane. Needed because sub-region
        // selectors can't be hit from an inline setStyle call.
        String css = ".subset-scroll,"
                + ".subset-scroll > .viewport,"
                + ".subset-scroll > .corner {"
                + "  -fx-background-color: transparent;"
                + "  -fx-background-insets: 0;"
                + "  -fx-padding: 0;"
                + "}"
                // Also neutralise the default white scrollbar track; scrollbar
                // thumb still picks up the platform theme.
                + ".subset-scroll > .scroll-bar,"
                + ".subset-scroll > .scroll-bar > .track,"
                + ".subset-scroll > .scroll-bar > .track-background {"
                + "  -fx-background-color: transparent;"
                + "  -fx-background-insets: 0;"
                + "}";
        scene.getStylesheets().add(
                "data:text/css;base64,"
                + java.util.Base64.getEncoder().encodeToString(
                        css.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        stage.setScene(scene);
        // Best-effort first attempt, so the very first frame is already themed.
        // The theme's stylesheet may not resolve this early, in which case this
        // is a no-op and the setOnShown handler above does it properly.
        applyThemedSceneFill(scene, root);
        // Keep width roomy enough for the longest label; let height size to
        // content. Users can shrink the window - the ScrollPane takes over.
        stage.setMinWidth(520);
        stage.setMinHeight(420);
        stage.sizeToScene();

        wireBindings();
        populateClassifierNames();
        refreshSourceCounts();
        recomputePreview();
    }

    /**
     * Paint the {@link Scene} in the same colour as the root's themed
     * background, so any region the root does not cover matches the dialog
     * instead of showing JavaFX's white default. A no-op while CSS has not yet
     * resolved {@code -fx-base} into a real background, so it is called both
     * before the stage is shown and again afterwards.
     */
    private static void applyThemedSceneFill(Scene scene, Region root) {
        if (scene == null || root == null) {
            return;
        }
        try {
            root.applyCss();
            var background = root.getBackground();
            if (background != null && !background.getFills().isEmpty()) {
                var paint = background.getFills().get(0).getFill();
                if (paint != null) {
                    scene.setFill(paint);
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Could not resolve a themed scene fill", e);
        }
    }

    // -----------------------------------------------------------------------------
    // Section builders
    // -----------------------------------------------------------------------------

    private Region buildHeader() {
        Label header = new Label(resources.getString("dialog.header"));
        header.setStyle("-fx-font-weight: bold;");
        return header;
    }

    private TitledPane buildClassifierSection() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label label = new Label(resources.getString("label.classifier.combo"));
        classifierCombo.setMaxWidth(Double.MAX_VALUE);
        classifierCombo.setTooltip(new Tooltip(resources.getString("tooltip.classifier")));
        label.setTooltip(new Tooltip(resources.getString("tooltip.classifier")));
        HBox.setHgrow(classifierCombo, Priority.ALWAYS);
        row.getChildren().addAll(label, classifierCombo);

        classifierClassesLabel.setStyle("-fx-text-fill: -fx-text-base-color; -fx-font-size: 0.9em; -fx-opacity: 0.85;");
        classifierClassesLabel.setWrapText(true);

        box.getChildren().addAll(row, classifierClassesLabel);

        TitledPane pane = new TitledPane(resources.getString("label.classifier.section"), box);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    private TitledPane buildSourceSection() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8));

        sourceAll.setToggleGroup(sourceGroup);
        sourceSelected.setToggleGroup(sourceGroup);
        sourceCustom.setToggleGroup(sourceGroup);
        sourceAll.setSelected(true);
        sourceAll.setTooltip(new Tooltip(resources.getString("tooltip.source.all")));
        sourceSelected.setTooltip(new Tooltip(resources.getString("tooltip.source.selected")));
        sourceCustom.setTooltip(new Tooltip(resources.getString("tooltip.source.custom")));

        HBox selectedRow = new HBox(8, sourceSelected, selectedCountLabel);
        selectedRow.setAlignment(Pos.CENTER_LEFT);
        selectedCountLabel.setStyle("-fx-font-style: italic; -fx-opacity: 0.8;");

        box.getChildren().addAll(sourceAll, selectedRow, sourceCustom);

        TitledPane pane = new TitledPane(resources.getString("label.source.section"), box);
        pane.setCollapsible(true);
        pane.setExpanded(true);
        return pane;
    }

    private TitledPane buildFiltersSection() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(8));

        // Class filter -- a checkbox list. Each row's checkbox is driven by the
        // ClassEntry's BooleanProperty; CheckBoxListCell wires the two together.
        VBox classBox = new VBox(6);
        Label classTitle = new Label(resources.getString("label.filter.class.title"));
        classTitle.setStyle("-fx-font-weight: bold;");
        classListView.setCellFactory(CheckBoxListCell.forListView(ClassEntry::selectedProperty));
        classListView.setItems(shownClassEntries);
        classListView.setPrefHeight(160);
        classListView.setMinHeight(120);
        classListView.setPlaceholder(new Label(resources.getString("label.filter.class.placeholder")));
        classListView.setTooltip(new Tooltip(resources.getString("tooltip.classFilter")));
        VBox.setVgrow(classListView, Priority.ALWAYS);

        // Find row. On highly multiplexed data the class list runs to dozens of
        // combinatorial entries, and ticking each one by hand is both tedious
        // and easy to get wrong - so narrow the list by substring, then tick
        // everything still showing in one click.
        classFindField.setPromptText(resources.getString("label.filter.class.find"));
        classFindField.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.find")));
        HBox.setHgrow(classFindField, Priority.ALWAYS);
        Button checkShown = new Button(resources.getString("label.filter.class.checkShown"));
        checkShown.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.checkShown")));
        checkShown.setOnAction(e -> setCheckedForShownEntries(true));
        Button uncheckShown = new Button(resources.getString("label.filter.class.uncheckShown"));
        uncheckShown.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.uncheckShown")));
        uncheckShown.setOnAction(e -> setCheckedForShownEntries(false));
        HBox findRow = new HBox(6, classFindField, checkShown, uncheckShown);
        findRow.setAlignment(Pos.CENTER_LEFT);

        includeUnclassifiedCheck.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.unclassified")));
        includeDerivedCheck.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.derived")));
        Button classClear = new Button(resources.getString("label.filter.class.clear"));
        classClear.setOnAction(e -> clearCheckedClasses());
        classCheckedLabel.setStyle("-fx-opacity: 0.75;");
        HBox classButtons = new HBox(8, includeUnclassifiedCheck, includeDerivedCheck,
                classClear, spacer(), classCheckedLabel);
        classButtons.setAlignment(Pos.CENTER_LEFT);
        classBox.getChildren().addAll(classTitle, findRow, classListView, classButtons);

        // Measurement filter -- one or more threshold rows, AND-combined.
        VBox measBox = new VBox(6);
        Label measTitle = new Label(resources.getString("label.filter.measurement.title"));
        measTitle.setStyle("-fx-font-weight: bold;");
        measurementEmptyLabel.setStyle("-fx-font-style: italic; -fx-opacity: 0.7;");
        addThresholdButton.setTooltip(new Tooltip(resources.getString("tooltip.measurement.add")));
        addThresholdButton.setOnAction(e -> {
            addMeasurementRow();
            recomputePreview();
        });
        refreshMeasurementEmptyState();
        measBox.getChildren().addAll(measTitle, measurementRowsBox, addThresholdButton);

        box.getChildren().addAll(classBox, new Separator(), measBox);

        filtersPane.setText(resources.getString("label.filter.section"));
        filtersPane.setContent(box);
        filtersPane.setCollapsible(true);
        filtersPane.setExpanded(false);
        return filtersPane;
    }

    private Region buildOptionsSection() {
        preserveClassCheck.setTooltip(new Tooltip(resources.getString("tooltip.preserve")));
        VBox box = new VBox(6, preserveClassCheck);
        box.setPadding(new Insets(0, 8, 0, 8));
        return box;
    }

    private Region buildPreviewSection() {
        previewLabel.setStyle("-fx-font-weight: bold;");
        previewLabel.setWrapText(true);
        warningLabel.setStyle("-fx-text-fill: #c0392b;");
        warningLabel.setWrapText(true);
        warningLabel.setVisible(false);
        warningLabel.setManaged(false);

        showSelectionButton.setTooltip(new Tooltip(resources.getString("tooltip.showSelection")));
        showSelectionButton.setOnAction(e -> showCurrentSelectionInViewer());

        Hyperlink docLink = new Hyperlink(resources.getString("label.preview.documentation"));
        docLink.setTooltip(new Tooltip(resources.getString("tooltip.documentation")));
        // The default Hyperlink colour in JavaFX modena is a dark navy that is
        // nearly invisible on QuPath's dark theme. Use the theme accent colour
        // (defined by atlantafx / overridden by QuPath themes) and force-bold
        // so the link is legible on both light and dark backgrounds.
        docLink.setStyle("-fx-text-fill: -fx-accent; -fx-font-weight: bold;");
        docLink.setOnAction(e -> openDocumentation());

        HBox row = new HBox(10, previewLabel, showSelectionButton, spacer(), docLink);
        row.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, row, warningLabel);
        box.setPadding(new Insets(8));
        return box;
    }

    private Region buildButtonBar() {
        applyButton.setDefaultButton(true);
        closeButton.setCancelButton(true);
        applyButton.setTooltip(new Tooltip(resources.getString("tooltip.apply")));
        applyButton.setOnAction(e -> onApply());
        closeButton.setOnAction(e -> stage.close());

        HBox bar = new HBox(8, spacer(), applyButton, closeButton);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(8, 0, 0, 0));
        return bar;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    // -----------------------------------------------------------------------------
    // Bindings & event wiring
    // -----------------------------------------------------------------------------

    private void wireBindings() {
        // Filters pane disabled unless source is CUSTOM; auto-expand/collapse on switch
        sourceGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            boolean custom = sourceCustom.isSelected();
            filtersPane.setDisable(!custom);
            filtersPane.setExpanded(custom);
            recomputePreview();
        });
        filtersPane.setDisable(true);

        // Measurement rows recompute the preview as they are edited; the
        // per-row change listeners are wired in addMeasurementRow().
        includeUnclassifiedCheck.selectedProperty().addListener((obs, oldV, newV) -> recomputePreview());
        includeDerivedCheck.selectedProperty().addListener((obs, oldV, newV) -> recomputePreview());

        // Find box narrows the visible rows only; ticks are held on the entries
        // themselves and in checkedClasses, so a class filtered out of view
        // stays part of the filter.
        classFindField.textProperty().addListener((obs, oldV, newV) -> applyClassFindFilter(newV));

        // The debounce fires once a burst of edits has settled; the recount
        // itself then runs off the FX thread.
        previewDebounce.setOnFinished(e -> launchPreview());
        previewExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "classify-subset-preview");
            t.setDaemon(true);
            return t;
        });

        // Hierarchy selection -> source counts and preview. Hold a reference
        // so we can detach on close - otherwise the listener leaks and keeps
        // firing against this dialog's controls after the user closes it.
        var hierarchy = imageData.getHierarchy();
        if (hierarchy != null) {
            hierarchySelectionListener = (src, oldSel, newSel) -> Platform.runLater(() -> {
                if (!stage.isShowing()) {
                    return;
                }
                refreshSourceCounts();
                recomputePreview();
            });
            hierarchy.getSelectionModel().addPathObjectSelectionListener(hierarchySelectionListener);

            // Hierarchy structure / classification changes -> re-derive the
            // classifier's universe so the dialog stays in sync if the user
            // runs cell detection, applies another classifier, or resets
            // classifications while we are open.
            hierarchyChangeListener = event -> {
                if (event == null || event.isChanging()) {
                    return;
                }
                Platform.runLater(() -> {
                    if (!stage.isShowing()) {
                        return;
                    }
                    refreshUniverseFromClassifier();
                });
            };
            hierarchy.addListener(hierarchyChangeListener);
        }

        // Image switch detection: if the user opens a different image while
        // this dialog is up, close the dialog so they cannot accidentally
        // Apply against the wrong (original) image's hierarchy.
        imageSwitchListener = (obs, oldData, newData) -> {
            if (newData != imageData) {
                Platform.runLater(() -> {
                    if (stage.isShowing()) {
                        Dialogs.showInfoNotification(
                                resources.getString("dialog.title"),
                                resources.getString("warning.imageChanged"));
                        stage.close();
                    }
                });
            }
        };
        qupath.imageDataProperty().addListener(imageSwitchListener);

        stage.setOnHidden(e -> {
            previewDebounce.stop();
            // Bump the generation so any in-flight background recount discards
            // its result instead of touching controls on a closed dialog.
            previewGeneration.incrementAndGet();
            if (previewExecutor != null) {
                previewExecutor.shutdownNow();
                previewExecutor = null;
            }
            if (hierarchy != null) {
                if (hierarchySelectionListener != null) {
                    hierarchy.getSelectionModel().removePathObjectSelectionListener(hierarchySelectionListener);
                    hierarchySelectionListener = null;
                }
                if (hierarchyChangeListener != null) {
                    hierarchy.removeListener(hierarchyChangeListener);
                    hierarchyChangeListener = null;
                }
            }
            if (imageSwitchListener != null) {
                qupath.imageDataProperty().removeListener(imageSwitchListener);
                imageSwitchListener = null;
            }
            // Free the (potentially large) cached object list explicitly so
            // it is collectable as soon as the dialog is closed.
            universeCache = Collections.emptyList();
            universeClassesCache = Collections.emptyList();
            universeMeasurementsCache = Collections.emptyList();
            currentClassifier = null;
        });

        // Classifier change -> reload caches
        classifierCombo.valueProperty().addListener((obs, oldV, newV) -> onClassifierSelected(newV));

        // Apply disabled state is managed in recomputePreview() (called on every
        // control change). We must NOT bind disableProperty here, otherwise the
        // setDisable() calls in recomputePreview() throw "A bound value cannot be set".
        applyButton.setDisable(true);
    }

    /**
     * Matches any *prefix* of a legal Java double literal, so the user can
     * type a number a character at a time without the filter dropping
     * intermediate states like "." (typing ".25"), "-" (typing "-3"),
     * "1e", or "1e-" on the way to "1e-5". Values that get through the
     * filter are not necessarily valid - that is checked separately when
     * the value is read.
     */
    private static final java.util.regex.Pattern NUMERIC_PREFIX =
            java.util.regex.Pattern.compile("^-?(\\d+\\.?\\d*|\\.\\d*|\\d*\\.)?([eE]-?\\d*)?$");

    private void configureNumericField(TextField field) {
        DoubleStringConverter converter = new DoubleStringConverter() {
            @Override public Double fromString(String value) {
                if (value == null || value.isBlank()) return null;
                try {
                    return Double.parseDouble(value.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            @Override public String toString(Double value) {
                return value == null ? "" : String.format(Locale.US, "%s", value);
            }
        };
        field.setTextFormatter(new TextFormatter<>(converter, null, change -> {
            String newText = change.getControlNewText();
            // Always allow deletion-to-empty and any in-progress numeric
            // prefix. Final parsing happens in parseField() when the value
            // is actually read - so a stuck-at-"." field simply produces
            // no MeasurementFilter and Apply stays disabled, no harm done.
            if (newText.isEmpty() || NUMERIC_PREFIX.matcher(newText).matches()) {
                return change;
            }
            return null;
        }));
        field.setPrefColumnCount(8);
    }

    // -----------------------------------------------------------------------------
    // Data refresh
    // -----------------------------------------------------------------------------

    private void populateClassifierNames() {
        Project<BufferedImage> project = qupath.getProject();
        if (project == null) {
            classifierCombo.setPlaceholder(new Label(resources.getString("label.classifier.placeholder.noProject")));
            classifierCombo.setItems(FXCollections.observableArrayList());
            return;
        }
        List<String> names = ClassifierLoader.listNames(project);
        if (names.isEmpty()) {
            classifierCombo.setPlaceholder(new Label(resources.getString("label.classifier.placeholder.noClassifiers")));
        } else {
            classifierCombo.setPlaceholder(new Label(resources.getString("label.classifier.placeholder.choose")));
        }
        classifierCombo.setItems(FXCollections.observableArrayList(names));
    }

    private void onClassifierSelected(String name) {
        currentClassifier = (name == null) ? null : ClassifierLoader.load(qupath.getProject(), name);
        if (currentClassifier == null) {
            universeCache = Collections.emptyList();
            universeClassesCache = Collections.emptyList();
            universeMeasurementsCache = Collections.emptyList();
            classifierClassesLabel.setText("");
            populateFilterChoices();
            recomputePreview();
            return;
        }
        recomputeUniverse();
    }

    /**
     * Re-derive {@link #universeCache} and the discovered class /
     * measurement caches from the current classifier. Called whenever the
     * classifier changes or the hierarchy structure changes underneath us.
     */
    private void refreshUniverseFromClassifier() {
        if (currentClassifier == null) {
            return;
        }
        recomputeUniverse();
    }

    private void recomputeUniverse() {

        try {
            Collection<PathObject> universe = currentClassifier.getCompatibleObjects(imageData);
            universeCache = (universe == null) ? Collections.emptyList() : new ArrayList<>(universe);
        } catch (RuntimeException e) {
            logger.warn("Classifier threw while listing compatible objects", e);
            universeCache = Collections.emptyList();
        }

        // Discover classes present
        Set<PathClass> classSet = new LinkedHashSet<>();
        for (PathObject o : universeCache) {
            PathClass pc = o.getPathClass();
            if (pc != null) {
                classSet.add(pc);
            }
        }
        universeClassesCache = new ArrayList<>(classSet);

        // Discover measurements (cap at 5000 objects to stay snappy)
        List<PathObject> sample = universeCache.size() > 5000
                ? universeCache.subList(0, 5000) : universeCache;
        Set<String> measurementNames = MeasurementFilter.discoverMeasurementNames(sample);
        universeMeasurementsCache = new ArrayList<>(measurementNames);

        // Set classifier labels label
        Collection<PathClass> outputClasses = currentClassifier.getPathClasses();
        if (outputClasses == null || outputClasses.isEmpty()) {
            classifierClassesLabel.setText("");
        } else {
            StringBuilder sb = new StringBuilder(resources.getString("label.classifier.classes")).append(' ');
            boolean first = true;
            for (PathClass pc : outputClasses) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(pc == null ? ClassFilter.UNCLASSIFIED_LITERAL : pc.toString());
            }
            classifierClassesLabel.setText(sb.toString());
        }

        populateFilterChoices();
        recomputePreview();
    }

    private void populateFilterChoices() {
        // Drop ticks for classes that are no longer present, then rebuild the
        // checkbox entries, restoring ticks from the surviving checked set.
        checkedClasses.retainAll(universeClassesCache);
        List<ClassEntry> entries = new ArrayList<>(universeClassesCache.size());
        for (PathClass pc : universeClassesCache) {
            ClassEntry entry = new ClassEntry(pc);
            entry.selectedProperty().set(checkedClasses.contains(pc));
            entry.selectedProperty().addListener((obs, was, now) -> {
                if (Boolean.TRUE.equals(now)) {
                    checkedClasses.add(pc);
                } else {
                    checkedClasses.remove(pc);
                }
                refreshCheckedCountLabel();
                recomputePreview();
            });
            entries.add(entry);
        }
        // Replace the master list; the FilteredList view and its predicate,
        // and therefore whatever the user typed in Find, survive untouched.
        allClassEntries.setAll(entries);
        refreshCheckedCountLabel();

        // Refresh the measurement-name choices in every existing threshold row,
        // preserving each row's current selection.
        for (MeasurementRow row : measurementRows) {
            row.setMeasurements(universeMeasurementsCache);
        }
    }

    /**
     * Narrow the visible class rows to those whose classification contains
     * {@code text}, case-insensitively. Checked state is untouched.
     */
    private void applyClassFindFilter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            shownClassEntries.setPredicate(entry -> true);
        } else {
            shownClassEntries.setPredicate(entry ->
                    entry.toString().toLowerCase(Locale.ROOT).contains(needle));
        }
        refreshCheckedCountLabel();
    }

    /**
     * Check or uncheck every row currently visible in the list - i.e. every row
     * matching the Find box. Rows hidden by the filter keep their state, so
     * this narrows to a group and acts on exactly that group.
     */
    private void setCheckedForShownEntries(boolean checked) {
        // The per-entry listeners maintain checkedClasses one row at a time;
        // suppress the recount until the whole batch has been applied.
        suppressPreview = true;
        try {
            for (ClassEntry entry : new ArrayList<>(shownClassEntries)) {
                entry.selectedProperty().set(checked);
            }
        } finally {
            suppressPreview = false;
        }
        refreshCheckedCountLabel();
        recomputePreview();
    }

    private void refreshCheckedCountLabel() {
        int total = allClassEntries.size();
        int shown = shownClassEntries.size();
        String text;
        if (shown == total) {
            text = MessageFormat.format(
                    resources.getString("label.filter.class.checkedCount"),
                    checkedClasses.size(), total);
        } else {
            text = MessageFormat.format(
                    resources.getString("label.filter.class.checkedCountFiltered"),
                    checkedClasses.size(), total, shown);
        }
        classCheckedLabel.setText(text);
    }

    private void refreshSourceCounts() {
        selectedCountLabel.setText(MessageFormat.format(
                resources.getString("label.source.selectedCount"), selectedObjectCount()));
    }

    // -----------------------------------------------------------------------------
    // Preview / count
    // -----------------------------------------------------------------------------

    private List<PathObject> currentSubsetSnapshot() {
        if (currentClassifier == null || universeCache.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return ObjectSubsetSelector.apply(universeCache, currentSelection(), buildCriteriaForPreview());
        } catch (Exception e) {
            logger.debug("Preview subset selection failed", e);
            return Collections.emptyList();
        }
    }

    private int currentSubsetCount() {
        return currentSubsetSnapshot().size();
    }

    /**
     * Ask for a preview recount. Cheap and safe to call from any control
     * listener: requests inside the debounce window collapse into one
     * background pass, so ticking twenty classes costs one recount, not twenty.
     */
    private void recomputePreview() {
        if (suppressPreview) {
            return;
        }
        // Invalidate here, not only at submit time: the early-exit paths in
        // launchPreview() write the labels directly, and without this bump an
        // older in-flight recount could land afterwards and undo them.
        previewGeneration.incrementAndGet();
        previewDebounce.playFromStart();
    }

    /**
     * Snapshot the inputs on the FX thread, then count the subset (and check
     * for missing features) on a background thread. A generation counter makes
     * a superseded result discard itself instead of overwriting a newer one.
     */
    private void launchPreview() {
        if (currentClassifier == null) {
            previewLabel.setText("");
            applyButton.setDisable(true);
            setWarning(null);
            return;
        }
        List<PathObject> universe = universeCache;
        final int universeSize = universe.size();
        if (universeSize == 0) {
            previewLabel.setText(MessageFormat.format(
                    resources.getString("label.preview.count"), 0, 0));
            setWarning(resources.getString("warning.incompatibleClassifier"));
            applyButton.setDisable(true);
            return;
        }

        // Everything the background pass touches is read here, on the FX
        // thread, so the task never reads a control or a mutating collection.
        final SubsetCriteria criteria = buildCriteriaForPreview();
        final ObjectClassifier<BufferedImage> classifier = currentClassifier;
        final boolean selectedSourceEmpty = sourceSelected.isSelected() && selectedObjectCount() == 0;
        final List<PathObject> selection = new ArrayList<>(currentSelection());
        final long generation = previewGeneration.incrementAndGet();

        ExecutorService executor = previewExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }
        executor.submit(() -> {
            int count;
            String missing;
            try {
                List<PathObject> subset = ObjectSubsetSelector.apply(universe, selection, criteria);
                count = subset.size();
                missing = describeMissingFeatures(classifier, subset);
            } catch (Exception ex) {
                logger.debug("Preview subset selection failed", ex);
                count = 0;
                missing = null;
            }
            final int finalCount = count;
            final String finalMissing = missing;
            Platform.runLater(() -> {
                if (generation != previewGeneration.get() || !stage.isShowing()) {
                    return;
                }
                applyPreviewResult(finalCount, universeSize, finalMissing, selectedSourceEmpty);
            });
        });
    }

    private void applyPreviewResult(int subset, int universe, String missing, boolean selectedSourceEmpty) {
        previewLabel.setText(MessageFormat.format(
                resources.getString("label.preview.count"), subset, universe));
        if (subset == 0) {
            setWarning(selectedSourceEmpty
                    ? resources.getString("warning.noSelection")
                    : resources.getString("label.preview.zero"));
        } else {
            // Surface missing-feature warnings before Apply, not just after,
            // so the user knows the classifier may not behave as expected.
            setWarning(missing);
        }
        applyButton.setDisable(subset == 0);
    }

    private Collection<PathObject> currentSelection() {
        return imageData.getHierarchy() != null
                ? imageData.getHierarchy().getSelectionModel().getSelectedObjects()
                : Collections.emptyList();
    }

    private int selectedObjectCount() {
        return currentSelection().size();
    }

    private String describeMissingFeatures(ObjectClassifier<BufferedImage> classifier, List<PathObject> subset) {
        if (classifier == null || subset == null || subset.isEmpty()) {
            return null;
        }
        try {
            var missing = classifier.getMissingFeatures(imageData, subset);
            if (missing == null || missing.isEmpty()) {
                return null;
            }
            int total = missing.values().stream().mapToInt(Integer::intValue).sum();
            String first = missing.keySet().iterator().next();
            String suffix = missing.size() > 1
                    ? " (+ " + (missing.size() - 1) + " more)"
                    : "";
            return MessageFormat.format(resources.getString("warning.missingFeatures.preview"),
                    total, first, suffix);
        } catch (Exception e) {
            logger.debug("Could not compute missing features for preview", e);
            return null;
        }
    }

    private void setWarning(String text) {
        if (text == null || text.isBlank()) {
            warningLabel.setText("");
            warningLabel.setVisible(false);
            warningLabel.setManaged(false);
        } else {
            warningLabel.setText(text);
            warningLabel.setVisible(true);
            warningLabel.setManaged(true);
        }
    }

    // -----------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------

    private void showCurrentSelectionInViewer() {
        List<PathObject> subset = currentSubsetSnapshot();
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            return;
        }
        if (subset.isEmpty()) {
            hierarchy.getSelectionModel().clearSelection();
            return;
        }
        hierarchy.getSelectionModel().setSelectedObjects(subset, subset.get(0));
    }

    private void openDocumentation() {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(DOC_URL));
                return;
            }
        } catch (Exception e) {
            logger.debug("Desktop browse failed: {}", e.getMessage());
        }
        Dialogs.showInfoNotification(resources.getString("dialog.title"), DOC_URL);
    }

    private SubsetCriteria buildCriteriaForPreview() {
        SubsetCriteria.Builder b = SubsetCriteria.builder()
                .source(currentSource())
                .preserveExistingClass(preserveClassCheck.isSelected());
        if (sourceCustom.isSelected()) {
            ClassFilter cf = buildClassFilter();
            if (cf != null && !cf.isAcceptAll()) {
                b.classFilter(cf);
            }
            for (MeasurementFilter mf : buildMeasurementFilters()) {
                b.measurementFilter(mf);
            }
        }
        return b.build();
    }

    private ObjectSourceMode currentSource() {
        if (sourceSelected.isSelected()) return ObjectSourceMode.SELECTED_ONLY;
        if (sourceCustom.isSelected()) return ObjectSourceMode.CUSTOM;
        return ObjectSourceMode.ALL_COMPATIBLE;
    }

    private ClassFilter buildClassFilter() {
        Set<PathClass> selected = new LinkedHashSet<>(checkedClasses);
        boolean includeUnclassified = includeUnclassifiedCheck.isSelected();
        if (selected.isEmpty() && !includeUnclassified) {
            return null;
        }
        return ClassFilter.of(selected, includeUnclassified,
                includeDerivedCheck.isSelected()
                        ? ClassFilter.MatchMode.INCLUDE_DERIVED
                        : ClassFilter.MatchMode.EXACT);
    }

    /**
     * Collect the valid measurement filters from the threshold rows. Rows that
     * are incomplete (no measurement chosen, no value typed, or a "between" row
     * missing its upper bound) are skipped so the preview stays live while the
     * user is still filling a row in.
     */
    private List<MeasurementFilter> buildMeasurementFilters() {
        List<MeasurementFilter> filters = new ArrayList<>(measurementRows.size());
        for (MeasurementRow row : measurementRows) {
            MeasurementFilter mf = row.toFilter();
            if (mf != null) {
                filters.add(mf);
            }
        }
        return filters;
    }

    private static Double parseField(TextField field) {
        String text = field.getText();
        if (text == null || text.isBlank()) return null;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------------
    // Class checkbox + measurement row management
    // -----------------------------------------------------------------------------

    private void clearCheckedClasses() {
        // Clear every row, not just the ones the Find box is showing - "Clear"
        // has to mean the whole filter or the preview and the list disagree.
        suppressPreview = true;
        try {
            for (ClassEntry entry : new ArrayList<>(allClassEntries)) {
                entry.selectedProperty().set(false);
            }
        } finally {
            suppressPreview = false;
        }
        checkedClasses.clear();
        refreshCheckedCountLabel();
        recomputePreview();
    }

    /** Append an empty threshold row defaulting to "greater than". */
    private void addMeasurementRow() {
        MeasurementRow row = new MeasurementRow();
        row.setMeasurements(universeMeasurementsCache);
        measurementRows.add(row);
        measurementRowsBox.getChildren().add(row.container);
        refreshMeasurementEmptyState();
    }

    private void removeMeasurementRow(MeasurementRow row) {
        if (measurementRows.remove(row)) {
            measurementRowsBox.getChildren().remove(row.container);
            refreshMeasurementEmptyState();
            recomputePreview();
        }
    }

    private void refreshMeasurementEmptyState() {
        // Show the "no thresholds" placeholder only when no rows exist; never
        // let it occupy a row slot alongside real rows.
        measurementRowsBox.getChildren().remove(measurementEmptyLabel);
        if (measurementRows.isEmpty()) {
            measurementRowsBox.getChildren().add(0, measurementEmptyLabel);
        }
    }

    /**
     * Wrapper around a {@link PathClass} carrying a {@code selected} property so
     * a {@link CheckBoxListCell} can render and toggle its checkbox. Equality is
     * by wrapped class so a checked entry survives list repopulation.
     */
    private static final class ClassEntry {
        private final PathClass pathClass;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);

        ClassEntry(PathClass pathClass) {
            this.pathClass = pathClass;
        }

        BooleanProperty selectedProperty() {
            return selected;
        }

        @Override
        public String toString() {
            return pathClass == null ? ClassFilter.UNCLASSIFIED_LITERAL : pathClass.toString();
        }
    }

    /**
     * A single measurement-threshold row: measurement combo, comparator,
     * value-1 / value-2 fields (value-2 shown only for "between"), and a remove
     * button. Lives in {@link #measurementRows}; any edit recomputes the preview.
     */
    private final class MeasurementRow {
        final ComboBox<String> measurementCombo = new ComboBox<>();
        final ComboBox<Comparator> comparatorCombo =
                new ComboBox<>(FXCollections.observableArrayList(Comparator.values()));
        final TextField value1Field = new TextField();
        final TextField value2Field = new TextField();
        final Label andLabel = new Label(resources.getString("label.filter.measurement.and"));
        final Button removeButton = new Button(resources.getString("label.filter.measurement.removeRow"));
        final HBox container;

        MeasurementRow() {
            measurementCombo.setMaxWidth(Double.MAX_VALUE);
            measurementCombo.setPlaceholder(new Label(resources.getString("label.filter.measurement.placeholder")));
            measurementCombo.setTooltip(new Tooltip(resources.getString("tooltip.measurement.combo")));

            comparatorCombo.setConverter(new StringConverter<Comparator>() {
                @Override public String toString(Comparator c) {
                    if (c == null) return "";
                    return c.symbol() + "  (" + c.label() + ")";
                }
                @Override public Comparator fromString(String s) { return null; }
            });
            comparatorCombo.getSelectionModel().select(Comparator.GT);
            comparatorCombo.setTooltip(new Tooltip(resources.getString("tooltip.measurement.op")));

            configureNumericField(value1Field);
            configureNumericField(value2Field);
            value1Field.setTooltip(new Tooltip(resources.getString("tooltip.measurement.value")));
            value2Field.setTooltip(new Tooltip(resources.getString("tooltip.measurement.value2")));

            removeButton.setTooltip(new Tooltip(resources.getString("tooltip.measurement.removeRow")));
            removeButton.setAccessibleText(resources.getString("tooltip.measurement.removeRow"));
            removeButton.setOnAction(e -> removeMeasurementRow(this));

            Label isLabel = new Label(resources.getString("label.filter.measurement.is"));
            container = new HBox(6, measurementCombo, isLabel, comparatorCombo,
                    value1Field, andLabel, value2Field, removeButton);
            container.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(measurementCombo, Priority.ALWAYS);

            comparatorCombo.valueProperty().addListener((obs, oldV, newV) -> {
                updateValue2Visibility();
                recomputePreview();
            });
            measurementCombo.valueProperty().addListener((obs, oldV, newV) -> recomputePreview());
            value1Field.textProperty().addListener((obs, oldV, newV) -> recomputePreview());
            value2Field.textProperty().addListener((obs, oldV, newV) -> recomputePreview());

            updateValue2Visibility();
        }

        void setMeasurements(List<String> measurements) {
            String prior = measurementCombo.getValue();
            measurementCombo.setItems(FXCollections.observableArrayList(measurements));
            if (prior != null && measurements.contains(prior)) {
                measurementCombo.setValue(prior);
            }
        }

        private void updateValue2Visibility() {
            Comparator op = comparatorCombo.getValue();
            boolean usesTwo = op != null && op.usesSecondValue();
            value2Field.setVisible(usesTwo);
            value2Field.setManaged(usesTwo);
            andLabel.setVisible(usesTwo);
            andLabel.setManaged(usesTwo);
        }

        /** Build a filter from this row, or {@code null} if the row is incomplete. */
        MeasurementFilter toFilter() {
            String name = measurementCombo.getValue();
            if (name == null || name.isBlank()) {
                return null;
            }
            Comparator op = comparatorCombo.getValue();
            if (op == null) {
                return null;
            }
            Double v1 = parseField(value1Field);
            if (v1 == null) {
                return null;
            }
            Double v2 = op.usesSecondValue() ? parseField(value2Field) : null;
            if (op.usesSecondValue() && v2 == null) {
                return null;
            }
            return new MeasurementFilter(name, op, v1, v2 == null ? Double.NaN : v2);
        }
    }

    private void onApply() {
        if (currentClassifier == null) {
            return;
        }
        String classifierName = classifierCombo.getValue();

        // Reload the classifier from disk in case it has been edited or
        // deleted since the dialog opened. This costs one IO read per Apply
        // and keeps us honest about which model actually runs.
        var freshClassifier = ClassifierLoader.load(qupath.getProject(), classifierName);
        if (freshClassifier == null) {
            Dialogs.showWarningNotification(
                    resources.getString("notification.warning.title"),
                    MessageFormat.format(resources.getString("warning.classifierGone"), classifierName));
            populateClassifierNames();
            currentClassifier = null;
            recomputePreview();
            return;
        }
        currentClassifier = freshClassifier;

        SubsetCriteria criteria = buildCriteriaForPreview();
        Collection<PathObject> selection = currentSelection();

        applyButton.setDisable(true);
        try {
            SubsetClassificationRunner.Result result = SubsetClassificationRunner.run(
                    imageData, currentClassifier, classifierName, selection, criteria, true);

            if (!result.ranSuccessfully()) {
                Dialogs.showWarningNotification(
                        resources.getString("notification.warning.title"),
                        result.warning == null ? "No objects classified." : result.warning);
            } else {
                String msg = MessageFormat.format(
                        resources.getString("notification.success.message"),
                        result.nSelected, result.nChanged);
                if (result.warning != null) {
                    msg = msg + "\n" + result.warning;
                    Dialogs.showWarningNotification(
                            resources.getString("notification.warning.title"), msg);
                } else {
                    Dialogs.showInfoNotification(
                            resources.getString("notification.success.title"), msg);
                }
            }
        } catch (RuntimeException e) {
            logger.error("Subset classification failed", e);
            Dialogs.showErrorMessage(
                    resources.getString("notification.error.title"), e.getMessage());
        } finally {
            recomputePreview();
        }
    }
}
