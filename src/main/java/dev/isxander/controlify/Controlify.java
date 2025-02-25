package dev.isxander.controlify;

import com.mojang.blaze3d.Blaze3D;
import dev.isxander.controlify.api.ControlifyApi;
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint;
import dev.isxander.controlify.gui.controllers.ControllerBindHandler;
import dev.isxander.controlify.gui.screen.ControllerCarouselScreen;
import dev.isxander.controlify.controller.Controller;
import dev.isxander.controlify.controller.ControllerState;
import dev.isxander.controlify.controller.sdl2.SDL2NativesManager;
import dev.isxander.controlify.debug.DebugProperties;
import dev.isxander.controlify.gui.screen.ControllerCalibrationScreen;
import dev.isxander.controlify.gui.screen.SDLOnboardingScreen;
import dev.isxander.controlify.gui.screen.SubmitUnknownControllerScreen;
import dev.isxander.controlify.ingame.ControllerPlayerMovement;
import dev.isxander.controlify.reacharound.ReachAroundHandler;
import dev.isxander.controlify.reacharound.ReachAroundMode;
import dev.isxander.controlify.reacharound.ReachAroundPolicy;
import dev.isxander.controlify.screenop.ScreenProcessorProvider;
import dev.isxander.controlify.config.ControlifyConfig;
import dev.isxander.controlify.hid.ControllerHIDService;
import dev.isxander.controlify.api.event.ControlifyEvents;
import dev.isxander.controlify.gui.guide.InGameButtonGuide;
import dev.isxander.controlify.ingame.InGameInputHandler;
import dev.isxander.controlify.mixins.feature.virtualmouse.MouseHandlerAccessor;
import dev.isxander.controlify.server.EntityVibrationPacket;
import dev.isxander.controlify.server.OriginVibrationPacket;
import dev.isxander.controlify.server.ReachAroundPolicyPacket;
import dev.isxander.controlify.server.VibrationPacket;
import dev.isxander.controlify.utils.DebugLog;
import dev.isxander.controlify.utils.Log;
import dev.isxander.controlify.utils.ToastUtils;
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler;
import dev.isxander.controlify.wireless.LowBatteryNotifier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

public class Controlify implements ControlifyApi {
    private static Controlify instance = null;

    private final Minecraft minecraft = Minecraft.getInstance();

    private Controller<?, ?> currentController = null;
    private InGameInputHandler inGameInputHandler;
    public InGameButtonGuide inGameButtonGuide;
    private VirtualMouseHandler virtualMouseHandler;
    private InputMode currentInputMode = InputMode.KEYBOARD_MOUSE;
    private ControllerHIDService controllerHIDService;

    private CompletableFuture<Boolean> nativeOnboardingFuture = null;

    private final ControlifyConfig config = new ControlifyConfig(this);

    private final Queue<Controller<?, ?>> calibrationQueue = new ArrayDeque<>();
    private boolean canDiscoverControllers = true;

    private int consecutiveInputSwitches = 0;
    private double lastInputSwitchTime = 0;

    private int showMouseTicks = 0;

    private @Nullable Controller<?, ?> switchableController = null;
    private double askSwitchTime = 0;
    private ToastUtils.ControlifyToast askSwitchToast = null;

    public void initializeControlify() {
        Log.LOGGER.info("Initializing Controlify...");

        config().load();

        var controllersConnected = IntStream.range(0, GLFW.GLFW_JOYSTICK_LAST + 1).anyMatch(GLFW::glfwJoystickPresent);
        if (controllersConnected) {
            if (!config().globalSettings().delegateSetup) {
                askNatives().whenComplete((loaded, th) -> discoverControllers());
            } else {
                ToastUtils.sendToast(
                        Component.translatable("controlify.toast.setup_in_config.title"),
                        Component.translatable(
                                "controlify.toast.setup_in_config.description",
                                Component.translatable("options.title"),
                                Component.translatable("controls.keybinds.title"),
                                Component.literal("Controlify")
                        ),
                        false
                );
            }
        }

        ClientTickEvents.START_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
            controllerHIDService().stop();
        });

        // listen for new controllers
        GLFW.glfwSetJoystickCallback((jid, event) -> {
            try {
                this.askNatives().whenComplete((loaded, th) -> {
                    if (event == GLFW.GLFW_CONNECTED) {
                        this.onControllerHotplugged(jid);
                    } else if (event == GLFW.GLFW_DISCONNECTED) {
                        this.onControllerDisconnect(jid);
                    }
                });
            } catch (Throwable e) {
                e.printStackTrace();
            }
        });

        notifyOfNewFeatures();
    }

    private CompletableFuture<Boolean> askNatives() {
        if (nativeOnboardingFuture != null) return nativeOnboardingFuture;

        if (config().globalSettings().vibrationOnboarded) {
            boolean loadNatives = config().globalSettings().loadVibrationNatives;
            if (loadNatives && !SDL2NativesManager.isInitialised()) {
                SDL2NativesManager.initialise();
            }
            return CompletableFuture.completedFuture(loadNatives);
        }

        nativeOnboardingFuture = new CompletableFuture<>();

        Screen parent = minecraft.screen;
        minecraft.setScreen(new SDLOnboardingScreen(
                () -> parent,
                answer -> {
                    if (answer)
                        SDL2NativesManager.initialise();
                    nativeOnboardingFuture.complete(answer);
                }
        ));

        return nativeOnboardingFuture;
    }

    public void discoverControllers() {
        if (!canDiscoverControllers) {
            throw new IllegalStateException("Already discovered/cannot discover controllers");
        }
        canDiscoverControllers = false;

        DebugLog.log("Discovering and initializing controllers...");

        if (config().globalSettings().loadVibrationNatives)
            SDL2NativesManager.initialise();

        // find already connected controllers
        for (int jid = 0; jid <= GLFW.GLFW_JOYSTICK_LAST; jid++) {
            if (GLFW.glfwJoystickPresent(jid)) {
                var controllerOpt = ControllerManager.createOrGet(jid, controllerHIDService.fetchType(jid));
                if (controllerOpt.isEmpty()) continue;
                var controller = controllerOpt.get();

                Log.LOGGER.info("Controller found: " + controller.name());

                config().loadOrCreateControllerData(controller);

                if (SubmitUnknownControllerScreen.canSubmit(controller)) {
                    minecraft.setScreen(new SubmitUnknownControllerScreen(controller, minecraft.screen));
                }

                if (controller.uid().equals(config().currentControllerUid()))
                    setCurrentController(controller);

                if (controller.config().allowVibrations && !config().globalSettings().loadVibrationNatives) {
                    controller.config().allowVibrations = false;
                    config().setDirty();
                }
            }
        }

        if (ControllerManager.getConnectedControllers().isEmpty()) {
            Log.LOGGER.info("No controllers found.");
        }

        if (getCurrentController().isEmpty()) {
            var controller = ControllerManager.getConnectedControllers().stream().findFirst().orElse(null);
            if (controller != null && controller.config().delayedCalibration) {
                controller = null;
            }

            this.setCurrentController(controller);
        } else {
            // setCurrentController saves config
            config().saveIfDirty();
        }

        FabricLoader.getInstance().getEntrypoints("controlify", ControlifyEntrypoint.class).forEach(entrypoint -> {
            try {
                entrypoint.onControllersDiscovered(this);
            } catch (Throwable e) {
                Log.LOGGER.error("Failed to run `onControllersDiscovered` on Controlify entrypoint: " + entrypoint.getClass().getName(), e);
            }
        });
    }

    public void preInitialiseControlify() {
        DebugProperties.printProperties();

        Log.LOGGER.info("Pre-initializing Controlify...");

        this.inGameInputHandler = null;
        this.virtualMouseHandler = new VirtualMouseHandler();

        controllerHIDService = new ControllerHIDService();
        controllerHIDService.start();

        ControllerBindHandler.setup();

        ClientPlayNetworking.registerGlobalReceiver(VibrationPacket.TYPE, (packet, player, sender) -> {
            if (config().globalSettings().allowServerRumble) {
                getCurrentController().ifPresent(controller ->
                        controller.rumbleManager().play(packet.source(), packet.createEffect()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(OriginVibrationPacket.TYPE, (packet, player, sender) -> {
            if (config().globalSettings().allowServerRumble) {
                getCurrentController().ifPresent(controller ->
                        controller.rumbleManager().play(packet.source(), packet.createEffect()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(EntityVibrationPacket.TYPE, (packet, player, sender) -> {
            if (config().globalSettings().allowServerRumble) {
                getCurrentController().ifPresent(controller ->
                        controller.rumbleManager().play(packet.source(), packet.createEffect()));
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(ReachAroundPolicyPacket.TYPE, (packet, player, sender) -> {
            Log.LOGGER.info("Connected server specified reach around policy is {}.", packet.allowed() ? "ALLOWED" : "DISALLOWED");
            ReachAroundHandler.reachAroundPolicy = ReachAroundPolicy.fromServer(packet.allowed());

            if (config().globalSettings().reachAround == ReachAroundMode.EVERYWHERE && !packet.allowed()) {
                ToastUtils.sendToast(
                        Component.translatable("controlify.toast.reach_around_disallowed.title"),
                        Component.translatable("controlify.toast.reach_around_disallowed.description"),
                        false
                );
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            DebugLog.log("Disconnected from server, resetting reach around policy");
            ReachAroundHandler.reachAroundPolicy = ReachAroundPolicy.UNSET;
        });

        FabricLoader.getInstance().getEntrypoints("controlify", ControlifyEntrypoint.class).forEach(entrypoint -> {
            try {
                entrypoint.onControlifyPreInit(this);
            } catch (Exception e) {
                Log.LOGGER.error("Failed to run `onControlifyPreInit` on Controlify entrypoint: " + entrypoint.getClass().getName(), e);
            }
        });
    }

    public void tick(Minecraft client) {
        if (minecraft.getOverlay() == null) {
            if (!calibrationQueue.isEmpty() && !(minecraft.screen instanceof SDLOnboardingScreen)) {
                Screen screen = minecraft.screen;
                while (!calibrationQueue.isEmpty()) {
                    screen = new ControllerCalibrationScreen(calibrationQueue.poll(), screen);
                }
                minecraft.setScreen(screen);
            }
        }

        boolean outOfFocus = !config().globalSettings().outOfFocusInput && !client.isWindowActive();

        for (var controller : ControllerManager.getConnectedControllers()) {
            if (!outOfFocus)
                wrapControllerError(controller::updateState, "Updating controller state", controller);
            else
                wrapControllerError(controller::clearState, "Clearing controller state", controller);
            ControlifyEvents.CONTROLLER_STATE_UPDATE.invoker().onControllerStateUpdate(controller);
        }

        if (switchableController != null && Blaze3D.getTime() - askSwitchTime <= 10000) {
            if (switchableController.state().hasAnyInput()) {
                switchableController.clearState();
                this.setCurrentController(switchableController); // setCurrentController sets switchableController to null
                if (askSwitchToast != null) {
                    askSwitchToast.remove();
                    askSwitchToast = null;
                }
            }
        }

        if (minecraft.mouseHandler.isMouseGrabbed())
            showMouseTicks = 0;
        if (currentInputMode() == InputMode.MIXED && showMouseTicks > 0) {
            showMouseTicks--;
            if (showMouseTicks == 0) {
                hideMouse(true, false);
                if (virtualMouseHandler().requiresVirtualMouse()) {
                    virtualMouseHandler().enableVirtualMouse();
                }
            }
        }

        LowBatteryNotifier.tick();

        getCurrentController().ifPresent(currentController -> {
            wrapControllerError(
                    () -> tickController(currentController, outOfFocus),
                    "Ticking current controller",
                    currentController
            );
        });
    }

    private void tickController(Controller<?, ?> controller, boolean outOfFocus) {
        ControllerState state = controller.state();

        if (outOfFocus) {
            state = ControllerState.EMPTY;
            controller.rumbleManager().setSilent(true);
        } else {
            controller.rumbleManager().setSilent(false);
            controller.rumbleManager().tick();
        }

        if (state.hasAnyInput()) {
            this.setInputMode(controller.config().mixedInput ? InputMode.MIXED : InputMode.CONTROLLER);
        }

        if (consecutiveInputSwitches > 100) {
            Log.LOGGER.warn("Controlify detected current controller to be constantly giving input and has been disabled.");
            ToastUtils.sendToast(
                    Component.translatable("controlify.toast.faulty_input.title"),
                    Component.translatable("controlify.toast.faulty_input.description"),
                    true
            );
            this.setCurrentController(null);
            consecutiveInputSwitches = 0;
            return;
        }

        this.virtualMouseHandler().handleControllerInput(controller);
        if (minecraft.screen != null) {
            ScreenProcessorProvider.provide(minecraft.screen).onControllerUpdate(controller);
        }
        if (minecraft.level != null) {
            this.inGameInputHandler().ifPresent(InGameInputHandler::inputTick);
        }

        ControlifyEvents.ACTIVE_CONTROLLER_TICKED.invoker().onControllerStateUpdate(controller);
    }

    public static void wrapControllerError(Runnable runnable, String errorTitle, Controller<?, ?> controller) {
        try {
            runnable.run();
        } catch (Throwable e) {
            CrashReport crashReport = CrashReport.forThrowable(e, errorTitle);
            CrashReportCategory category = crashReport.addCategory("Affected controller");
            category.setDetail("Controller name", controller.name());
            category.setDetail("Controller identification", controller.type().toString());
            category.setDetail("Controller type", controller.getClass().getCanonicalName());
            throw new ReportedException(crashReport);
        }
    }

    public ControlifyConfig config() {
        return config;
    }

    private void onControllerHotplugged(int jid) {
        var controllerOpt = ControllerManager.createOrGet(jid, controllerHIDService.fetchType(jid));
        if (controllerOpt.isEmpty()) return;
        var controller = controllerOpt.get();

        Log.LOGGER.info("Controller connected: " + controller.name());

        config().loadOrCreateControllerData(controller);

        if (SubmitUnknownControllerScreen.canSubmit(controller)) {
            minecraft.setScreen(new SubmitUnknownControllerScreen(controller, minecraft.screen));
        }

        canDiscoverControllers = false;
        if (config().globalSettings().delegateSetup) {
            config().globalSettings().delegateSetup = false;
            config().setDirty();
        }

        if (controller.config().allowVibrations && !config().globalSettings().loadVibrationNatives) {
            controller.config().allowVibrations = false;
            config().setDirty();
        }

        if (ControllerManager.getConnectedControllers().size() == 1) {
            this.setCurrentController(controller);

            ToastUtils.sendToast(
                    Component.translatable("controlify.toast.default_controller_connected.title"),
                    Component.translatable("controlify.toast.default_controller_connected.description"),
                    false
            );
        } else {
            this.askToSwitchController(controller);
            config().saveIfDirty();
        }

        if (minecraft.screen instanceof ControllerCarouselScreen controllerListScreen) {
            controllerListScreen.refreshControllers();
        }
    }

    private void onControllerDisconnect(int jid) {
        ControllerManager.getConnectedControllers().stream().filter(controller -> controller.joystickId() == jid).findAny().ifPresent(controller -> {
            ControllerManager.disconnect(controller);

            controller.hidInfo().ifPresent(controllerHIDService::unconsumeController);

            setCurrentController(ControllerManager.getConnectedControllers().stream().findFirst().orElse(null));
            Log.LOGGER.info("Controller disconnected: " + controller.name());
            this.setInputMode(currentController == null ? InputMode.KEYBOARD_MOUSE : InputMode.CONTROLLER);

            ToastUtils.sendToast(
                    Component.translatable("controlify.toast.controller_disconnected.title"),
                    Component.translatable("controlify.toast.controller_disconnected.description", controller.name()),
                    false
            );

            if (minecraft.screen instanceof ControllerCarouselScreen controllerListScreen) {
                controllerListScreen.refreshControllers();
            }
        });
    }

    private void askToSwitchController(Controller<?, ?> controller) {
        this.switchableController = controller;
        this.askSwitchTime = Blaze3D.getTime();

        askSwitchToast = ToastUtils.sendToast(
                Component.translatable("controlify.toast.ask_to_switch.title"),
                Component.translatable("controlify.toast.ask_to_switch.description", controller.name()),
                true
        );
    }

    @Override
    @Deprecated
    public @NotNull Controller<?, ?> currentController() {
        if (currentController == null)
            return Controller.DUMMY;

        return currentController;
    }

    @Override
    public @NotNull Optional<Controller<?, ?>> getCurrentController() {
        return Optional.ofNullable(currentController);
    }

    public void setCurrentController(@Nullable Controller<?, ?> controller) {
        if (this.currentController == controller) return;

        this.currentController = controller;

        if (switchableController == controller) {
            switchableController = null;
        }

        if (controller == null) {
            this.setInputMode(InputMode.KEYBOARD_MOUSE);
            this.inGameInputHandler = null;
            this.inGameButtonGuide = null;
            DebugLog.log("Updated current controller to null");
            config().save();
            return;
        }

        DebugLog.log("Updated current controller to {}({})", controller.name(), controller.uid());

        if (!controller.uid().equals(config().currentControllerUid())) {
            config().save();
        }

        this.inGameInputHandler = new InGameInputHandler(controller);

        setInputMode(controller.config().mixedInput ? InputMode.MIXED : InputMode.CONTROLLER);

        if (!controller.config().deadzonesCalibrated)
            calibrationQueue.add(controller);
    }

    public Optional<InGameInputHandler> inGameInputHandler() {
        return Optional.ofNullable(inGameInputHandler);
    }

    public Optional<InGameButtonGuide> inGameButtonGuide() {
        return Optional.ofNullable(inGameButtonGuide);
    }

    public VirtualMouseHandler virtualMouseHandler() {
        return virtualMouseHandler;
    }

    public ControllerHIDService controllerHIDService() {
        return controllerHIDService;
    }

    public @NotNull InputMode currentInputMode() {
        return currentInputMode;
    }

    @Override
    public boolean setInputMode(@NotNull InputMode currentInputMode) {
        if (this.currentInputMode == currentInputMode) return false;
        this.currentInputMode = currentInputMode;

        if (!minecraft.mouseHandler.isMouseGrabbed())
            hideMouse(currentInputMode.isController(), true);
        if (minecraft.screen != null) {
            ScreenProcessorProvider.provide(minecraft.screen).onInputModeChanged(currentInputMode);
        }
        if (Minecraft.getInstance().player != null) {
            if (currentInputMode == InputMode.KEYBOARD_MOUSE) {
                this.inGameButtonGuide = null;
            } else {
                this.inGameButtonGuide = this.getCurrentController().map(c -> new InGameButtonGuide(c, Minecraft.getInstance().player)).orElse(null);
            }
        }
        if (Blaze3D.getTime() - lastInputSwitchTime < 20) {
            consecutiveInputSwitches++;
        } else {
            consecutiveInputSwitches = 0;
        }
        lastInputSwitchTime = Blaze3D.getTime();

        if (this.currentInputMode.isController())
            getCurrentController().ifPresent(Controller::clearState);

        ControllerPlayerMovement.updatePlayerInput(minecraft.player);

        ControlifyEvents.INPUT_MODE_CHANGED.invoker().onInputModeChanged(currentInputMode);

        return true;
    }

    public void hideMouse(boolean hide, boolean moveMouse) {
        GLFW.glfwSetInputMode(
                minecraft.getWindow().getWindow(),
                GLFW.GLFW_CURSOR,
                hide
                        ? GLFW.GLFW_CURSOR_HIDDEN
                        : GLFW.GLFW_CURSOR_NORMAL
        );
        if (minecraft.screen != null) {
            var mouseHandlerAccessor = (MouseHandlerAccessor) minecraft.mouseHandler;
            if (hide && !virtualMouseHandler().isVirtualMouseEnabled() && moveMouse) {
                // stop mouse hovering over last element before hiding cursor but don't actually move it
                // so when the user switches back to mouse it will be in the same place
                mouseHandlerAccessor.invokeOnMove(minecraft.getWindow().getWindow(), -50, -50);
            }
        }
    }

    public void showCursorTemporarily() {
        if (currentInputMode() == InputMode.MIXED && !minecraft.mouseHandler.isMouseGrabbed()) {
            hideMouse(false, false);
            showMouseTicks = 20 * 2;
            if (virtualMouseHandler().isVirtualMouseEnabled()) {
                virtualMouseHandler().disableVirtualMouse();
            }
        }
    }

    private void notifyOfNewFeatures() {
        if (config().isFirstLaunch())
            return;

        var newFeatureVersions = List.of(
                "1.5.0"
        ).iterator();

        String foundVersion = null;
        while (foundVersion == null && newFeatureVersions.hasNext()) {
            var version = newFeatureVersions.next();
            if (config().isLastSeenVersionLessThan(version)) {
                foundVersion = version;
            }
        }

        if (foundVersion != null) {
            Log.LOGGER.info("Sending new features toast for {}", foundVersion);
            ToastUtils.sendToast(
                    Component.translatable("controlify.new_features.title", foundVersion),
                    Component.translatable("controlify.new_features." + foundVersion),
                    true
            );
        }
    }

    public static Controlify instance() {
        if (instance == null) instance = new Controlify();
        return instance;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation("controlify", path);
    }
}
