package top.diaoyugan.perPlayerLoot.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ProtectionPolicyTest {
    @Test
    void containerPermissionWorksWithoutSneaking() {
        var protectedSettings = new PluginSettings.ContainerProtection(true, false, true, false, true);
        assertFalse(ProtectionPolicy.canDestroyContainer(protectedSettings, false));
        assertTrue(ProtectionPolicy.canDestroyContainer(protectedSettings, true));
    }

    @Test
    void framePermissionStillRequiresSneaking() {
        var protectedSettings = new PluginSettings.FrameProtection(true, false, false, Set.of());
        assertFalse(ProtectionPolicy.canDestroyFrame(protectedSettings, false, true));
        assertTrue(ProtectionPolicy.canDestroyFrame(protectedSettings, true, true));
    }

    @Test
    void brushablePermissionDoesNotRequireSneaking() {
        var protectedSettings = new PluginSettings.BrushableProtection(true, false, false);
        assertFalse(ProtectionPolicy.canDestroyBrushable(protectedSettings, false, false));
        assertTrue(ProtectionPolicy.canDestroyBrushable(protectedSettings, false, true));
    }

    @Test
    void disabledProtectionsIgnoreAllowSwitchesAndPermissions() {
        assertTrue(ProtectionPolicy.canDestroyContainer(
            new PluginSettings.ContainerProtection(false, false, true, false, true), false
        ));
        assertTrue(ProtectionPolicy.canDestroyFrame(
            new PluginSettings.FrameProtection(false, false, false, Set.of()), false, false
        ));
        assertTrue(ProtectionPolicy.canDestroyBrushable(
            new PluginSettings.BrushableProtection(false, false, false), false, false
        ));
    }
}
