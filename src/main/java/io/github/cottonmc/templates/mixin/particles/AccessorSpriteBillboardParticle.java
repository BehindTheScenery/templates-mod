package io.github.cottonmc.templates.mixin.particles;

import net.minecraft.client.particle.SpriteBillboardParticle;
import net.minecraft.client.texture.Sprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SpriteBillboardParticle.class)
public interface AccessorSpriteBillboardParticle {
	@Invoker("setSprite") void templates$setSprite(Sprite sprite);
}
