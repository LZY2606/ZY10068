package gsb.model;

public record YearPoint(long coordinate, String display) {
    public YearPoint {
        display = coordinate == 0 ? "0" : (coordinate < 0 ? (-coordinate) + " BCE" : coordinate + " CE");
    }

    public static YearPoint of(long coordinate) {
        if (coordinate == 0) {
            throw new IllegalArgumentException("Year zero is not valid in astronomical BCE/CE conversion");
        }
        return new YearPoint(coordinate, coordinate < 0 ? (-coordinate) + " BCE" : coordinate + " CE");
    }

    public static long fromInput(String era, long magnitude) {
        if (magnitude <= 0) {
            throw new IllegalArgumentException("Year magnitude must be positive");
        }
        if (era == null) return magnitude;
        return switch (era.strip().toUpperCase()) {
            case "CE", "AD" -> magnitude;
            case "BCE", "BC" -> -magnitude;
            default -> throw new IllegalArgumentException("Era must be BCE or CE");
        };
    }

    public static String display(long coordinate) {
        return of(coordinate).display();
    }
}
