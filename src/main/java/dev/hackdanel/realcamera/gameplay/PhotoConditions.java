package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.Optional;

/** Condition checks shared by advancements and newspaper commissions. */
public final class PhotoConditions {
    private PhotoConditions() {}

    public static int flag(String name) {
        return switch (name) {
            case "tripod" -> PhotoMeta.Analysis.FLAG_TRIPOD;
            case "film" -> PhotoMeta.Analysis.FLAG_FILM;
            case "flash" -> PhotoMeta.Analysis.FLAG_FLASH;
            case "electronic" -> PhotoMeta.Analysis.FLAG_ELECTRONIC;
            case "fisheye" -> PhotoMeta.Analysis.FLAG_FISHEYE;
            case "tilt_shift" -> PhotoMeta.Analysis.FLAG_TILT_SHIFT;
            case "adapter" -> PhotoMeta.Analysis.FLAG_ADAPTER;
            case "medium_format" -> PhotoMeta.Analysis.FLAG_MEDIUM_FORMAT;
            case "underwater" -> PhotoMeta.Analysis.FLAG_UNDERWATER;
            default -> 0;
        };
    }

    public static boolean matches(PhotoMeta meta, Optional<String> entity, int minStars, Optional<String> phenomenon,
                                  Optional<String> biome, Optional<String> dimension, Optional<String> flag,
                                  RCTriggers.PhotoTrigger.Shot shot) {
        if (meta.analysis().isEmpty()) return false;
        PhotoMeta.Analysis a = meta.analysis().get();
        if (a.stars() < minStars) return false;
        if (phenomenon.isPresent() && !a.phenomena().contains(phenomenon.get())) return false;
        if (biome.isPresent() && !meta.place().biome().equals(biome.get())) return false;
        if (dimension.isPresent() && !meta.place().dimension().equals(dimension.get())) return false;
        if (flag.isPresent() && !a.has(flag(flag.get()))) return false;
        if (shot.minShutter() > 0 && meta.exif().shutter() < shot.minShutter()) return false;
        if (shot.maxAperture() > 0 && meta.exif().fNumber() > shot.maxAperture() + 0.01f) return false;
        if (shot.minMagnification() > 0 && a.magnification() < shot.minMagnification()) return false;
        if (shot.minSky() > 0 && a.sky() < shot.minSky()) return false;
        if (entity.isPresent() || shot.minDistance() > 0 || shot.maxDistance() > 0) {
            boolean any = a.subjects().stream().anyMatch(s -> (entity.isEmpty() || s.type().equals(entity.get()))
                    && s.inFocus() && s.area() >= 0.005f
                    && (shot.minDistance() <= 0 || s.distance() >= shot.minDistance())
                    && (shot.maxDistance() <= 0 || s.distance() <= shot.maxDistance()));
            if (!any) return false;
        }
        return true;
    }
}
