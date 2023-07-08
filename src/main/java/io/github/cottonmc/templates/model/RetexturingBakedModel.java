package io.github.cottonmc.templates.model;

import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachedBlockView;
import net.minecraft.block.BlockState;
import net.minecraft.client.color.block.BlockColorProvider;
import net.minecraft.client.color.item.ItemColorProvider;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.texture.Sprite;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public abstract class RetexturingBakedModel extends ForwardingBakedModel {
	public RetexturingBakedModel(BakedModel baseModel, TemplateAppearanceManager tam, ModelBakeSettings settings, BlockState itemModelState) {
		this.wrapped = baseModel;
		
		this.tam = tam;
		this.facePermutation = MeshTransformUtil.facePermutation(settings);
		this.uvlock = settings.isUvLocked();
		this.itemModelState = itemModelState;
	}
	
	protected final TemplateAppearanceManager tam;
	protected final Map<Direction, Direction> facePermutation; //immutable
	protected final boolean uvlock;
	protected final BlockState itemModelState;
	
	private static record CacheKey(BlockState state, TemplateAppearance appearance) {}
	private final ConcurrentMap<CacheKey, Mesh> retexturedMeshes = new ConcurrentHashMap<>();
	
	protected static final Direction[] DIRECTIONS = Direction.values();
	protected static final Direction[] DIRECTIONS_AND_NULL = new Direction[DIRECTIONS.length + 1];
	static {
		System.arraycopy(DIRECTIONS, 0, DIRECTIONS_AND_NULL, 0, DIRECTIONS.length);
	}
	
	protected abstract Mesh getBaseMesh(BlockState state);
	
	@Override
	public boolean isVanillaAdapter() {
		return false;
	}
	
	@Override
	public Sprite getParticleSprite() {
		return tam.getDefaultAppearance().getParticleSprite();
	}
	
	@Override
	public void emitBlockQuads(BlockRenderView blockView, BlockState state, BlockPos pos, Supplier<Random> randomSupplier, RenderContext context) {
		BlockState theme = (((RenderAttachedBlockView) blockView).getBlockEntityRenderAttachment(pos) instanceof BlockState s) ? s : null;
		if(theme == null || theme.isAir()) {
			context.meshConsumer().accept(getUntintedRetexturedMesh(new CacheKey(state, tam.getDefaultAppearance())));
			return;
		}
		
		TemplateAppearance ta = tam.getAppearance(theme);
		
		BlockColorProvider prov = ColorProviderRegistry.BLOCK.get(theme.getBlock());
		int tint = prov == null ? 0xFFFFFFFF : (0xFF000000 | prov.getColor(theme, blockView, pos, 1));
		Mesh untintedMesh = getUntintedRetexturedMesh(new CacheKey(state, ta));
		
		//The specific tint might vary a lot; imagine grass color smoothly changing. Trying to bake the tint into
		//the cached mesh will pollute it with a ton of single-use meshes with only slighly different colors.
		if(tint == 0xFFFFFFFF) {
			context.meshConsumer().accept(untintedMesh);
		} else {
			context.pushTransform(new TintingTransformer(ta, tint));
			context.meshConsumer().accept(untintedMesh);
			context.popTransform();
		}
	}
	
	@Override
	public void emitItemQuads(ItemStack stack, Supplier<Random> randomSupplier, RenderContext context) {
		TemplateAppearance nbtAppearance = tam.getDefaultAppearance();
		int tint = 0xFFFFFFFF;
		
		//cheeky: if the item has NBT data, pluck out the blockstate from it & look up the item color provider
		//none of this is accessible unless you're in creative mode doing ctrl-pick btw
		NbtCompound tag = BlockItem.getBlockEntityNbt(stack);
		if(tag != null && tag.contains("BlockState")) {
			BlockState theme = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), tag.getCompound("BlockState"));
			if(!theme.isAir()) {
				nbtAppearance = tam.getAppearance(theme);
				
				ItemColorProvider prov = ColorProviderRegistry.ITEM.get(theme.getBlock());
				if(prov != null) tint = prov.getColor(new ItemStack(theme.getBlock()), 1);
			}
		}
		
		Mesh untintedMesh = getUntintedRetexturedMesh(new CacheKey(itemModelState, nbtAppearance));
		
		if(tint == 0xFFFFFFFF) {
			context.meshConsumer().accept(untintedMesh);
		} else {
			context.pushTransform(new TintingTransformer(nbtAppearance, tint));
			context.meshConsumer().accept(untintedMesh);
			context.popTransform();
		}
	}
	
	protected Mesh getUntintedRetexturedMesh(CacheKey key) {
		return retexturedMeshes.computeIfAbsent(key, this::createUntintedRetexturedMesh);
	}
	
	protected Mesh createUntintedRetexturedMesh(CacheKey key) {
		return MeshTransformUtil.pretransformMesh(getBaseMesh(key.state), new RetexturingTransformer(key.appearance, 0xFFFFFFFF));
	}
	
	protected class RetexturingTransformer implements RenderContext.QuadTransform {
		protected RetexturingTransformer(TemplateAppearance ta, int tint) {
			this.ta = ta;
			this.tint = tint;
		}
		
		protected final TemplateAppearance ta;
		protected final int tint;
		
		@Override
		public boolean transform(MutableQuadView quad) {
			quad.material(ta.getRenderMaterial());
			
			int tag = quad.tag();
			if(tag == 0) return true; //Pass the quad through unmodified.
			
			//The quad tag numbers were selected so this magic trick works:
			Direction dir = facePermutation.get(DIRECTIONS[quad.tag() - 1]);
			if(ta.hasColor(dir)) quad.color(tint, tint, tint, tint); //TODO: still doesn't cover stuff like grass blocks, leaf blocks, etc
			
			quad.spriteBake(ta.getSprite(dir), MutableQuadView.BAKE_NORMALIZED | ta.getBakeFlags(dir) | (uvlock ? MutableQuadView.BAKE_LOCK_UV : 0));
			
			return true;
		}
	}
	
	protected class TintingTransformer implements RenderContext.QuadTransform {
		protected TintingTransformer(TemplateAppearance ta, int tint) {
			this.ta = ta;
			this.tint = tint;
		}
		
		protected final TemplateAppearance ta;
		protected final int tint;
		
		@Override
		public boolean transform(MutableQuadView quad) {
			int tag = quad.tag();
			if(tag == 0) return true;
			
			Direction dir = facePermutation.get(DIRECTIONS[quad.tag() - 1]);
			if(ta.hasColor(dir)) quad.color(tint, tint, tint, tint); //TODO: still doesn't cover stuff like grass blocks, leaf blocks, etc
			
			return true;
		}
	}
}