package net.townymap.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;
import net.townymap.TownyMapMod;
import net.townymap.gui.TownInfoOverlay;
import net.townymap.gui.TownSearchOverlay;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.TownData;
import org.objectweb.asm.Opcodes;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Injects into Xaero's GuiMap.
 *
 * Rendering order inside method_25394 (Screen.render):
 *   1. renderPreDropdown — waypoints, labels, town overlays (HEAD/RETURN inject here)
 *   2. Squaremap tile compositing (DrawContext flush in onBeforePlayerArrow)
 *   3. Xaero's player arrow (drawArrowOnMap via vertex buffers)
 *   4. method_25394 RETURN — our arrow re-draw fires here, guaranteed on top of tiles
 *
 * Town overlays and info UI render at renderPreDropdown HEAD (clean matrix state).
 * The player arrow MUST render at method_25394 RETURN so it lands after the
 * squaremap DrawContext flush, not before it.
 *
 * method_25402 = mouseClicked(Click, boolean) in MC 1.21.11.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class MixinGuiMap {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final AtomicBoolean MAP_SURFACE_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean RENDER_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean CLICK_ERROR_LOGGED = new AtomicBoolean(false);

    @Shadow(remap = false) private double cameraX;
    @Shadow(remap = false) private double cameraZ;
    @Shadow(remap = false) private double scale;
    @Shadow(remap = false) private double screenScale;

    // ── All overlay rendering at HEAD (clean GL/matrix state) ─────────────────

    @Inject(
            method = "method_25394",
            at = @At(
                    value = "FIELD",
                    target = "Lxaero/map/common/config/option/WorldMapProfiledConfigOptions;ARROW:Lxaero/lib/common/config/option/BooleanConfigOption;",
                    opcode = Opcodes.GETSTATIC,
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void onBeforePlayerArrow(DrawContext ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            TownyMapMod.renderSquaremapBackground(ctx, cameraX, cameraZ, guiScale, w, h);
            TownyMapMod.renderOnWorldMap(ctx, cameraX, cameraZ, guiScale, w, h);
            if (guiScale > 0) {
                double worldX = (mouseX - w / 2.0) / guiScale + cameraX;
                double worldZ = (mouseY - h / 2.0) / guiScale + cameraZ;
                TownyMapMod.renderHoveredWorldMapChunk(ctx, cameraX, cameraZ, guiScale, w, h, worldX, worldZ);
                TownyMapMod.renderChunkCounter(ctx, cameraX, cameraZ, guiScale, w, h, worldX, worldZ);
            }
            ctx.drawDeferredElements();
            clearDepthForXaeroArrowIfAvailable();
            disableDepthTestIfAvailable();
        } catch (Exception e) {
            logOnce(MAP_SURFACE_ERROR_LOGGED, "Failed to render world-map surface overlay", e);
        }
    }

    @Inject(method = "renderPreDropdown", at = @At("HEAD"), remap = false)
    private void onRenderPreDropdown(DrawContext ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            if (guiScale > 0) {
                double worldX = (mouseX - w / 2.0) / guiScale + cameraX;
                double worldZ = (mouseY - h / 2.0) / guiScale + cameraZ;
                TownyMapMod.renderTownHover(ctx, mouseX, mouseY, worldX, worldZ, w, h);
            }
            TownyMapMod.renderTownInfo(ctx, w, h);
            TownyMapMod.renderMapToggles(ctx, h);
            TownyMapMod.renderTownSearch(ctx, w, h);
        } catch (Exception e) {
            logOnce(RENDER_ERROR_LOGGED, "Failed to render Xaero world-map overlay", e);
        }
    }

    // Arrow drawn via DrawContext at method_25394 RETURN.
    //
    // Root cause of all previous failures: SquaremapTileRenderer calls
    // ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ...) which enqueues into
    // DrawContext's deferred batch. That batch is flushed once — at frame-end,
    // AFTER everything rendered via vertex buffers (CustomRenderTypes.GUI_BILINEAR).
    // Any vertex-buffer arrow always lands behind the squaremap tiles no matter the
    // injection point.
    //
    // Fix: queue the arrow through DrawContext too (ctx.fill() + matrix rotation),
    // after the squaremap tile draws. Both end up in the same deferred batch; the
    // arrow is queued last so it renders on top when the batch flushes at frame-end.
    @Inject(method = "method_25394", at = @At("RETURN"), remap = false)
    private void onRenderReturn(DrawContext ctx, int mouseX, int mouseY,
                                float delta, CallbackInfo ci) {
        try {
            if (!TownyMapMod.shouldRenderWorldMapIndicatorOverlay()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity player = mc.player;
            if (player == null || mc.world == null) return;

            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            if (guiScale <= 0) return;

            double dx = player.getX() - cameraX;
            double dz = player.getZ() - cameraZ;
            float sx = (float) (w / 2.0 + dx * guiScale);
            float sy = (float) (h / 2.0 + dz * guiScale);
            if (sx < -32 || sx > w + 32 || sy < -32 || sy > h + 32) return;

            float yawRad = (float) Math.toRadians(player.getYaw());
            Matrix3x2fStack m = ctx.getMatrices();

            // Replicate Xaero's own arrow sizing.
            // Xaero passes sc = scaleMultiplier / scale to drawObjectOnMap, which then
            // calls matrixStack.scale(sc, sc, 1) in a coordinate space where 1 unit =
            // 1 world block (their map matrix already encodes guiScale).  So the arrow
            // appears 26 * sc * guiScale = 26 * smult / screenScale GUI pixels wide —
            // constant regardless of zoom, only growing on HiDPI screens > 1080 px tall.
            int fwMin = Math.min(mc.getWindow().getFramebufferWidth(),
                                 mc.getWindow().getFramebufferHeight());
            double scaleMultiplier = fwMin <= 1080 ? 1.0 : fwMin / 1080.0;
            float arrowScale = (float) Math.max(0.2, Math.min(2.0,
                    scaleMultiplier / Math.max(1, screenScale)));
            float shadowOffset = 2f * arrowScale;

            // Shadow — offset south in pre-rotation space, then scale
            m.pushMatrix();
            m.translate(sx, sy + shadowOffset);
            m.rotate(yawRad);
            m.scale(arrowScale, arrowScale);
            drawXaeroArrowSprite(ctx, 0xE5000000);
            m.popMatrix();

            // Main arrow — Xaero colour: r=1, g=0.08, b=0.08, a=1
            m.pushMatrix();
            m.translate(sx, sy);
            m.rotate(yawRad);
            m.scale(arrowScale, arrowScale);
            drawXaeroArrowSprite(ctx, 0xFFFF1414);
            m.popMatrix();
        } catch (Exception e) {
            logOnce(RENDER_ERROR_LOGGED, "Failed to render world-map arrow overlay", e);
        }
    }

    /**
     * Draws Xaero's own arrow sprite (from assets/xaeroworldmap/gui/gui.png) via
     * DrawContext so it composites on top of the squaremap deferred tile batch.
     *
     * UV in the 256×256 sheet: origin (13, 5), size 26×28.
     * Xaero centers it by drawing at screen position (−13, −5) in local space.
     * The caller must have already applied the player-yaw rotation to the matrix.
     *
     * @param color ARGB tint — 0xFFFF1414 for the red arrow, 0xE5000000 for shadow.
     */
    private static final Identifier XAERO_GUI = Identifier.of("xaeroworldmap", "gui/gui.png");

    private static void drawXaeroArrowSprite(DrawContext ctx, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, XAERO_GUI,
                -13, -5,   // screen position (centers the 26-wide sprite at x=0)
                0f, 0f,    // UV start in the 256×256 sheet (sprite is at top-left)
                26, 28,    // sprite size
                256, 256,  // full texture size
                color);
    }

    // ── Mouse click ───────────────────────────────────────────────────────────

    @Inject(method = "method_25402", at = @At("HEAD"), remap = false, cancellable = true)
    private void onMouseClicked(Click click, boolean bl,
                                CallbackInfoReturnable<Boolean> cir) {
        try {
            int button = click.buttonInfo().button();
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();

            if (button == 0) {
                TownSearchOverlay.ClickResult result =
                        TownyMapMod.onTownSearchClick(click.x(), click.y(), sw, sh);
                if (result.consumed()) {
                    handleJumpOrRoute(result.target());
                    cir.setReturnValue(true);
                    return;
                }
            }

            if (button == 0) {
                TownInfoOverlay.ActionResult action = TownyMapMod.onTownInfoClick(click.x(), click.y());
                if (action.action() != TownInfoOverlay.Action.NONE) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            if (button == 0 && TownyMapMod.onSettingsButtonClick(click.x(), click.y(), sh)) {
                TownyMapMod.openConfigScreen();
                cir.setReturnValue(true);
                return;
            }

            if (button == 0 && TownyMapMod.onMapToggleClick(click.x(), click.y(), sh)) {
                cir.setReturnValue(true);
                return;
            }

            if (button == 1 && TownyMapMod.isChunkCounterActive()) {
                double guiScale = (screenScale > 0) ? scale / screenScale : scale;
                if (guiScale > 0) {
                    double worldX = (click.x() - sw / 2.0) / guiScale + cameraX;
                    double worldZ = (click.y() - sh / 2.0) / guiScale + cameraZ;
                    TownyMapMod.onChunkCounterClick(worldX, worldZ);
                }
                cir.setReturnValue(true);
                return;
            }

            if (button == 0) {
                TownyMapMod.dismissTownInfo();
                return;
            }
            if (button != 1) return;

            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            if (guiScale <= 0) return;

            double worldX = (click.x() - sw / 2.0) / guiScale + cameraX;
            double worldZ = (click.y() - sh / 2.0) / guiScale + cameraZ;

            TownyMapMod.onMapRightClick(worldX, worldZ, (int) click.x(), (int) click.y());
            cir.setReturnValue(true);
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map click", e);
        }
    }

    @Inject(method = "method_25404", at = @At("HEAD"), remap = false, cancellable = true)
    private void onKeyPressed(KeyInput input,
                              CallbackInfoReturnable<Boolean> cir) {
        try {
            TownSearchOverlay.ClickResult result = TownyMapMod.onTownSearchKeyPressed(input.key());
            if (result.consumed()) {
                jumpTo(result.target());
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map key press", e);
        }
    }

    @Inject(method = "method_25400", at = @At("HEAD"), remap = false, cancellable = true)
    private void onCharTyped(CharInput input, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!input.isValidChar()) return;
            boolean consumed = false;
            String text = input.asString();
            for (int i = 0; i < text.length(); i++) {
                consumed |= TownyMapMod.onTownSearchCharTyped(text.charAt(i));
            }
            if (consumed) {
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map text input", e);
        }
    }

    private void jumpTo(TownData town) {
        if (town == null) return;
        cameraX = town.centerX();
        cameraZ = town.centerZ();
    }

    private void jumpTo(MapJumpTarget target) {
        if (target == null) return;
        cameraX = target.x();
        cameraZ = target.z();
    }

    private void handleJumpOrRoute(MapJumpTarget target) {
        if (target == null) return;
        if (isShiftDown()) {
            TownyMapMod.createXaeroRoute(target);
            return;
        }
        jumpTo(target);
    }

    private static boolean isShiftDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        long handle = mc.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static void logOnce(AtomicBoolean flag, String message, Exception e) {
        if (flag.compareAndSet(false, true)) {
            LOGGER.warn("[TownyMap] {}", message, e);
        }
    }

    private static void disableDepthTestIfAvailable() {
        for (String className : new String[]{
                "com.mojang.blaze3d.opengl.GlStateManager",
                "com.mojang.blaze3d.platform.GlStateManager"
        }) {
            try {
                Class<?> stateManager = Class.forName(className);
                stateManager.getMethod("_disableDepthTest").invoke(null);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static void clearDepthForXaeroArrowIfAvailable() {
        try {
            Object framebuffer = MinecraftClient.getInstance().getFramebuffer();
            Class<?> textureUtils = Class.forName("xaero.lib.client.graphics.util.TextureUtils");
            for (Method method : textureUtils.getMethods()) {
                if (!"clearRenderTargetDepth".equals(method.getName()) || method.getParameterCount() != 2) continue;
                Class<?>[] params = method.getParameterTypes();
                if (!params[0].isInstance(framebuffer) || params[1] != float.class) continue;
                method.invoke(null, framebuffer, 1.0F);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }
}
