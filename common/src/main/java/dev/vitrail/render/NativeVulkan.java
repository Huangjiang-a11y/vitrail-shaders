package dev.vitrail.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The native Vulkan device behind whichever backend the game came up on.
 *
 * <p>Almost everything this mod draws goes through the render API the game exposes, which is the
 * same on either backend. What is not is the handful of places that reach past it: the storage
 * buffers and images a pack declares, the compute passes that fill them, and the depth sampler
 * shadow comparison needs. On the game's own Vulkan backend those read {@code VulkanDevice} and
 * its siblings directly, and that is what this mod was written against.
 *
 * <p>On LavaFlow the backend is {@code LavaFlowDevice}, which is not a {@code VulkanDevice}, so
 * every {@code instanceof} answered no and those paths retired while the engine as a whole was
 * already on: the translated programs declare uniform blocks and storage resources that nothing
 * fills, and the first draw dies on the first empty slot. This class answers the same questions
 * out of LavaFlow's handles, read through {@code dev.lavaflow.minecraft.vulkan.VitrailBridge}.
 *
 * <p>The bridge is reached by reflection, and by name, because LavaFlow is an optional mod: a
 * game without it has no bridge class, every method here then answers the official branch or
 * null, and the official backend behaves exactly as before. The names below are the contract;
 * see {@code VitrailBridge} on the LavaFlow side for the other half.
 */
public final class NativeVulkan {
    private static final String BRIDGE_CLASS = "dev.lavaflow.minecraft.vulkan.VitrailBridge";

    private static final Method IS_LAVA_FLOW = bridgeMethod("isLavaFlow", GpuDeviceBackend.class);
    private static final Method VK_DEVICE = bridgeMethod("vkDevice", GpuDeviceBackend.class);
    private static final Method COMMAND_BUFFER =
            bridgeMethod("commandBuffer", CommandEncoderBackend.class);
    private static final Method BUFFER_HANDLE = bridgeMethod("bufferHandle", GpuBuffer.class);
    private static final Method IMAGE_HANDLE = bridgeMethod("imageHandle", GpuTexture.class);
    private static final Method IMAGE_VIEW_HANDLE =
            bridgeMethod("imageViewHandle", GpuTextureView.class);
    private static final Method SAMPLER_HANDLE = bridgeMethod("samplerHandle", GpuSampler.class);
    private static final Method CREATE_BUFFER =
            bridgeMethod("createBuffer", GpuDeviceBackend.class, long.class, int.class);
    private static final Method DESTROY_BUFFER =
            bridgeMethod("destroyBuffer", GpuDeviceBackend.class, long.class, long.class);
    private static final Method CREATE_IMAGE =
            bridgeMethod("createImage", GpuDeviceBackend.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class, int.class);
    private static final Method DESTROY_IMAGE =
            bridgeMethod("destroyImage", GpuDeviceBackend.class, long.class, long.class);

    /** The game's own Vulkan backend, or null when this is LavaFlow's. */
    private final VulkanDevice official;

    /** LavaFlow's backend, or null when this is the game's own. */
    private final GpuDeviceBackend lavaflow;

    private NativeVulkan(VulkanDevice official, GpuDeviceBackend lavaflow) {
        this.official = official;
        this.lavaflow = lavaflow;
    }

    /**
     * The native device behind {@code backend}, or null when it is neither backend this knows.
     *
     * <p>A backend nobody here has heard of answers null rather than throwing: the caller's
     * existing null check then retires its path, which is what it already does for OpenGL.
     */
    public static NativeVulkan of(GpuDeviceBackend backend) {
        if (backend == null) {
            return null;
        }

        if (backend instanceof VulkanDevice official) {
            return new NativeVulkan(official, null);
        }

        return IS_LAVA_FLOW != null && (Boolean) invoke(IS_LAVA_FLOW, backend)
                ? new NativeVulkan(null, backend)
                : null;
    }

    /** The {@code VkDevice}, or null when the backend is neither. */
    public VkDevice vkDevice() {
        return this.official != null
                ? this.official.vkDevice()
                : (VkDevice) invoke(VK_DEVICE, this.lavaflow);
    }

    /**
     * The allocator a pack's storage resources are made through.
     *
     * <p>LavaFlow keeps no VMA allocator: it allocates through Vulkan itself. Its storage paths
     * do not go through here; see the callers, which fall back to the device's own buffer and
     * texture creation.
     */
    public long vma() {
        return this.official != null ? this.official.vma() : 0L;
    }

    /** The backend these handles were read from, which the allocator hands back. */
    public GpuDeviceBackend backend() {
        return this.lavaflow;
    }

    /**
     * Whether a pack's storage resources can be made through this backend.
     *
     * <p>Only LavaFlow's side can: the game's own backend keeps VMA and its storage paths
     * still ask {@link #vma()}. This fork serves LavaFlow, so a backend without
     * {@code createBuffer} retires the storage path instead.
     */
    public boolean canAllocate() {
        return this.lavaflow != null && CREATE_BUFFER != null;
    }

    /** {@code {VkBuffer, VkDeviceMemory}}, or null when this backend cannot make one. */
    public long[] createBuffer(long size, int usage) {
        return canAllocate() ? (long[]) invoke(CREATE_BUFFER, this.lavaflow, size, usage) : null;
    }

    /** Frees a buffer {@link #createBuffer} made. Both handles may be 0. */
    public void destroyBuffer(long buffer, long memory) {
        if (DESTROY_BUFFER != null) invoke(DESTROY_BUFFER, this.lavaflow, buffer, memory);
    }

    /** {@code {VkImage, VkDeviceMemory}}, or null when this backend cannot make one. */
    public long[] createImage(int format, int width, int height, int mipLevels, int arrayLayers,
            int samples, int tiling, int usage, int initialLayout) {
        return CREATE_IMAGE == null ? null
                : (long[]) invoke(CREATE_IMAGE, this.lavaflow, format, width, height, mipLevels,
                        arrayLayers, samples, tiling, usage, initialLayout);
    }

    /** Frees an image {@link #createImage} made. Both handles may be 0. */
    public void destroyImage(long image, long memory) {
        if (DESTROY_IMAGE != null) invoke(DESTROY_IMAGE, this.lavaflow, image, memory);
    }

    /** Whether the backend is the game's own Vulkan one. */
    public boolean official() {
        return this.official != null;
    }

    /**
     * Raises the failure the game's own backend raises, with the same message it would write.
     *
     * <p>On LavaFlow there is no {@code VulkanUtils} to hand the device to, so the message is the
     * one a bare result code carries.
     */
    public void crashIfFailure(int result, String message) {
        if (this.official != null) {
            VulkanUtils.crashIfFailure(this.official, result, message);
        } else if (result != 0) {
            throw new IllegalStateException("VkResult " + result + ": " + message);
        }
    }

    /** The command buffer {@code encoder} is recording into, or null when it is neither backend. */
    public static VkCommandBuffer commandBuffer(CommandEncoderBackend encoder) {
        return COMMAND_BUFFER == null ? null : (VkCommandBuffer) invoke(COMMAND_BUFFER, encoder);
    }

    /** The {@code VkBuffer} behind {@code buffer}, or 0 when it is not LavaFlow's. */
    public static long bufferHandle(GpuBuffer buffer) {
        return BUFFER_HANDLE == null ? 0L : (Long) invoke(BUFFER_HANDLE, buffer);
    }

    /** The {@code VkImage} behind {@code texture}, or 0 when it is not LavaFlow's. */
    public static long imageHandle(GpuTexture texture) {
        return IMAGE_HANDLE == null ? 0L : (Long) invoke(IMAGE_HANDLE, texture);
    }

    /** The {@code VkImageView} behind {@code view}, or 0 when it is not LavaFlow's. */
    public static long imageViewHandle(GpuTextureView view) {
        return IMAGE_VIEW_HANDLE == null ? 0L : (Long) invoke(IMAGE_VIEW_HANDLE, view);
    }

    /** The {@code VkSampler} behind {@code sampler}, or 0 when it is not LavaFlow's. */
    public static long samplerHandle(GpuSampler sampler) {
        return SAMPLER_HANDLE == null ? 0L : (Long) invoke(SAMPLER_HANDLE, sampler);
    }

    private static Object invoke(Method method, Object target, Object... args) {
        if (method == null || target == null) {
            return null;
        }

        try {
            Object[] all = new Object[args.length + 1];
            all[0] = target;
            System.arraycopy(args, 0, all, 1, args.length);
            return method.invoke(null, all);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Vitrail's LavaFlow bridge could not be read", failure);
        }
    }

    private static Method bridgeMethod(String name, Class<?>... parameters) {
        Class<?> bridge = bridgeClass();

        if (bridge == null) {
            return null;
        }

        try {
            return bridge.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            // A LavaFlow older than the method: answer null and let the caller retire its path.
            return null;
        }
    }

    private static Class<?> bridgeClass() {
        try {
            return Class.forName(BRIDGE_CLASS, false, NativeVulkan.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }
}
