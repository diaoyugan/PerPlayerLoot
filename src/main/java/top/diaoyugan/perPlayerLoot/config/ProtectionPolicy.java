package top.diaoyugan.perPlayerLoot.config;

/** Pure permission/config decisions shared by listeners and covered by unit tests. */
public final class ProtectionPolicy {
    private ProtectionPolicy() { }

    public static boolean canDestroyContainer(
        final PluginSettings.ContainerProtection settings, final boolean hasPermission
    ) {
        return !settings.protectDestruction() || settings.allowDestruction() || hasPermission;
    }

    public static boolean canDestroyFrame(
        final PluginSettings.FrameProtection settings, final boolean sneaking, final boolean hasPermission
    ) {
        return !settings.protectDestruction() || settings.allowDestruction()
            || (sneaking && (settings.allowSneakDestruction() || hasPermission));
    }

    public static boolean canDestroyBrushable(
        final PluginSettings.BrushableProtection settings, final boolean sneaking, final boolean hasPermission
    ) {
        return !settings.protectDestruction() || settings.allowDestruction() || hasPermission
            || (sneaking && settings.allowSneakDestruction());
    }

    public static boolean canMergeContainer(
        final PluginSettings.ContainerProtection settings, final boolean hasPermission
    ) {
        return !settings.protectMerging() || settings.allowMerging() || hasPermission;
    }
}
