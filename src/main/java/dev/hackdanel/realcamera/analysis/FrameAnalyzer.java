package dev.hackdanel.realcamera.analysis;

import dev.hackdanel.realcamera.camera.OpticalChain;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.Optics;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side frame analysis (plan §7.1). What is in the picture (subjects, sky, sun and moon, biome, phenomena)
 * comes from the server's own world state at the moment of the release: the photographer's (or tripod's) eye and
 * rotation and the lens's field of view. The camera settings the client reports (focal length, f-number, shutter,
 * ISO, focus distance) are clamped to what the camera and lens can do; the exposure error and the motion blur are
 * the client's own measurements and are taken as reported (bounded), so a modified client could improve its stars.
 */
public final class FrameAnalyzer {
    private static final double RANGE = 256;

    private FrameAnalyzer() {}

    /** Camera frame: position, orthonormal basis and half-field tangents. */
    private record Frame(Vec3 eye, Vec3 fw, Vec3 right, Vec3 up, double tanH, double tanV) {
        /** Frame coordinates (-1..1, y up) and depth of a point; null if behind the camera. */
        double[] project(Vec3 p) {
            Vec3 d = p.subtract(eye);
            double z = d.dot(fw);
            if (z <= 0.05) return null;
            return new double[]{d.dot(right) / z / tanH, d.dot(up) / z / tanV, z};
        }

        Vec3 ray(double x, double y) {
            return fw.add(right.scale(x * tanH)).add(up.scale(y * tanV)).normalize();
        }
    }

    /** The reported settings, limited to what this camera and lens can actually do. */
    static Payloads.ShotInfo checked(Payloads.ShotInfo info, OpticalChain chain) {
        var body = chain.body();
        float focal = info.focal() > 0 ? chain.focal(info.focal()) : info.focal();
        float wide = chain.maxApertureAt(focal > 0 ? focal : chain.lens().focalMin());
        float n = Math.max(wide * 0.97f, Math.min(chain.minAperture() * 1.03f, info.fNumber()));
        float fastest = Math.min(body.shutter().hasMechanical() ? body.shutter().mechMin() : 1, body.shutter().elecMin() > 0
                ? body.shutter().elecMin() : 1);
        float shutter = info.shutter() < 0 ? info.shutter() : Math.max(fastest * 0.9f, Math.min(3600, info.shutter()));
        var sn = chain.sensor();
        int iso = body.isFilm() ? Math.max(6, Math.min(25600, info.iso())) : Math.max(sn.isoMin(), Math.min(sn.isoMax(), info.iso()));
        float focus = Math.max(chain.lens().minFocusM() * 0.95f, Math.min(10_000, info.focusM()));
        float ev = Math.max(-10, Math.min(10, info.exposureEv()));
        float blur = Math.max(0, Math.min(1, info.blur()));
        return new Payloads.ShotInfo(info.nonce(), focal, n, shutter, iso, focus, ev, blur);
    }

    private static Frame frame(Entity viewer, OpticalChain chain, CameraSettings settings, Payloads.ShotInfo info) {
        float focal = info.focal() > 0 ? info.focal() : chain.focal(settings.focal());
        double vfov = chain.renderVfovDeg(focal, settings, Math.max(0.05, info.focusM()));
        double aspect = chain.sensor().aspect();
        double tanV = Math.tan(Math.toRadians(vfov) / 2), tanH = tanV * aspect;
        float pitch = viewer.getXRot() - (float) chain.shiftPitchDeg(focal, settings);
        Vec3 fw = Vec3.directionFromRotation(pitch, viewer.getYRot());
        Vec3 worldUp = Math.abs(fw.y) > 0.999 ? new Vec3(0, 0, 1) : new Vec3(0, 1, 0);
        Vec3 right = fw.cross(worldUp).normalize();
        Vec3 up = right.cross(fw).normalize();
        return new Frame(viewer.getEyePosition(), fw, right, up, tanH, tanV);
    }

    private static boolean boltInFrame(Frame frame, Vec3 boltPos) {
        double[] c = frame.project(boltPos.add(0, 30, 0));
        return c != null && Math.abs(c[0]) < 1.2 && Math.abs(c[1]) < 1.6;
    }

    /** For bolts striking while the shutter is still open (a long exposure): would this one show in the frame? */
    public static java.util.function.Predicate<Vec3> lightningTest(Entity viewer, OpticalChain chain, CameraSettings settings,
                                                                  Payloads.ShotInfo info) {
        Frame frame = frame(viewer, chain, settings, checked(info, chain));
        return pos -> boltInFrame(frame, pos);
    }

    public static PhotoMeta.Analysis analyze(ServerLevel level, Entity viewer, Entity photographer, OpticalChain chain,
                                             CameraSettings settings, Payloads.ShotInfo info, boolean electronic,
                                             boolean flash, boolean tripod) {
        info = checked(info, chain);
        float focal = info.focal() > 0 ? info.focal() : chain.focal(settings.focal());
        Frame frame = frame(viewer, chain, settings, info);
        Vec3 fw = frame.fw();
        dev.hackdanel.realcamera.RealCamera.LOGGER.debug("[analysis] focal {} f/{} focus {} eye {} yaw {} pitch {}",
                focal, info.fNumber(), info.focusM(), frame.eye(), viewer.getYRot(), viewer.getXRot());

        // ---------------------------------------------------------------- subjects
        double coc = Optics.FF_COC_MM * chain.sensor().diagonalMm() / 43.27;
        double[] dof = Optics.depthOfFieldM(focal, Math.max(0.7, info.fNumber()), Math.max(0.05, info.focusM()), coc);
        List<PhotoMeta.Subject> subjects = new ArrayList<>();
        boolean lightning = false;
        AABB around = new AABB(frame.eye(), frame.eye()).inflate(RANGE);
        for (Entity e : level.getEntities(viewer, around, e -> e.isAlive() && !e.isSpectator())) {
            if (e instanceof LightningBolt) {
                if (boltInFrame(frame, e.position())) lightning = true;
                continue;
            }
            if (!(e instanceof LivingEntity) && !(e instanceof EnderDragonPart)) continue;
            if (e instanceof EnderDragonPart) continue; // the dragon itself carries the whole body box
            if (e.isInvisible()) continue;
            // only what the photographer's client draws: entities are sent within their tracking range and drawn
            // within 64 blocks x their size x the entity distance (the capture raises it to 4x for long lenses)
            double far = Math.min(e.getType().clientTrackingRange() * 16.0,
                    64 * 4 * Math.max(1, e.getBoundingBox().getSize()));
            if (e.distanceTo(viewer) > far) continue;
            PhotoMeta.Subject s = subject(level, viewer, frame, e, dof);
            if (s != null) subjects.add(s);
        }
        subjects.sort(Comparator.comparingDouble(PhotoMeta.Subject::area).reversed());
        if (subjects.size() > 8) subjects = new ArrayList<>(subjects.subList(0, 8));

        // ---------------------------------------------------------------- sky, sun and moon
        boolean skyWorld = level.dimensionType().hasSkyLight() || level.dimension() == Level.END;
        int skyRays = 0, total = 0;
        if (skyWorld) {
            for (int gy = 0; gy < 8; gy++) {
                for (int gx = 0; gx < 12; gx++) {
                    double x = (gx + 0.5) / 12 * 2 - 1, y = (gy + 0.5) / 8 * 2 - 1;
                    Vec3 dir = frame.ray(x, y);
                    total++;
                    if (dir.y < -0.02) continue;
                    HitResult hit = level.clip(new ClipContext(frame.eye(), frame.eye().add(dir.scale(RANGE)),
                            ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
                    if (hit.getType() == HitResult.Type.MISS) skyRays++;
                }
            }
        }
        float sky = total == 0 ? 0 : (float) skyRays / total;
        int dayTime = (int) (level.getDayTime() % 24000L);
        boolean overworldSky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        double a = level.getSunAngle(1f);
        Vec3 sunDir = new Vec3(-Math.sin(a), Math.cos(a), 0).normalize();
        boolean sun = overworldSky && level.getRainLevel(1f) < 0.5f && inFrameAndClear(level, viewer, frame, sunDir);
        boolean moon = overworldSky && level.getRainLevel(1f) < 0.5f && inFrameAndClear(level, viewer, frame, sunDir.scale(-1));
        int moonPhase = level.getMoonPhase();
        int weather = level.isThundering() ? 2 : level.isRaining() ? 1 : 0;

        // ---------------------------------------------------------------- the focus point
        Vec3 focusPoint = frame.eye().add(fw.scale(Math.min(RANGE, Math.max(0.3, info.focusM()))));
        HitResult centre = level.clip(new ClipContext(frame.eye(), frame.eye().add(fw.scale(RANGE)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, viewer));
        if (centre.getType() != HitResult.Type.MISS) focusPoint = centre.getLocation();
        String structure = structureAt(level, BlockPos.containing(focusPoint));
        // the biome the picture shows: at the focus point, and only with some sky or depth (not the ground at one's feet)
        double centreDist = centre.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY
                : centre.getLocation().distanceTo(frame.eye());
        String biome = sky >= 0.05f || centreDist >= 8 ? level.getBiome(BlockPos.containing(
                centre.getType() == HitResult.Type.MISS ? frame.eye().add(fw.scale(48)) : focusPoint))
                .unwrapKey().map(k -> k.location().toString()).orElse("") : PhotoMeta.NO_BIOME;
        // a landscape is sharp where the frame's middle is (or everything far is, focused at/near the hyperfocal)
        boolean sceneInFocus = centreDist >= dof[0] && centreDist <= dof[1];

        // ---------------------------------------------------------------- how it was taken
        boolean underwater = viewer.isEyeInFluid(FluidTags.WATER);
        int flags = 0;
        if (tripod) flags |= PhotoMeta.Analysis.FLAG_TRIPOD;
        if (chain.body().isFilm()) flags |= PhotoMeta.Analysis.FLAG_FILM;
        if (flash) flags |= PhotoMeta.Analysis.FLAG_FLASH;
        if (electronic) flags |= PhotoMeta.Analysis.FLAG_ELECTRONIC;
        if (chain.lens().optics().fisheye()) flags |= PhotoMeta.Analysis.FLAG_FISHEYE;
        if (chain.tiltShift().isPresent() && (chain.tiltDeg(settings) != 0 || chain.shiftMm(settings) != 0)) {
            flags |= PhotoMeta.Analysis.FLAG_TILT_SHIFT;
        }
        if (chain.adapter() != null) flags |= PhotoMeta.Analysis.FLAG_ADAPTER;
        if (chain.sensor().widthMm() > 40) flags |= PhotoMeta.Analysis.FLAG_MEDIUM_FORMAT;
        if (underwater) flags |= PhotoMeta.Analysis.FLAG_UNDERWATER;
        double mag = chain.magnification(Math.max(0.05, info.focusM()));

        // ---------------------------------------------------------------- natural phenomena
        List<String> phenomena = new ArrayList<>();
        boolean night = dayTime >= 13000 && dayTime < 23000;
        if (overworldSky) {
            boolean lowSun = sunDir.y > -0.12 && sunDir.y < 0.3 && sunDir.dot(fw) > 0.3 && sky > 0.1;
            if (lowSun && dayTime >= 11000 && dayTime < 13800) phenomena.add("sunset");
            if (lowSun && (dayTime >= 22200 || dayTime < 1000)) phenomena.add("sunrise");
            if (moon && moonPhase == 0) phenomena.add("full_moon");
            if (night && weather == 0 && sky >= 0.3f) phenomena.add("starry_night");
            BlockPos at = BlockPos.containing(frame.eye());
            boolean outside = level.canSeeSky(at) || sky > 0.1f;
            if (weather > 0 && outside) {
                Biome.Precipitation p = level.getBiome(at).value().getPrecipitationAt(at);
                if (p == Biome.Precipitation.SNOW) phenomena.add("snowfall");
                else if (p == Biome.Precipitation.RAIN) phenomena.add(weather == 2 ? "thunderstorm" : "rain");
            }
        }
        if (lightning) phenomena.add("lightning");
        if (level.dimension() == Level.END && sky >= 0.3f) phenomena.add("end_sky");
        if (underwater) phenomena.add("underwater");

        // ---------------------------------------------------------------- quality
        List<PhotoMeta.Subject> found = subjects;
        var main = found.stream().filter(PhotoMeta.Subject::inFocus).findFirst().or(() -> found.stream().findFirst());
        double isoStops = Math.log(Math.max(1, info.iso()) / (double) Math.max(1, chain.sensor().baseIso())) / Math.log(2);
        int quality = QualityScore.score(main.isPresent(), main.map(PhotoMeta.Subject::area).orElse(0f),
                main.map(PhotoMeta.Subject::x).orElse(0f), main.map(PhotoMeta.Subject::y).orElse(0f),
                main.map(PhotoMeta.Subject::inFocus).orElse(false), info.blur(), info.exposureEv(), isoStops, sky,
                !phenomena.isEmpty(), sceneInFocus);
        return new PhotoMeta.Analysis(subjects, structure, sky, sun, moon, moonPhase, dayTime, weather, phenomena, flags,
                (float) mag, quality, QualityScore.stars(quality), biome);
    }

    /**
     * Completes the analysis when the picture arrives: things only known after the exposure (the real length of a
     * bulb exposure for star trails).
     */
    public static PhotoMeta.Analysis complete(PhotoMeta.Analysis a, PhotoMeta.Exif exif) {
        boolean night = a.timeOfDay() >= 13000 && a.timeOfDay() < 23000;
        if (night && a.sky() >= 0.2f && exif.shutter() >= 15 && a.has(PhotoMeta.Analysis.FLAG_TRIPOD)
                && !a.phenomena().contains("star_trails")) {
            List<String> p = new ArrayList<>(a.phenomena());
            p.add("star_trails");
            return a.withPhenomena(p);
        }
        return a;
    }

    private static PhotoMeta.Subject subject(ServerLevel level, Entity viewer, Frame frame, Entity e, double[] dof) {
        AABB box = e.getBoundingBox();
        double[] c = frame.project(box.getCenter());
        if (c == null) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ);
            double[] p = frame.project(corner);
            if (p == null) {
                // a corner behind the camera: the box fills that side of the frame
                p = new double[]{c[0] < 0 ? -10 : 10, c[1] < 0 ? -10 : 10, 0.05};
            }
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double w = Math.min(1, maxX) - Math.max(-1, minX), h = Math.min(1, maxY) - Math.max(-1, minY);
        if (w <= 0 || h <= 0) return null;
        double area = w * h / 4;
        // visible if a ray from the eye reaches the centre or one of four points on the box
        Vec3 centre = box.getCenter();
        Vec3[] targets = {centre, centre.add(0, box.getYsize() * 0.35, 0), centre.add(0, -box.getYsize() * 0.35, 0),
                centre.add(frame.right().scale(box.getXsize() * 0.35)), centre.add(frame.right().scale(-box.getXsize() * 0.35))};
        boolean visible = false;
        for (Vec3 t : targets) {
            HitResult hit = level.clip(new ClipContext(frame.eye(), t, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
            if (hit.getType() == HitResult.Type.MISS) {
                visible = true;
                break;
            }
        }
        if (!visible) return null;
        double dist = Math.max(0.05, box.clip(frame.eye(), centre).map(p -> p.distanceTo(frame.eye()))
                .orElse(frame.eye().distanceTo(centre)));
        if (box.contains(frame.eye())) dist = 0.05;
        boolean inFocus = dist >= dof[0] * 0.98 && dist <= dof[1] * 1.02;
        double cx = (Math.max(-1, minX) + Math.min(1, maxX)) / 2, cy = (Math.max(-1, minY) + Math.min(1, maxY)) / 2;
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return new PhotoMeta.Subject(type, (float) Math.min(1, area), inFocus, (float) dist, (float) cx, (float) cy);
    }

    private static boolean inFrameAndClear(ServerLevel level, Entity viewer, Frame frame, Vec3 dir) {
        double[] p = frame.project(frame.eye().add(dir));
        if (p == null || Math.abs(p[0]) > 1 || Math.abs(p[1]) > 1 || dir.y < -0.05) return false;
        HitResult hit = level.clip(new ClipContext(frame.eye(), frame.eye().add(dir.scale(RANGE)), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static String structureAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return "";
        var start = level.structureManager().getStructureWithPieceAt(pos, s -> true);
        if (start == null || !start.isValid()) return "";
        var key = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
        return key == null ? "" : key.toString();
    }
}
