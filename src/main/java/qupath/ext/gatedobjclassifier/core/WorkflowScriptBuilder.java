package qupath.ext.gatedobjclassifier.core;

import qupath.lib.objects.classes.PathClassTools;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowStep;

import java.util.List;

/**
 * Builds the Groovy {@link WorkflowStep} that the runner appends to the
 * image's history workflow when the user clicks Apply.
 *
 * <p>The script always calls
 * {@link qupath.ext.gatedobjclassifier.scripting.GatedObjectClassifierScripts#runGatedClassifier(String, java.util.Map)}
 * with a small map of options keyed by strings - that keeps the script short,
 * stable across versions, and easy to edit by hand.</p>
 */
public final class WorkflowScriptBuilder {

    public static final String STEP_NAME = "Apply gated object classifier";

    private WorkflowScriptBuilder() {}

    public static WorkflowStep build(String classifierName, GatingCriteria criteria) {
        return new DefaultScriptableWorkflowStep(STEP_NAME, buildScript(classifierName, criteria));
    }

    /** Visible for testing. */
    public static String buildScript(String classifierName, GatingCriteria criteria) {
        StringBuilder sb = new StringBuilder();
        sb.append("import qupath.ext.gatedobjclassifier.scripting.GatedObjectClassifierScripts\n\n");
        sb.append("GatedObjectClassifierScripts.runGatedClassifier(\n");
        sb.append("    \"").append(escape(classifierName)).append("\",\n");
        sb.append("    [\n");

        boolean first = true;
        first = appendEntry(sb, first, "source", quote(criteria.source().name()));

        ObjectSourceMode source = criteria.source();

        if (source == ObjectSourceMode.CUSTOM) {
            criteria.classFilter().ifPresent(cf -> {
                if (!cf.isAcceptAll()) {
                    appendEntry(sb, false, "classes", classesLiteral(cf));
                }
            });
            criteria.measurementFilter().ifPresent(mf -> {
                appendEntry(sb, false, "measurement", quote(mf.measurementName()));
                appendEntry(sb, false, "op", quote(mf.op().name()));
                appendEntry(sb, false, "value1", String.valueOf(mf.value1()));
                if (mf.op().usesSecondValue()) {
                    appendEntry(sb, false, "value2", String.valueOf(mf.value2()));
                }
            });
        }

        if (criteria.preserveExistingClass()) {
            appendEntry(sb, false, "preserveClass", "true");
        }

        // Strip trailing comma before closing
        int len = sb.length();
        if (len >= 2 && sb.charAt(len - 2) == ',' && sb.charAt(len - 1) == '\n') {
            sb.setLength(len - 2);
            sb.append('\n');
        }
        sb.append("    ]\n");
        sb.append(")\n");
        return sb.toString();
    }

    private static boolean appendEntry(StringBuilder sb, boolean isFirst, String key, String renderedValue) {
        sb.append("        ").append(key).append(" : ").append(renderedValue).append(",\n");
        return false;
    }

    /**
     * Render the {@code classes} list literal. Each PathClass is encoded as a
     * Groovy list of its component names (via {@link PathClassTools#splitNames})
     * so that classes whose own names contain ":" round-trip as a single atomic
     * class instead of being re-parsed as derived classes by
     * {@code PathClass.fromString}. The "(unclassified)" marker stays a String.
     */
    private static String classesLiteral(ClassFilter cf) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (var pc : cf.pathClasses()) {
            if (!first) sb.append(", ");
            first = false;
            List<String> components = PathClassTools.splitNames(pc);
            if (components.isEmpty()) {
                sb.append(quote(pc.toString()));
            } else {
                sb.append(stringListLiteral(components));
            }
        }
        if (cf.includesUnclassified()) {
            if (!first) sb.append(", ");
            sb.append(quote(ClassFilter.UNCLASSIFIED_LITERAL));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String stringListLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(v));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + escape(s) + "\"";
    }

    /**
     * Escape backslashes, double quotes and newlines so the generated Groovy
     * string literal stays valid.
     */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
