package com.tunic.parallel.client;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Opens the operating system's "open file" dialog through SDL, which the game already uses for its window. */
public final class SkinFileDialog {

    private SkinFileDialog() {
    }

    /**
     * Asks the player for a file. {@code onPick} runs on the game thread with the chosen file; it is not
     * called if the dialog is cancelled or could not be shown.
     */
    public static void open(Consumer<Path> onPick) {
        Minecraft minecraft = Minecraft.getInstance();
        // The native callback has to stay alive until SDL has called it, so it is freed from inside.
        AtomicReference<SDL_DialogFileCallback> self = new AtomicReference<>();
        SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            String picked = null;
            if (fileList != 0L) {
                // A NULL-terminated array of UTF-8 paths; only the first is wanted.
                long first = MemoryUtil.memGetAddress(fileList);
                if (first != 0L) {
                    picked = MemoryUtil.memUTF8(first);
                }
            }
            String path = picked;
            minecraft.execute(() -> {
                SDL_DialogFileCallback done = self.getAndSet(null);
                if (done != null) {
                    done.free();
                }
                if (path != null) {
                    onPick.accept(Path.of(path));
                }
            });
        });
        self.set(callback);
        SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, minecraft.getWindow().handle(), null, (CharSequence) null, false);
    }
}
