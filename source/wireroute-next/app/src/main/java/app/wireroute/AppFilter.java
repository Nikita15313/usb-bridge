package app.wireroute;

public enum AppFilter {
    ALL,
    VPN,
    DIRECT,
    RECOMMENDED,
    LINKS,
    SELECTED;

    boolean matches(boolean recommended, boolean linkHandler, boolean selected) {
        switch (this) {
            case VPN:
                return selected;
            case DIRECT:
                return !selected;
            case RECOMMENDED:
                return recommended;
            case LINKS:
                return linkHandler;
            case SELECTED:
                return selected;
            case ALL:
            default:
                return true;
        }
    }
}
