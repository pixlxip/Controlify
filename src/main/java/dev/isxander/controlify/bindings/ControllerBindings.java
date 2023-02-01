package dev.isxander.controlify.bindings;

import com.google.gson.JsonObject;
import dev.isxander.controlify.controller.Controller;
import dev.isxander.controlify.event.ControlifyEvents;
import dev.isxander.controlify.mixins.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public class ControllerBindings {
    public final ControllerBinding JUMP, SNEAK, ATTACK, USE, SPRINT, NEXT_SLOT, PREV_SLOT, PAUSE, INVENTORY, CHANGE_PERSPECTIVE, OPEN_CHAT;

    private final Map<ResourceLocation, ControllerBinding> registry = new HashMap<>();

    public ControllerBindings(Controller controller) {
        var options = Minecraft.getInstance().options;

        register(JUMP = new ControllerBinding(controller, Bind.A_BUTTON, new ResourceLocation("controlify", "jump"), options.keyJump));
        register(SNEAK = new ControllerBinding(controller, Bind.RIGHT_STICK, new ResourceLocation("controlify", "sneak"), options.keyShift));
        register(ATTACK = new ControllerBinding(controller, Bind.RIGHT_TRIGGER, new ResourceLocation("controlify", "attack"), options.keyAttack));
        register(USE = new ControllerBinding(controller, Bind.LEFT_TRIGGER, new ResourceLocation("controlify", "use"), options.keyUse));
        register(SPRINT = new ControllerBinding(controller, Bind.LEFT_STICK, new ResourceLocation("controlify", "sprint"), options.keySprint));
        register(NEXT_SLOT = new ControllerBinding(controller, Bind.RIGHT_BUMPER, new ResourceLocation("controlify", "next_slot"), null));
        register(PREV_SLOT = new ControllerBinding(controller, Bind.LEFT_BUMPER, new ResourceLocation("controlify", "prev_slot"), null));
        register(PAUSE = new ControllerBinding(controller, Bind.START, new ResourceLocation("controlify", "pause"), null));
        register(INVENTORY = new ControllerBinding(controller, Bind.Y_BUTTON, new ResourceLocation("controlify", "inventory"), options.keyInventory));
        register(CHANGE_PERSPECTIVE = new ControllerBinding(controller, Bind.BACK, new ResourceLocation("controlify", "change_perspective"), options.keyTogglePerspective));
        register(OPEN_CHAT = new ControllerBinding(controller, Bind.DPAD_UP, new ResourceLocation("controlify", "open_chat"), options.keyChat));

        ControlifyEvents.CONTROLLER_BIND_REGISTRY.invoker().onRegisterControllerBinds(this, controller);

        ControlifyEvents.CONTROLLER_STATE_UPDATED.register(this::imitateVanillaClick);
    }

    public BindingSupplier register(ControllerBinding binding) {
        registry.put(binding.id(), binding);
        return controller -> controller.bindings().get(binding.id());
    }

    public ControllerBinding get(ResourceLocation id) {
        return registry.get(id);
    }

    public Map<ResourceLocation, ControllerBinding> registry() {
        return Collections.unmodifiableMap(registry);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        for (var binding : registry().values()) {
            json.addProperty(binding.id().toString(), binding.currentBind().identifier());
        }
        return json;
    }

    public void fromJson(JsonObject json) {
        for (var binding : registry().values()) {
            var bind = json.get(binding.id().toString());
            if (bind == null) continue;
            binding.setCurrentBind(Bind.fromIdentifier(bind.getAsString()));
        }
    }

    private void imitateVanillaClick(Controller controller) {
        for (var binding : registry().values()) {
            KeyMapping vanillaKey = binding.override();
            if (vanillaKey == null) continue;

            var vanillaKeyCode = ((KeyMappingAccessor) vanillaKey).getKey();

            KeyMapping.set(vanillaKeyCode, binding.held());
            if (binding.justPressed()) KeyMapping.click(vanillaKeyCode);
        }
    }
}