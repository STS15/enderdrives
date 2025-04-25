package com.sts15.enderdrives.mixins;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

public class EnderDrivesMixinPlugin implements IMixinConfigPlugin {

    // Map of conditional mixin class → mod ID it depends on
    private static final Object2ObjectMap<String, String> MOD_MIXINS = new Object2ObjectOpenHashMap<>(
            new String[] {
                    "com.sts15.enderdrives.mixins.compat.TileExIOPortMixin"
            },
            new String[] {
                    "extendedae"
            },
            Object2ObjectOpenHashMap.DEFAULT_LOAD_FACTOR
    );

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Apply always-loaded mixins
        if (!MOD_MIXINS.containsKey(mixinClassName)) {
            return true;
        }
        // Only apply conditional mixin if the mod is loaded
        return isModLoaded(MOD_MIXINS.get(mixinClassName));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (mixinClassName.equals("com.sts15.enderdrives.mixins.compat.TileExIOPortMixin")) {
            System.out.println("[EnderDrives] Mixin applied to TileExIOPort successfully.");
        }
    }

    private static boolean isModLoaded(String modId) {
        if (ModList.get() == null) {
            return LoadingModList.get().getMods().stream()
                    .map(ModInfo::getModId)
                    .anyMatch(modId::equals);
        } else {
            return ModList.get().isLoaded(modId);
        }
    }
}
