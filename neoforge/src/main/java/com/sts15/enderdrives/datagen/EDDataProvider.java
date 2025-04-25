package com.sts15.enderdrives.datagen;

import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.*;

public class EDDataProvider implements DataProvider {

    private final List<DataProvider> subProviders = new ArrayList<>();

    public void addSubProvider(boolean include, DataProvider provider) {
        if (include) subProviders.add(provider);
    }

    @Override
    @NotNull
    public CompletableFuture<?> run(@NotNull CachedOutput cachedOutput) {
        List<CompletableFuture<?>> list = new ArrayList<>();
        for (DataProvider provider : subProviders) list.add(provider.run(cachedOutput));
        return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
    }

    @Override
    @NotNull
    public String getName() {
        return "Ender Drives Data Provider";
    }
}
