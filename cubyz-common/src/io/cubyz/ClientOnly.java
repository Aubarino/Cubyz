package io.cubyz;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.cubyz.blocks.Block;
import io.cubyz.entity.EntityType;
import io.cubyz.entity.Player;
import io.cubyz.items.Inventory;
import io.cubyz.world.ReducedChunk;

public class ClientOnly {

	public static Consumer<Block[]> generateTextureAtlas;
	public static Consumer<Block> createBlockMesh;
	public static Consumer<EntityType> createEntityMesh;
	public static BiConsumer<String, Object> registerGui;
	public static BiConsumer<String, Inventory> openGui;
	public static Consumer<Player> onBorderCrossing;
	public static Consumer<ReducedChunk> createChunkMesh;
	public static Consumer<ReducedChunk> deleteChunkMesh;
	
	static {
		createBlockMesh = (b) -> {
			throw new UnsupportedOperationException("createBlockMesh");
		};
		onBorderCrossing = (p) -> {
			System.out.println("Did it!");
			return; // This λ is used for updating the Spatial position to be in correct relation to the player.
		};
	}
	
}
