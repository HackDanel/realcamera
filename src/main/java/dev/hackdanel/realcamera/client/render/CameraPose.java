package dev.hackdanel.realcamera.client.render;

import dev.hackdanel.realcamera.client.camera.Viewfinder;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;

/**
 * How a camera is held. Third person: raised to the eye with both hands, elbows out, following where the head
 * looks. First person: brought up from the hip to the eye over {@link Viewfinder#RAISE_TICKS} before the viewfinder
 * takes over, and lowered the same way.
 */
public final class CameraPose {
    /** The two-handed "camera at the eye" arm pose (added to {@link HumanoidModel.ArmPose} by enum extension). */
    @SuppressWarnings("unused") // referenced from META-INF/enumextensions.json
    public static final EnumProxy<HumanoidModel.ArmPose> ARM_POSE = new EnumProxy<>(HumanoidModel.ArmPose.class, true,
            (IArmPoseTransformer) CameraPose::poseArms);

    /** A player holding a raised camera is being drawn (between RenderPlayerEvent.Pre and Post). */
    private static boolean raised;

    private CameraPose() {}

    public static boolean raisedNow() {
        return raised;
    }

    public static void onRenderPlayerPre(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        var p = event.getEntity();
        raised = p.isUsingItem() && dev.hackdanel.realcamera.camera.CameraHelper.isCamera(p.getUseItem());
    }

    public static void onRenderPlayerPost(net.neoforged.neoforge.client.event.RenderPlayerEvent.Post event) {
        raised = false;
    }

    /** Undo the arm's raise (relative to the hanging pose the item's display transform assumes), in item space. */
    public static void counterRotate(com.mojang.blaze3d.vertex.PoseStack pose, boolean leftHand) {
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(RAISE_DEG));
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(leftHand ? -INWARD_DEG : INWARD_DEG));
        // centre it on the face rather than on the gripping hand
        pose.translate(leftHand ? -SHIFT : SHIFT, 0, 0);
    }

    private static final float RAISE_DEG = -95f, INWARD_DEG = -32f, SHIFT = 0.12f;

    /** Both hands at the face: arms pointing forward and inward, tilted with the head. */
    private static void poseArms(HumanoidModel<?> model, LivingEntity entity, HumanoidArm arm) {
        float pitch = model.head.xRot, yaw = model.head.yRot;
        // the hand holding the grip a little lower and further out than the one under the lens
        boolean rightMain = entity.getMainArm() == HumanoidArm.RIGHT;
        float up = -Mth.HALF_PI - 0.35f;
        model.rightArm.xRot = pitch + up + (rightMain ? 0.05f : -0.05f);
        model.leftArm.xRot = pitch + up + (rightMain ? -0.05f : 0.05f);
        model.rightArm.yRot = yaw - 0.62f;
        model.leftArm.yRot = yaw + 0.62f;
        model.rightArm.zRot = 0;
        model.leftArm.zRot = 0;
    }
}
