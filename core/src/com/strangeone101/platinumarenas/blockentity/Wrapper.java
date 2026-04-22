package com.strangeone101.platinumarenas.blockentity;

import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;

public interface Wrapper<T extends TileState, S> {

    byte[] write(S cache);

    S cache(T baseTileState);

    S read(byte[] bytes);

    T place(T baseTileState, S cache);

    Class<T> getTileClass();

    boolean isBlank(T tileState);

    default T cast(TileState state) {
        return (T) state;
    }

    static ItemStack readItemStack(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;

        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
