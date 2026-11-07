package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Camera bodies: the two-handed pose while raised (third person) and the raise to the eye (first person). */
public final class CameraItemExtensions implements IClientItemExtensions {
    public static final CameraItemExtensions INSTANCE = new CameraItemExtensions();

    private CameraItemExtensions() {}

    @Override
    public HumanoidModel.ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
        if (entity.isUsingItem() && entity.getUsedItemHand() == hand && CameraHelper.isCamera(entity.getUseItem())) {
            return CameraPose.ARM_POSE.getValue();
        }
        return null;
    }

    @Override
    public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack,
                                           float partialTick, float equipProcess, float swingProcess) {
        float t = Viewfinder.raiseProgress(player, stack, partialTick);
        if (t <= 0) return false; // held normally
        float s = t * t * (3 - 2 * t);
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        // from the usual held position (vanilla applyItemArmTransform) to just in front of the eye, centred
        float x = Mth.lerp(s, side * 0.56f, side * -0.1f);
        float y = Mth.lerp(s, -0.52f, -0.17f);
        float z = Mth.lerp(s, -0.72f, -0.32f);
        pose.translate(x, y, z);
        pose.mulPose(Axis.YP.rotationDegrees(side * 12 * s));
        pose.mulPose(Axis.XP.rotationDegrees(-8 * (1 - s) * s * 4));
        return true;
    }
}
