package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * User-facing camera state stored on the camera item (survives being dropped or traded, like a real
 * camera remembers its dials). Index fields point into {@link dev.hackdanel.realcamera.optics.ExposureScales}.
 *
 * @param isoIndex      -1 = Auto ISO
 * @param evComp        exposure compensation in 1/3 EV
 * @param kelvin        used when whiteBalance == WB_KELVIN
 * @param focusDistance metres (manual focus / last AF result)
 * @param focal         current focal length of a zoom lens in mm, 0 = lens minimum
 */
public record CameraSettings(int mode, int shutterIndex, int apertureIndex, int isoIndex, int evComp,
                             int whiteBalance, int kelvin, int metering, int focusMode, float focusDistance,
                             float focal, int quality, int profile, int disp, int selectedParam) {
    public static final int MODE_AUTO = 0, MODE_P = 1, MODE_A = 2, MODE_S = 3, MODE_M = 4, MODE_COUNT = 5;
    public static final int FOCUS_AFS = 0, FOCUS_AFC = 1, FOCUS_MF = 2, FOCUS_COUNT = 3;
    public static final int METER_MATRIX = 0, METER_CENTER = 1, METER_SPOT = 2, METER_COUNT = 3;
    public static final int WB_AUTO = 0, WB_DAYLIGHT = 1, WB_CLOUDY = 2, WB_SHADE = 3, WB_TUNGSTEN = 4,
            WB_FLUORESCENT = 5, WB_FLASH = 6, WB_KELVIN = 7, WB_COUNT = 8;
    public static final int QUALITY_FINE = 0, QUALITY_NORMAL = 1, QUALITY_COUNT = 2;
    public static final int PARAM_SHUTTER = 0, PARAM_APERTURE = 1, PARAM_ISO = 2, PARAM_EV = 3, PARAM_WB = 4,
            PARAM_COUNT = 5;
    public static final int DISP_FULL = 0, DISP_HISTOGRAM = 1, DISP_BASIC = 2, DISP_CLEAN = 3, DISP_COUNT = 4;

    public static final CameraSettings DEFAULT = new CameraSettings(MODE_P, 24, 12, -1, 0, WB_AUTO, 5500,
            METER_MATRIX, FOCUS_AFS, 3f, 0f, QUALITY_FINE, 0, DISP_FULL, PARAM_SHUTTER);

    public static final Codec<CameraSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("mode").forGetter(CameraSettings::mode),
            Codec.INT.fieldOf("shutter").forGetter(CameraSettings::shutterIndex),
            Codec.INT.fieldOf("aperture").forGetter(CameraSettings::apertureIndex),
            Codec.INT.fieldOf("iso").forGetter(CameraSettings::isoIndex),
            Codec.INT.fieldOf("ev_comp").forGetter(CameraSettings::evComp),
            Codec.INT.fieldOf("wb").forGetter(CameraSettings::whiteBalance),
            Codec.INT.fieldOf("kelvin").forGetter(CameraSettings::kelvin),
            Codec.INT.fieldOf("metering").forGetter(CameraSettings::metering),
            Codec.INT.fieldOf("focus_mode").forGetter(CameraSettings::focusMode),
            Codec.FLOAT.fieldOf("focus_distance").forGetter(CameraSettings::focusDistance),
            Codec.FLOAT.fieldOf("focal").forGetter(CameraSettings::focal),
            Codec.INT.fieldOf("quality").forGetter(CameraSettings::quality),
            Codec.INT.fieldOf("profile").forGetter(CameraSettings::profile),
            Codec.INT.fieldOf("disp").forGetter(CameraSettings::disp),
            Codec.INT.fieldOf("selected_param").forGetter(CameraSettings::selectedParam)
    ).apply(i, CameraSettings::new));

    public static final StreamCodec<ByteBuf, CameraSettings> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public CameraSettings decode(ByteBuf buf) {
            return new CameraSettings(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), buf.readInt(), buf.readInt(), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), buf.readFloat(), buf.readFloat(),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, CameraSettings s) {
            ByteBufCodecs.VAR_INT.encode(buf, s.mode);
            ByteBufCodecs.VAR_INT.encode(buf, s.shutterIndex);
            ByteBufCodecs.VAR_INT.encode(buf, s.apertureIndex);
            buf.writeInt(s.isoIndex);
            buf.writeInt(s.evComp);
            ByteBufCodecs.VAR_INT.encode(buf, s.whiteBalance);
            ByteBufCodecs.VAR_INT.encode(buf, s.kelvin);
            ByteBufCodecs.VAR_INT.encode(buf, s.metering);
            ByteBufCodecs.VAR_INT.encode(buf, s.focusMode);
            buf.writeFloat(s.focusDistance);
            buf.writeFloat(s.focal);
            ByteBufCodecs.VAR_INT.encode(buf, s.quality);
            ByteBufCodecs.VAR_INT.encode(buf, s.profile);
            ByteBufCodecs.VAR_INT.encode(buf, s.disp);
            ByteBufCodecs.VAR_INT.encode(buf, s.selectedParam);
        }
    };

    public CameraSettings withMode(int v) {
        return new CameraSettings(v, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withShutter(int v) {
        return new CameraSettings(mode, v, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withAperture(int v) {
        return new CameraSettings(mode, shutterIndex, v, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withIso(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, v, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withEvComp(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, v, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withWhiteBalance(int wb, int k) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, wb, k, metering,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withMetering(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, v,
                focusMode, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withFocusMode(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                v, focusDistance, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withFocusDistance(float v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, v, focal, quality, profile, disp, selectedParam);
    }

    public CameraSettings withFocal(float v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, v, quality, profile, disp, selectedParam);
    }

    public CameraSettings withQuality(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, v, profile, disp, selectedParam);
    }

    public CameraSettings withProfile(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, v, disp, selectedParam);
    }

    public CameraSettings withDisp(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, v, selectedParam);
    }

    public CameraSettings withSelectedParam(int v) {
        return new CameraSettings(mode, shutterIndex, apertureIndex, isoIndex, evComp, whiteBalance, kelvin, metering,
                focusMode, focusDistance, focal, quality, profile, disp, v);
    }
}
