package qupath.ext.gatedobjclassifier.core;

/**
 * Numeric comparison operators used by the measurement filter.
 *
 * <p>All symbols are ASCII so they survive Windows cp1252 encoding in logs and
 * recorded workflow scripts.</p>
 */
public enum Comparator {

    LT("<", "less than"),
    LE("<=", "less than or equal to"),
    GT(">", "greater than"),
    GE(">=", "greater than or equal to"),
    EQ("==", "equal to"),
    NE("!=", "not equal to"),
    BETWEEN("between", "between (inclusive)");

    private final String symbol;
    private final String label;

    Comparator(String symbol, String label) {
        this.symbol = symbol;
        this.label = label;
    }

    public String symbol() {
        return symbol;
    }

    public String label() {
        return label;
    }

    public boolean usesSecondValue() {
        return this == BETWEEN;
    }

    /**
     * Test the comparison against {@code value}. {@code b} is only used when
     * {@link #usesSecondValue()} is true; for {@code BETWEEN} the range is
     * inclusive and the smaller of {@code a, b} is taken as the lower bound.
     */
    public boolean test(double value, double a, double b) {
        if (Double.isNaN(value)) {
            return false;
        }
        switch (this) {
            case LT: return value < a;
            case LE: return value <= a;
            case GT: return value > a;
            case GE: return value >= a;
            case EQ: return value == a;
            case NE: return value != a;
            case BETWEEN: {
                double lo = Math.min(a, b);
                double hi = Math.max(a, b);
                return value >= lo && value <= hi;
            }
            default:
                throw new IllegalStateException("Unhandled comparator: " + this);
        }
    }

    @Override
    public String toString() {
        return label;
    }
}
