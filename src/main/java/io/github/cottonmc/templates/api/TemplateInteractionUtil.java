package io.github.cottonmc.templates.api;

import io.github.cottonmc.templates.block.TemplateEntity;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class TemplateInteractionUtil {
	public static final BooleanProperty LIGHT = BooleanProperty.of("templates_light");
	public static final BooleanProperty REDSTONE = BooleanProperty.of("templates_redstone");
	public static final BooleanProperty SOLID = BooleanProperty.of("templates_solid");
	
	public static StateManager.Builder<Block, BlockState> appendProperties(StateManager.Builder<Block, BlockState> builder) {
		return builder.add(LIGHT, REDSTONE, SOLID);
	}
	
	public static AbstractBlock.Settings makeSettings() {
		return configureSettings(AbstractBlock.Settings.create());
	}
	
	public static AbstractBlock.Settings configureSettings(AbstractBlock.Settings s) {
		return s.luminance(TemplateInteractionUtil::luminance).nonOpaque().sounds(BlockSoundGroup.WOOD).hardness(0.2f);
	}
	
	public static BlockState setDefaultStates(BlockState in) {
		return in.with(LIGHT, false).with(REDSTONE, false).with(SOLID, true);
	}
	
	public static ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
		if(!(world.getBlockEntity(pos) instanceof TemplateEntity be)) return ActionResult.PASS;
		if(!player.canModifyBlocks() || !world.canPlayerModifyAt(player, pos)) return ActionResult.PASS;
		
		ItemStack held = player.getStackInHand(hand);
		
		//Glowstone
		if(state.contains(LIGHT) && held.getItem() == Items.GLOWSTONE_DUST && !state.get(LIGHT) && !be.hasSpentGlowstoneDust()) {
			world.setBlockState(pos, state.with(LIGHT, true));
			be.spentGlowstoneDust();
			
			if(!player.isCreative()) held.decrement(1);
			world.playSound(player, pos, SoundEvents.BLOCK_GLASS_HIT, SoundCategory.BLOCKS, 1f, 1f);
			return ActionResult.SUCCESS;
		}
		
		//Redstone
		if(state.contains(REDSTONE) && held.getItem() == Blocks.REDSTONE_TORCH.asItem() && !state.get(REDSTONE) && !be.hasSpentRedstoneTorch()) {
			world.setBlockState(pos, state.with(REDSTONE, true));
			be.spentRedstoneTorch();
			
			if(!player.isCreative()) held.decrement(1);
			world.playSound(player, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1f, 1f);
			return ActionResult.SUCCESS;
		}
		
		//Popped chorus fruit
		if(state.contains(SOLID) && held.getItem() == Items.POPPED_CHORUS_FRUIT && state.get(SOLID) && !be.hasSpentPoppedChorus()) {
			world.setBlockState(pos, state.with(SOLID, false));
			be.spentPoppedChorus();
			
			if(!player.isCreative()) held.decrement(1);
			world.playSound(player, pos, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.BLOCKS, 1f, 1f);
			return ActionResult.SUCCESS;
		}
		
		//Changing the theme
		if(held.getItem() instanceof BlockItem bi && be.getThemeState().getBlock() == Blocks.AIR) {
			Block block = bi.getBlock();
			ItemPlacementContext ctx = new ItemPlacementContext(new ItemUsageContext(player, hand, hit));
			BlockState placementState = block.getPlacementState(ctx);
			if(placementState != null && Block.isShapeFullCube(placementState.getCollisionShape(world, pos)) && !(block instanceof BlockEntityProvider)) {
				if(!world.isClient) be.setRenderedState(placementState);
				
				world.setBlockState(pos, state
					.with(LIGHT, be.hasSpentGlowstoneDust() || (placementState.getLuminance() != 0))
					.with(REDSTONE, be.hasSpentRedstoneTorch() || placementState.getWeakRedstonePower(world, pos, Direction.NORTH) != 0));
				
				if(!player.isCreative()) held.decrement(1);
				world.playSound(player, pos, placementState.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 1.1f);
				return ActionResult.SUCCESS;
			}
		}
		
		return ActionResult.PASS;
	}
	
	public static void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if(!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof TemplateEntity template) {
			DefaultedList<ItemStack> drops = DefaultedList.of();
			
			//TODO: remember the specific ItemStack
			Block theme = template.getThemeState().getBlock();
			if(theme != Blocks.AIR) drops.add(new ItemStack(theme));
			
			if(template.hasSpentRedstoneTorch()) drops.add(new ItemStack(Items.REDSTONE_TORCH));
			if(template.hasSpentGlowstoneDust()) drops.add(new ItemStack(Items.GLOWSTONE_DUST));
			if(template.hasSpentPoppedChorus()) drops.add(new ItemStack(Items.POPPED_CHORUS_FRUIT));
			
			ItemScatterer.spawn(world, pos, drops);
		}
	}
	
	public static void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		//Load the BlockEntityTag clientside, which fixes the template briefly showing its default state when placing it.
		//I'm surprised this doesn't happen by default; the BlockEntityTag stuff is only done serverside.
		if(world.isClient && world.getBlockEntity(pos) instanceof TemplateEntity be) {
			NbtCompound tag = BlockItem.getBlockEntityNbt(stack);
			if(tag != null) be.readNbt(tag);
		}
	}
	
	public static @Nullable VoxelShape getCollisionShape(BlockState state) {
		return state.contains(SOLID) && !state.get(SOLID) ? VoxelShapes.empty() : null;
	}
	
	public static boolean emitsRedstonePower(BlockState state) {
		return state.contains(REDSTONE) ? state.get(REDSTONE) : false;
	}
	
	public static int getWeakRedstonePower(BlockState state, BlockView view, BlockPos pos, Direction dir) {
		return state.contains(REDSTONE) && state.get(REDSTONE) ? 15 : 0;
	}
	
	public static int getStrongRedstonePower(BlockState state, BlockView view, BlockPos pos, Direction dir) {
		return state.contains(REDSTONE) && state.get(REDSTONE) ? 15 : 0;
	}
	
	public static int luminance(BlockState state) {
		return state.contains(LIGHT) && state.get(LIGHT) ? 15 : 0;
	}
}
