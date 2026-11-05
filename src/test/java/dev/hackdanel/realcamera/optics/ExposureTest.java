package dev.hackdanel.realcamera.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExposureTest {
    private static final Exposure.Limits LENS_F18 = new Exposure.Limits(
            ExposureScales.nearestAperture(1.8), ExposureScales.nearestAperture(22),
            ExposureScales.nearestShutter(1 / 8000.0), ExposureScales.nearestShutter(30),
            ExposureScales.nearestIso(100), ExposureScales.nearestIso(51200), ExposureScales.nearestIso(12800),
            1 / 85.0);

    @Test
    void scalesHaveConsistentLength() {
        assertEquals(ExposureScales.SHUTTER.length, ExposureScales.SHUTTER_LABEL.length);
        assertEquals(61, ExposureScales.SHUTTER.length);
        assertEquals(31, ExposureScales.APERTURE.length);
        assertEquals("1/125", ExposureScales.SHUTTER_LABEL[ExposureScales.nearestShutter(1 / 125.0)]);
        assertEquals("16", ExposureScales.APERTURE_LABEL[ExposureScales.nearestAperture(16)]);
        assertEquals(100, ExposureScales.ISO[ExposureScales.nearestIso(100)]);
    }

    @Test
    void nominalValuesForExif() {
        assertEquals(1 / 8000.0, ExposureScales.nominalShutter(ExposureScales.nearestShutter(1 / 8000.0)), 1e-12);
        assertEquals(30, ExposureScales.nominalShutter(ExposureScales.nearestShutter(30)), 1e-9);
        assertEquals(0.3, ExposureScales.nominalShutter(ExposureScales.nearestShutter(0.3)), 1e-9);
        assertEquals(1.8, ExposureScales.nominalAperture(ExposureScales.nearestAperture(1.8)), 1e-9);
    }

    @Test
    void sunny16() {
        // f/16, 1/125 s, ISO 100 is about EV 15
        assertEquals(15, Exposure.cameraEv(16, 1 / 125.0, 100), 0.05);
        // doubling ISO lowers the scene EV the settings suit by one stop
        assertEquals(14, Exposure.cameraEv(16, 1 / 125.0, 200), 0.05);
    }

    @Test
    void programHitsTargetInDaylight() {
        Exposure.Result r = Exposure.solve(Exposure.MODE_P, 14, 0, 0, 0, -1, LENS_F18);
        assertEquals(0, r.delta(), 0.2);
        assertEquals(100, ExposureScales.ISO[r.isoIdx()]);
    }

    @Test
    void exposureCompensationShowsUpAsDelta() {
        Exposure.Result r = Exposure.solve(Exposure.MODE_P, 13, 1, 0, 0, -1, LENS_F18);
        assertEquals(1, r.delta(), 0.2);
    }

    @Test
    void apertureModeKeepsAperture() {
        int f28 = ExposureScales.nearestAperture(2.8);
        Exposure.Result r = Exposure.solve(Exposure.MODE_A, 12, 0, f28, 0, -1, LENS_F18);
        assertEquals(f28, r.apertureIdx());
        assertEquals(0, r.delta(), 0.2);
    }

    @Test
    void autoIsoRisesInTheDark() {
        // EV 4 (dim interior): needs high ISO to stay at a hand-holdable speed
        Exposure.Result r = Exposure.solve(Exposure.MODE_A, 4, 0, ExposureScales.nearestAperture(1.8), 0, -1, LENS_F18);
        assertTrue(r.iso() >= 800, "iso=" + r.iso());
        assertTrue(r.seconds() <= 1 / 60.0, "t=" + r.seconds());
    }

    @Test
    void manualModeReportsError() {
        // M at f/16 1/125 ISO 100 (EV 15) in a scene of EV 12 -> 3 stops under-exposed
        Exposure.Result r = Exposure.solve(Exposure.MODE_M, 12, 0, ExposureScales.nearestAperture(16),
                ExposureScales.nearestShutter(1 / 125.0), ExposureScales.nearestIso(100), LENS_F18);
        assertEquals(-3, r.delta(), 0.1);
    }

    @Test
    void shutterPriorityClampsAtWideOpen() {
        // very dark scene, fixed ISO 100, 1/1000: aperture cannot open past f/1.8 -> heavy under-exposure
        Exposure.Result r = Exposure.solve(Exposure.MODE_S, 5, 0, 0, ExposureScales.nearestShutter(1 / 1000.0),
                ExposureScales.nearestIso(100), LENS_F18);
        assertEquals(ExposureScales.nearestAperture(1.8), r.apertureIdx());
        assertTrue(r.delta() < -5);
    }
}
