package cubyz.world;

import cubyz.api.CurrentWorldRegistries;
import cubyz.client.Cubyz;
import cubyz.client.entity.ClientPlayer;
import cubyz.clientSide.ServerConnection;
import cubyz.modding.ModLoader;
import cubyz.multiplayer.Protocols;
import cubyz.rendering.RenderOctTree;
import cubyz.server.Server;
import cubyz.utils.Logger;
import cubyz.world.blocks.BlockEntity;
import cubyz.world.blocks.Blocks;
import cubyz.world.entity.ChunkEntityManager;
import cubyz.world.entity.Entity;
import cubyz.world.items.ItemStack;
import cubyz.world.save.BlockPalette;
import cubyz.world.terrain.biomes.Biome;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import pixelguys.json.JsonObject;

import java.util.Arrays;

//TODO:
public class ClientWorld extends World {
	private ServerConnection serverConnection;
	private ClientPlayer player;
	float ambientLight = 0f;
	Vector4f clearColor = new Vector4f(0, 0, 0, 1.0f);

	public ClientWorld(String ip, String playerName, Class<?> chunkProvider) {
		super("server", chunkProvider);

		//wio = new WorldIO(this, new File("saves/" + name));
		serverConnection = new ServerConnection(ip, 5679, 5678, playerName);

		player = new ClientPlayer(0);
		JsonObject handshakeResult = serverConnection.doHandShake(playerName);
		blockPalette = new BlockPalette(handshakeResult.getObjectOrNew("blockPalette"));
		player.loadFrom(handshakeResult.getObjectOrNew("player"), this);

		if(Server.world != null) {
			// Share the registries of the local server:
			registries = Server.world.getCurrentRegistries();
		} else {
			registries = new CurrentWorldRegistries(this, "serverAssets/", blockPalette);
		}

		// Call mods for this new world. Mods sometimes need to do extra stuff for the specific world.
		ModLoader.postWorldGen(registries);


	}

	public ClientPlayer getLocalPlayer() {
		return player;
	}

	public Vector4f getClearColor() {
		return clearColor;
	}

	public float getGlobalLighting() {
		return ambientLight;
	}

	public void update() {
		int dayCycle = World.DAY_CYCLE;
		// Ambient light
		{
			int dayTime = Math.abs((int)(gameTime % dayCycle) - (dayCycle >> 1));
			if (dayTime < (dayCycle >> 2)-(dayCycle >> 4)) {
				ambientLight = 0.1f;
				clearColor.x = clearColor.y = clearColor.z = 0;
			} else if (dayTime > (dayCycle >> 2)+(dayCycle >> 4)) {
				ambientLight = 1.0f;
				clearColor.x = clearColor.y = 0.8f;
				clearColor.z = 1.0f;
			} else {
				//b:
				if (dayTime > (dayCycle >> 2)) {
					clearColor.z = 1.0f*(dayTime-(dayCycle >> 2))/(dayCycle >> 4);
				} else {
					clearColor.z = 0.0f;
				}
				//g:
				if (dayTime > (dayCycle >> 2)+(dayCycle >> 5)) {
					clearColor.y = 0.8f;
				} else if (dayTime > (dayCycle >> 2)-(dayCycle >> 5)) {
					clearColor.y = 0.8f+0.8f*(dayTime-(dayCycle >> 2)-(dayCycle >> 5))/(dayCycle >> 4);
				} else {
					clearColor.y = 0.0f;
				}
				//r:
				if (dayTime > (dayCycle >> 2)) {
					clearColor.x = 0.8f;
				} else {
					clearColor.x = 0.8f+0.8f*(dayTime-(dayCycle >> 2))/(dayCycle >> 4);
				}
				dayTime -= dayCycle >> 2;
				dayTime <<= 3;
				ambientLight = 0.55f + 0.45f*dayTime/(dayCycle >> 1);
			}
		}
	}

	@Override
	public void generate() {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void forceSave() {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void addEntity(Entity ent) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void removeEntity(Entity ent) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void setEntities(Entity[] arr) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public boolean isValidSpawnLocation(int x, int z) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void removeBlock(int x, int y, int z) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void placeBlock(int x, int y, int z, int b) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void drop(ItemStack stack, Vector3d pos, Vector3f dir, float velocity, int pickupCooldown) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void drop(ItemStack stack, Vector3d pos, Vector3f dir, float velocity) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void updateBlock(int x, int y, int z, int block) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void queueChunk(ChunkData ch) {
		Protocols.CHUNK_REQUEST.sendRequest(serverConnection, ch);
	}

	@Override
	public void seek(int x, int y, int z, int renderDistance) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public MetaChunk getMetaChunk(int wx, int wy, int wz) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public NormalChunk getChunk(int wx, int wy, int wz) {
		RenderOctTree.OctTreeNode node = Cubyz.chunkTree.findNode(new ChunkData(wx, wy, wz, 1));
		if(node == null)
			return null;
		ChunkData chunk = node.mesh.getChunk();
		if(chunk instanceof NormalChunk)
			return (NormalChunk)chunk;
		return null;
	}

	@Override
	public ChunkEntityManager getEntityManagerAt(int wx, int wy, int wz) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public BlockEntity getBlockEntity(int x, int y, int z) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public void cleanup() {
		throw new IllegalArgumentException("a");
	}

	@Override
	public int getHeight(int wx, int wz) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public CurrentWorldRegistries getCurrentRegistries() {
		return registries;
	}

	@Override
	public Biome getBiome(int wx, int wy, int wz) {
		throw new IllegalArgumentException("a");
	}

	@Override
	public int getLight(int x, int y, int z, Vector3f sunLight, boolean easyLighting) {
		NormalChunk ch = getChunk(x, y, z);
		if (ch == null || !ch.isLoaded() || !easyLighting)
			return 0xffffffff;
		return ch.getLight(x & Chunk.chunkMask, y & Chunk.chunkMask, z & Chunk.chunkMask);
	}
	@Override
	public void getLight(NormalChunk ch, int x, int y, int z, int[] array) {
		int block = getBlock(x, y, z);
		if (block == 0) return;
		int selfLight = Blocks.light(block);
		x--;
		y--;
		z--;
		for(int ix = 0; ix < 3; ix++) {
			for(int iy = 0; iy < 3; iy++) {
				for(int iz = 0; iz < 3; iz++) {
					array[ix + iy*3 + iz*9] = getLight(ch, x+ix, y+iy, z+iz, selfLight);
				}
			}
		}
	}
	@Override
	protected int getLight(NormalChunk ch, int x, int y, int z, int minLight) {
		if (x - ch.wx != (x & Chunk.chunkMask) || y - ch.wy != (y & Chunk.chunkMask) || z - ch.wz != (z & Chunk.chunkMask))
			ch = getChunk(x, y, z);
		if (ch == null || !ch.isLoaded())
			return 0xff000000;
		int light = ch.getLight(x & Chunk.chunkMask, y & Chunk.chunkMask, z & Chunk.chunkMask);
		// Make sure all light channels are at least as big as the minimum:
		if ((light & 0xff000000) >>> 24 < (minLight & 0xff000000) >>> 24) light = (light & 0x00ffffff) | (minLight & 0xff000000);
		if ((light & 0x00ff0000) < (minLight & 0x00ff0000)) light = (light & 0xff00ffff) | (minLight & 0x00ff0000);
		if ((light & 0x0000ff00) < (minLight & 0x0000ff00)) light = (light & 0xffff00ff) | (minLight & 0x0000ff00);
		if ((light & 0x000000ff) < (minLight & 0x000000ff)) light = (light & 0xffffff00) | (minLight & 0x000000ff);
		return light;
	}
}
