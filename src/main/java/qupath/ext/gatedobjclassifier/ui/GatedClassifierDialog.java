package qupath.ext.gatedobjclassifier.ui;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.HPos;
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
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import javafx.util.converter.DoubleStringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.gatedobjclassifier.core.ClassFilter;
import qupath.ext.gatedobjclassifier.core.ClassifierLoader;
import qupath.ext.gatedobjclassifier.core.Comparator;
import qupath.ext.gatedobjclassifier.core.GatedClassificationRunner;
import qupath.ext.gatedobjclassifier.core.GatingCriteria;
import qupath.ext.gatedobjclassifier.core.MeasurementFilter;
import qupath.ext.gatedobjclassifier.core.ObjectGater;
import qupath.ext.gatedobjclassifier.core.ObjectSourceMode;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.classifiers.object.ObjectClassifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.Project;

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

/**
 * Modeless dialog for the Gated Object Classifier extension.
 *
 * <p>Lets the user pick a project classifier and a gating strategy
 * (all compatible / current selection / class+measurement custom filter),
 * then applies the classifier to the gated subset and records the operation
 * as a workflow step.</p>
 *
 * <p>Built as a single class (rather than several small {@code Pane} classes)
 * because the controls share a lot of cross-cutting state - source mode
 * enables/disables filters, every change recomputes the preview - and the
 * extra plumbing for property bridges would not pay for itself.</p>
 */
public final class GatedClassifierDialog {

    private static final Logger logger = LoggerFactory.getLogger(GatedClassifierDialog.class);

    private static final ResourceBundle resources =
            ResourceBundle.getBundle("qupath.ext.gatedobjclassifier.ui.strings");

    private static final String DOC_URL =
            "https://github.com/MichaelSNelson/qupath-extension-gated-object-classifier#readme";

    private final QuPathGUI qupath;
    private final ImageData<BufferedImage> imageData;
    private final Stage stage;

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
    private final ListView<PathClass> classListView = new ListView<>();
    private final CheckBox includeUnclassifiedCheck = new CheckBox(resources.getString("label.filter.class.includeUnclassified"));
    private final CheckBox enableMeasurementCheck = new CheckBox(resources.getString("label.filter.measurement.enable"));
    private final ComboBox<String> measurementCombo = new ComboBox<>();
    private final ComboBox<Comparator> comparatorCombo = new ComboBox<>(FXCollections.observableArrayList(Comparator.values()));
    private final TextField value1Field = new TextField();
    private final TextField value2Field = new TextField();
    private final Label value2Label = new Label(resources.getString("label.filter.measurement.value2"));

    // --- Options
    private final CheckBox preserveClassCheck = new CheckBox(resources.getString("label.options.preserveClass"));

    // --- Preview
    private final Label previewLabel = new Label();
    private final Label warningLabel = new Label();
    private final Button showSelectionButton = new Button(resources.getString("label.preview.show"));

    // --- Actions
    private final Button applyButton = new Button(resources.getString("button.apply"));
    private final Button closeButton = new Button(resources.getString("button.close"));

    private GatedClassifierDialog(QuPathGUI qupath, ImageData<BufferedImage> imageData) {
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
            new GatedClassifierDialog(qupath, imageData).stage.show();
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
        // BorderPane and button bar don't show modena's white default behind
        // the themed TitledPanes. QuPath sets its theme via
        // Application.setUserAgentStylesheet, which defines -fx-base.
        root.setStyle("-fx-background-color: -fx-base;");
        VBox center = new VBox(10);
        center.setPadding(new Insets(12));

        center.getChildren().addAll(
                buildHeader(),
                new Separator(),
                buildClassifierSection(),
                buildSourceSection(),
                buildFiltersSection(),
                buildOptionsSection(),
                buildPreviewSection()
        );
        root.setCenter(center);
        root.setBottom(buildButtonBar());
        BorderPane.setMargin(root.getBottom(), new Insets(0, 12, 12, 12));

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(640);
        stage.sizeToScene();

        wireBindings();
        populateClassifierNames();
        refreshSourceCounts();
        recomputePreview();
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

        // Class filter
        VBox classBox = new VBox(6);
        Label classTitle = new Label(resources.getString("label.filter.class.title"));
        classTitle.setStyle("-fx-font-weight: bold;");
        classListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        classListView.setPrefHeight(160);
        classListView.setMinHeight(120);
        classListView.setPlaceholder(new Label(resources.getString("label.filter.class.placeholder")));
        classListView.setTooltip(new Tooltip(resources.getString("tooltip.classFilter")));
        VBox.setVgrow(classListView, Priority.ALWAYS);
        includeUnclassifiedCheck.setTooltip(new Tooltip(resources.getString("tooltip.classFilter.unclassified")));
        Button classClear = new Button(resources.getString("label.filter.class.clear"));
        classClear.setOnAction(e -> classListView.getSelectionModel().clearSelection());
        HBox classButtons = new HBox(8, includeUnclassifiedCheck, classClear);
        classButtons.setAlignment(Pos.CENTER_LEFT);
        classBox.getChildren().addAll(classTitle, classListView, classButtons);

        // Measurement filter
        VBox measBox = new VBox(6);
        Label measTitle = new Label(resources.getString("label.filter.measurement.title"));
        measTitle.setStyle("-fx-font-weight: bold;");
        measurementCombo.setMaxWidth(Double.MAX_VALUE);
        measurementCombo.setPlaceholder(new Label(resources.getString("label.filter.measurement.placeholder")));
        comparatorCombo.setConverter(new StringConverter<Comparator>() {
            @Override public String toString(Comparator c) {
                if (c == null) return "";
                return c.symbol() + "  (" + c.label() + ")";
            }
            @Override public Comparator fromString(String s) { return null; }
        });
        comparatorCombo.getSelectionModel().select(Comparator.GT);
        configureNumericField(value1Field);
        configureNumericField(value2Field);

        enableMeasurementCheck.setTooltip(new Tooltip(resources.getString("tooltip.measurement.enable")));
        measurementCombo.setTooltip(new Tooltip(resources.getString("tooltip.measurement.combo")));
        comparatorCombo.setTooltip(new Tooltip(resources.getString("tooltip.measurement.op")));
        value1Field.setTooltip(new Tooltip(resources.getString("tooltip.measurement.value")));
        value2Field.setTooltip(new Tooltip(resources.getString("tooltip.measurement.value2")));

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.add(new Label(resources.getString("label.filter.measurement.combo")), 0, 0);
        grid.add(measurementCombo, 1, 0, 3, 1);
        GridPane.setHgrow(measurementCombo, Priority.ALWAYS);
        grid.add(new Label(resources.getString("label.filter.measurement.op")), 0, 1);
        grid.add(comparatorCombo, 1, 1);
        grid.add(new Label(resources.getString("label.filter.measurement.value")), 2, 1);
        grid.add(value1Field, 3, 1);
        grid.add(value2Label, 2, 2);
        grid.add(value2Field, 3, 2);
        GridPane.setHalignment(value2Label, HPos.RIGHT);

        measBox.getChildren().addAll(measTitle, enableMeasurementCheck, grid);

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

        // Measurement filter row enable/disable
        measurementCombo.disableProperty().bind(enableMeasurementCheck.selectedProperty().not());
        comparatorCombo.disableProperty().bind(enableMeasurementCheck.selectedProperty().not());
        value1Field.disableProperty().bind(enableMeasurementCheck.selectedProperty().not());

        comparatorCombo.valueProperty().addListener((obs, oldV, newV) -> {
            boolean usesTwo = newV != null && newV.usesSecondValue();
            value2Field.setVisible(usesTwo);
            value2Field.setManaged(usesTwo);
            value2Label.setVisible(usesTwo);
            value2Label.setManaged(usesTwo);
            recomputePreview();
        });
        // Initialize visibility for default GT
        Comparator initial = comparatorCombo.getValue();
        boolean usesTwoInitial = initial != null && initial.usesSecondValue();
        value2Field.setVisible(usesTwoInitial);
        value2Field.setManaged(usesTwoInitial);
        value2Label.setVisible(usesTwoInitial);
        value2Label.setManaged(usesTwoInitial);

        enableMeasurementCheck.selectedProperty().addListener((obs, oldV, newV) -> recomputePreview());
        measurementCombo.valueProperty().addListener((obs, oldV, newV) -> recomputePreview());
        value1Field.textProperty().addListener((obs, oldV, newV) -> recomputePreview());
        value2Field.textProperty().addListener((obs, oldV, newV) -> recomputePreview());
        includeUnclassifiedCheck.selectedProperty().addListener((obs, oldV, newV) -> recomputePreview());
        classListView.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<PathClass>) change -> recomputePreview());

        // Hierarchy selection -> source counts and preview
        var hierarchy = imageData.getHierarchy();
        if (hierarchy != null) {
            hierarchy.getSelectionModel().addPathObjectSelectionListener((src, oldSel, newSel) -> {
                Platform.runLater(() -> {
                    refreshSourceCounts();
                    recomputePreview();
                });
            });
        }

        // Classifier change -> reload caches
        classifierCombo.valueProperty().addListener((obs, oldV, newV) -> onClassifierSelected(newV));

        // Apply disabled state is managed in recomputePreview() (called on every
        // control change). We must NOT bind disableProperty here, otherwise the
        // setDisable() calls in recomputePreview() throw "A bound value cannot be set".
        applyButton.setDisable(true);
    }

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
            if (newText.isEmpty() || newText.equals("-") || newText.equals(".") || newText.equals("-.")) {
                return change;
            }
            try {
                Double.parseDouble(newText);
                return change;
            } catch (NumberFormatException e) {
                return null;
            }
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

        try {
            Collection<PathObject> universe = currentClassifier.getCompatibleObjects(imageData);
            universeCache = (universe == null) ? Collections.emptyList() : new ArrayList<>(universe);
        } catch (RuntimeException e) {
            logger.warn("Classifier '{}' threw while listing compatible objects", name, e);
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
        Set<String> names = MeasurementFilter.discoverMeasurementNames(sample);
        universeMeasurementsCache = new ArrayList<>(names);

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
        ObservableList<PathClass> classItems = FXCollections.observableArrayList(universeClassesCache);
        classListView.setItems(classItems);

        ObservableList<String> measItems = FXCollections.observableArrayList(universeMeasurementsCache);
        String previousValue = measurementCombo.getValue();
        measurementCombo.setItems(measItems);
        if (previousValue != null && measItems.contains(previousValue)) {
            measurementCombo.setValue(previousValue);
        } else if (!measItems.isEmpty()) {
            measurementCombo.setValue(null);
        }
    }

    private void refreshSourceCounts() {
        int n = 0;
        var hierarchy = imageData.getHierarchy();
        if (hierarchy != null) {
            n = hierarchy.getSelectionModel().getSelectedObjects().size();
        }
        selectedCountLabel.setText(MessageFormat.format(
                resources.getString("label.source.selectedCount"), n));
    }

    // -----------------------------------------------------------------------------
    // Preview / count
    // -----------------------------------------------------------------------------

    private List<PathObject> currentGatedSnapshot() {
        if (currentClassifier == null || universeCache.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<PathObject> selected = imageData.getHierarchy() != null
                ? imageData.getHierarchy().getSelectionModel().getSelectedObjects()
                : Collections.emptyList();
        try {
            return ObjectGater.apply(universeCache, selected, buildCriteriaForPreview());
        } catch (Exception e) {
            logger.debug("Preview gating failed", e);
            return Collections.emptyList();
        }
    }

    private int currentGatedCount() {
        return currentGatedSnapshot().size();
    }

    private void recomputePreview() {
        int gated = currentGatedCount();
        int universe = universeCache.size();

        if (currentClassifier == null) {
            previewLabel.setText("");
            applyButton.setDisable(true);
            setWarning(null);
            return;
        }
        if (universe == 0) {
            previewLabel.setText(MessageFormat.format(
                    resources.getString("label.preview.count"), 0, 0));
            setWarning(resources.getString("warning.incompatibleClassifier"));
            applyButton.setDisable(true);
            return;
        }

        previewLabel.setText(MessageFormat.format(
                resources.getString("label.preview.count"), gated, universe));

        if (gated == 0) {
            String reason = resources.getString("label.preview.zero");
            if (sourceSelected.isSelected()) {
                int sel = imageData.getHierarchy() == null ? 0
                        : imageData.getHierarchy().getSelectionModel().getSelectedObjects().size();
                if (sel == 0) {
                    reason = resources.getString("warning.noSelection");
                }
            }
            setWarning(reason);
        } else {
            setWarning(null);
        }
        applyButton.setDisable(currentClassifier == null || gated == 0);
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
        List<PathObject> gated = currentGatedSnapshot();
        PathObjectHierarchy hierarchy = imageData.getHierarchy();
        if (hierarchy == null) {
            return;
        }
        if (gated.isEmpty()) {
            hierarchy.getSelectionModel().clearSelection();
            return;
        }
        hierarchy.getSelectionModel().setSelectedObjects(gated, gated.get(0));
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

    private GatingCriteria buildCriteriaForPreview() {
        GatingCriteria.Builder b = GatingCriteria.builder()
                .source(currentSource())
                .preserveExistingClass(preserveClassCheck.isSelected());
        if (sourceCustom.isSelected()) {
            ClassFilter cf = buildClassFilter();
            if (cf != null && !cf.isAcceptAll()) {
                b.classFilter(cf);
            }
            MeasurementFilter mf = buildMeasurementFilter();
            if (mf != null) {
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
        Set<PathClass> selected = new LinkedHashSet<>(classListView.getSelectionModel().getSelectedItems());
        boolean includeUnclassified = includeUnclassifiedCheck.isSelected();
        if (selected.isEmpty() && !includeUnclassified) {
            return null;
        }
        return ClassFilter.of(selected, includeUnclassified);
    }

    private MeasurementFilter buildMeasurementFilter() {
        if (!enableMeasurementCheck.isSelected()) {
            return null;
        }
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

    private static Double parseField(TextField field) {
        String text = field.getText();
        if (text == null || text.isBlank()) return null;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void onApply() {
        if (currentClassifier == null) {
            return;
        }
        GatingCriteria criteria = buildCriteriaForPreview();
        Collection<PathObject> selection = imageData.getHierarchy() != null
                ? imageData.getHierarchy().getSelectionModel().getSelectedObjects()
                : Collections.emptyList();
        String classifierName = classifierCombo.getValue();

        applyButton.setDisable(true);
        try {
            GatedClassificationRunner.Result result = GatedClassificationRunner.run(
                    imageData, currentClassifier, classifierName, selection, criteria, true);

            if (!result.ranSuccessfully()) {
                Dialogs.showWarningNotification(
                        resources.getString("notification.warning.title"),
                        result.warning == null ? "No objects classified." : result.warning);
            } else {
                String msg = MessageFormat.format(
                        resources.getString("notification.success.message"),
                        result.nGated, result.nChanged);
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
            logger.error("Gated classification failed", e);
            Dialogs.showErrorMessage(
                    resources.getString("notification.error.title"), e.getMessage());
        } finally {
            recomputePreview();
        }
    }
}
