package dev.secdocs.embed;

import java.util.List;

public final class VectorSql {

    private VectorSql() {
    }

    public static String literal(List<Double> values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(values.get(i));
        }
        return result.append(']').toString();
    }
}
