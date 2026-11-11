package app.wireroute;

public enum RoutingMode {
    ONLY_SELECTED,
    BYPASS_SELECTED;

    static RoutingMode fromStored(String value) {
        try {
            return value == null ? ONLY_SELECTED : valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ONLY_SELECTED;
        }
    }
}
