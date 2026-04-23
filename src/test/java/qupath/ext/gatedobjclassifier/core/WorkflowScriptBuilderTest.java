package qupath.ext.gatedobjclassifier.core;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.classes.PathClass;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowScriptBuilderTest {

    @Test
    void allCompatibleScriptIsMinimal() {
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.ALL_COMPATIBLE)
                .build();
        String script = WorkflowScriptBuilder.buildScript("MyClassifier", crit);
        assertThat(script)
                .contains("import qupath.ext.gatedobjclassifier.scripting.GatedObjectClassifierScripts")
                .contains("\"MyClassifier\"")
                .contains("source : \"ALL_COMPATIBLE\"")
                .doesNotContain("classes")
                .doesNotContain("measurement")
                .doesNotContain("preserveClass");
    }

    @Test
    void selectedOnlyScript() {
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.SELECTED_ONLY)
                .build();
        String script = WorkflowScriptBuilder.buildScript("MyClassifier", crit);
        assertThat(script).contains("source : \"SELECTED_ONLY\"");
    }

    @Test
    void customScriptIncludesClassesAndMeasurement() {
        Set<PathClass> classes = new LinkedHashSet<>();
        classes.add(PathClass.fromString("Tumor"));
        classes.add(PathClass.fromString("Stroma"));
        ClassFilter cf = ClassFilter.of(classes, true);
        MeasurementFilter mf = new MeasurementFilter("DAB: Cell: Mean", Comparator.LT, 0.25);
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .classFilter(cf)
                .measurementFilter(mf)
                .build();
        String script = WorkflowScriptBuilder.buildScript("MyClassifier", crit);

        assertThat(script).contains("source : \"CUSTOM\"");
        assertThat(script).contains("classes : [\"Tumor\", \"Stroma\", \"(unclassified)\"]");
        assertThat(script).contains("measurement : \"DAB: Cell: Mean\"");
        assertThat(script).contains("op : \"LT\"");
        assertThat(script).contains("value1 : 0.25");
        assertThat(script).doesNotContain("value2");
    }

    @Test
    void betweenIncludesValue2() {
        MeasurementFilter mf = new MeasurementFilter("x", Comparator.BETWEEN, 5.0, 10.0);
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .measurementFilter(mf)
                .build();
        String script = WorkflowScriptBuilder.buildScript("c", crit);
        assertThat(script).contains("op : \"BETWEEN\"");
        assertThat(script).contains("value1 : 5.0");
        assertThat(script).contains("value2 : 10.0");
    }

    @Test
    void preserveClassFlagAppears() {
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.ALL_COMPATIBLE)
                .preserveExistingClass(true)
                .build();
        String script = WorkflowScriptBuilder.buildScript("c", crit);
        assertThat(script).contains("preserveClass : true");
    }

    @Test
    void escapesQuotesAndBackslashesInClassifierName() {
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.ALL_COMPATIBLE)
                .build();
        String script = WorkflowScriptBuilder.buildScript("name with \" quote and \\ backslash", crit);
        assertThat(script).contains("\"name with \\\" quote and \\\\ backslash\"");
    }

    @Test
    void escapeHelperHandlesNewlines() {
        assertThat(WorkflowScriptBuilder.escape("line1\nline2")).isEqualTo("line1\\nline2");
    }

    @Test
    void scriptStartsWithImportAndEndsWithCloseParen() {
        GatingCriteria crit = GatingCriteria.builder().source(ObjectSourceMode.ALL_COMPATIBLE).build();
        String script = WorkflowScriptBuilder.buildScript("c", crit);
        assertThat(script).startsWith("import qupath.ext.gatedobjclassifier.scripting.GatedObjectClassifierScripts");
        assertThat(script.trim()).endsWith(")");
    }

    @Test
    void roundTripParseFromGroovyOptionsMatchesOriginal() {
        // Just sanity-check that the keys/values our builder emits are the same
        // ones the scripting facade understands. Real round-trip is exercised by
        // GatedObjectClassifierScripts.parseCriteria which is tested separately
        // when QuPath context is available.
        GatingCriteria crit = GatingCriteria.builder()
                .source(ObjectSourceMode.CUSTOM)
                .measurementFilter(new MeasurementFilter("x", Comparator.GT, 1.5))
                .preserveExistingClass(true)
                .build();
        String script = WorkflowScriptBuilder.buildScript("c", crit);
        assertThat(script).contains("source : \"CUSTOM\"");
        assertThat(script).contains("measurement : \"x\"");
        assertThat(script).contains("op : \"GT\"");
        assertThat(script).contains("value1 : 1.5");
        assertThat(script).contains("preserveClass : true");
    }
}
