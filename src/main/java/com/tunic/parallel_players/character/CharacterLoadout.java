package com.tunic.parallel_players.character;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What a character is wearing and holding, kept as a small separate snapshot so the menu can draw the
 * character without a world.
 *
 * <p>The snapshot is needed because items cannot be rebuilt from the saved player data outside a
 * world: vanilla only binds the items' default components (which say, for instance, which armor
 * texture an item uses) when a world's registries load. So the render-relevant components are
 * captured while in a world and the stacks are rebuilt from them, on a direct holder that carries
 * exactly those components.
 */
public record CharacterLoadout(ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet, ItemStack mainHand, ItemStack offHand) {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallelplayers");

    public static final CharacterLoadout EMPTY = new CharacterLoadout(
            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    /** Components that decide how an item looks when worn or held. */
    private static final List<DataComponentType<?>> VISUAL_COMPONENTS = List.of(
            DataComponents.ITEM_MODEL,
            DataComponents.EQUIPPABLE,
            DataComponents.DYED_COLOR,
            DataComponents.CUSTOM_MODEL_DATA,
            DataComponents.PROFILE,
            DataComponents.ENCHANTMENT_GLINT_OVERRIDE
    );

    /** Not a real component type: where the by-value trim is kept inside a stack's saved components. */
    private static final String TRIM_KEY = "parallelplayers:trim";

    private static final String[] SLOT_KEYS = {"head", "chest", "legs", "feet", "mainhand", "offhand"};

    /** Snapshots what the player currently wears and holds. Call while a world is loaded. */
    public static byte[] capture(ServerPlayer player) throws IOException {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        CompoundTag root = new CompoundTag();
        ItemStack[] stacks = {
                player.getItemBySlot(EquipmentSlot.HEAD),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.FEET),
                player.getItemBySlot(EquipmentSlot.MAINHAND),
                player.getItemBySlot(EquipmentSlot.OFFHAND),
        };
        for (int i = 0; i < stacks.length; i++) {
            if (!stacks[i].isEmpty()) {
                root.put(SLOT_KEYS[i], captureStack(ops, stacks[i]));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    private static CompoundTag captureStack(RegistryOps<Tag> ops, ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", idOf(BuiltInRegistries.ITEM, stack.getItem()));
        CompoundTag components = new CompoundTag();
        for (DataComponentType<?> type : VISUAL_COMPONENTS) {
            captureComponent(ops, stack, type, components);
        }
        // Enchantments live in a data-driven registry that is not available in the menu, so only whether
        // the item glints is kept.
        if (stack.hasFoil()) {
            components.putBoolean(idOf(BuiltInRegistries.DATA_COMPONENT_TYPE, DataComponents.ENCHANTMENT_GLINT_OVERRIDE), true);
        }
        captureTrim(ops, stack, components);
        tag.put("components", components);
        return tag;
    }

    /**
     * Armor trims reference data-driven registries (trim materials and patterns), which the menu cannot
     * look up. So the trim's material and pattern are stored by value and rebuilt as direct holders.
     */
    private static void captureTrim(RegistryOps<Tag> ops, ItemStack stack, CompoundTag components) {
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim == null) {
            return;
        }
        Optional<Tag> material = TrimMaterial.DIRECT_CODEC.encodeStart(ops, trim.material().value()).result();
        Optional<Tag> pattern = TrimPattern.DIRECT_CODEC.encodeStart(ops, trim.pattern().value()).result();
        if (material.isPresent() && pattern.isPresent()) {
            CompoundTag saved = new CompoundTag();
            saved.put("material", material.get());
            saved.put("pattern", pattern.get());
            components.put(TRIM_KEY, saved);
        }
    }

    private static void decodeTrim(RegistryOps<Tag> ops, CompoundTag saved, DataComponentMap.Builder out) {
        Optional<TrimMaterial> material = saved.get("material") == null
                ? Optional.empty() : TrimMaterial.DIRECT_CODEC.parse(ops, saved.get("material")).result();
        Optional<TrimPattern> pattern = saved.get("pattern") == null
                ? Optional.empty() : TrimPattern.DIRECT_CODEC.parse(ops, saved.get("pattern")).result();
        if (material.isPresent() && pattern.isPresent()) {
            out.set(DataComponents.TRIM, new ArmorTrim(Holder.direct(material.get()), Holder.direct(pattern.get())));
        }
    }

    /** The registry id of a built-in registry entry; these are always registered, so a miss is a bug. */
    private static <T> String idOf(Registry<T> registry, T value) {
        Identifier id = registry.getKey(value);
        if (id == null) {
            throw new IllegalStateException("Not registered: " + value);
        }
        return id.toString();
    }

    private static <T> void captureComponent(RegistryOps<Tag> ops, ItemStack stack, DataComponentType<T> type, CompoundTag out) {
        T value = stack.get(type);
        if (value == null || type.codec() == null) {
            return;
        }
        type.codecOrThrow().encodeStart(ops, value).result()
                .ifPresent(encoded -> out.put(idOf(BuiltInRegistries.DATA_COMPONENT_TYPE, type), encoded));
    }

    /** The character's last captured loadout, or {@link #EMPTY} if it has none yet. */
    public static CharacterLoadout read(CharacterStore store, UUID id) {
        try {
            Optional<byte[]> bytes = store.readAppearance(id);
            if (bytes.isEmpty()) {
                return EMPTY;
            }
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes.get()), NbtAccounter.unlimitedHeap());
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            ItemStack[] stacks = new ItemStack[SLOT_KEYS.length];
            for (int i = 0; i < stacks.length; i++) {
                stacks[i] = root.getCompound(SLOT_KEYS[i]).map(tag -> rebuild(ops, tag)).orElse(ItemStack.EMPTY);
            }
            return new CharacterLoadout(stacks[0], stacks[1], stacks[2], stacks[3], stacks[4], stacks[5]);
        } catch (Exception e) {
            LOGGER.warn("Could not read the appearance of character {}", id, e);
            return EMPTY;
        }
    }

    private static ItemStack rebuild(RegistryOps<Tag> ops, CompoundTag tag) {
        Optional<Item> item = tag.getString("id")
                .map(Identifier::tryParse)
                .flatMap(BuiltInRegistries.ITEM::get)
                .map(Holder.Reference::value);
        if (item.isEmpty()) {
            return ItemStack.EMPTY;
        }
        DataComponentMap.Builder components = DataComponentMap.builder();
        CompoundTag saved = tag.getCompound("components").orElseGet(CompoundTag::new);
        saved.getCompound(TRIM_KEY).ifPresent(trim -> decodeTrim(ops, trim, components));
        for (String key : saved.keySet()) {
            Identifier typeId = Identifier.tryParse(key);
            Optional<DataComponentType<?>> type = typeId == null
                    ? Optional.empty()
                    : BuiltInRegistries.DATA_COMPONENT_TYPE.get(typeId).map(Holder.Reference::value);
            type.ifPresent(t -> decodeComponent(ops, t, saved.get(key), components));
        }
        return new ItemStack(Holder.direct(item.get(), components.build()), 1);
    }

    private static <T> void decodeComponent(RegistryOps<Tag> ops, DataComponentType<T> type, Tag tag, DataComponentMap.Builder out) {
        if (type.codec() == null) {
            return;
        }
        type.codecOrThrow().parse(ops, tag).result().ifPresent(value -> out.set(type, value));
    }
}
