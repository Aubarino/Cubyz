package io.cubyz.world;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import org.joml.Vector4f;

import io.cubyz.CubyzLogger;
import io.cubyz.Profiler;
import io.cubyz.api.CubyzRegistries;
import io.cubyz.api.IRegistryElement;
import io.cubyz.base.init.ItemInit;
import io.cubyz.base.init.MaterialInit;
import io.cubyz.blocks.Block;
import io.cubyz.blocks.BlockInstance;
import io.cubyz.blocks.CustomOre;
import io.cubyz.blocks.IUpdateable;
import io.cubyz.blocks.Ore;
import io.cubyz.blocks.BlockEntity;
import io.cubyz.entity.Entity;
import io.cubyz.entity.Player;
import io.cubyz.handler.PlaceBlockHandler;
import io.cubyz.handler.RemoveBlockHandler;
import io.cubyz.math.Bits;
import io.cubyz.save.BlockChange;
import io.cubyz.save.WorldIO;
import io.cubyz.world.cubyzgenerators.biomes.Biome;
import io.cubyz.world.generator.LifelandGenerator;
import io.cubyz.world.generator.WorldGenerator;

public class LocalWorld extends World {
	
	private static Random rnd = new Random();

	private String name;
	private List<Chunk> chunks;
	private List<MetaChunk> maps;
	private Chunk [] visibleChunks;
	private int lastX = Integer.MAX_VALUE, lastZ = Integer.MAX_VALUE; // Chunk coordinates of the last chunk update.
	private int doubleRD; // Corresponds to the doubled value of the last used render distance.
	private int lastChunk = -1;
	private ArrayList<Entity> entities = new ArrayList<>();
	
	// Stores a reference to the lists of WorldIO.
	public ArrayList<byte[]> blockData;	
	public ArrayList<int[]> chunkData;
	
	private static int renderDistance = 5;
	private static int MAX_QUEUE_SIZE = renderDistance << 2;
	
	private Block [] blocks;
	private Player player;
	private WorldGenerator generator;
	
	private WorldIO wio;
	
	private List<ChunkGenerationThread> threads = new ArrayList<>();
	private boolean generated;
	
	public static final int DAYCYCLE = 120000; // Length of one in-game day in 100ms. Midnight is at DAYCYCLE/2. Sunrise and sunset each take about 1/16 of the day. Currently set to 20 minutes
	public static final int SEASONCYCLE = DAYCYCLE * 7; // Length of one in-game season in 100ms. Equals to 7 days per season
	long gameTime = 0; // Time of the game in 100ms.
	long milliTime;
	float ambientLight = 0f;
	Vector4f clearColor = new Vector4f(0, 0, 0, 1.0f);
	
	private void queue(Chunk ch) {
		if (!isQueued(ch)) {
			try {
				loadList.put(ch);
			} catch (InterruptedException e) {
				System.err.println("Interrupted while queuing chunk. This is unexpected.");
			}
		}
	}
	
	private boolean isQueued(Chunk ch) {
		Chunk[] list = loadList.toArray(new Chunk[0]);
		for (Chunk ch2 : list) {
			if (ch2 == ch) {
				return true;
			}
		}
		return false;
	}
	
	// synchronized common list for chunk generation
	private volatile BlockingDeque<Chunk> loadList = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
	private class ChunkGenerationThread extends Thread {
		
		public void run() {
			while (true) {
				try {
					Chunk popped = loadList.take();
					synchronousGenerate(popped);
					popped.load();
				} catch (InterruptedException e) {
					break;
				}
			}
		}
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public LocalWorld() {
		this("World");
	}
	
	public LocalWorld(String name) {
		MaterialInit.resetCustom();
		ItemInit.resetCustom();
		this.name = name;
		chunks = new ArrayList<>();
		maps = new ArrayList<>();
		visibleChunks = new Chunk[0];
		
		for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
			ChunkGenerationThread thread = new ChunkGenerationThread();
			thread.setName("Local-Chunk-Thread-" + i);
			thread.setDaemon(true);
			thread.start();
			threads.add(thread);
		}
		
		generator = CubyzRegistries.WORLD_GENERATOR_REGISTRY.getByID("cubyz:lifeland");
		if (generator instanceof LifelandGenerator) {
			((LifelandGenerator) generator).sortGenerators();
		}
		wio = new WorldIO(this, new File("saves/" + name));
		if (wio.hasWorldData()) {
			wio.loadWorldSeed(); // Load the rest in generate(), so custom items can be taken care of correctly.
			generated = true;
		} else {
			wio.saveWorldData();
		}
		milliTime = System.currentTimeMillis();
	}

	
	public void forceSave() {
		wio.saveWorldData();
	}
	
	@Override
	public Player getLocalPlayer() {
		if (player == null) {
			for (Entity e : entities) {
				if (e instanceof Player) {
					player = (Player) e;
				}
			}
			if (player == null) {
				player = (Player) CubyzRegistries.ENTITY_REGISTRY.getByID("cubyz:player").newEntity();
				entities.add(player);
				player.setWorld(this);
			}
		}
		return player;
	}

	@Override
	public List<Chunk> getChunks() {
		return chunks;
	}

	@Override
	public Chunk[] getVisibleChunks() {
		return visibleChunks;
	}

	@Override
	public Block [] getBlocks() {
		return blocks;
	}
	
	@Override
	public Entity[] getEntities() {
		return entities.toArray(new Entity[entities.size()]);
	}
	
	public void addEntity(Entity ent) {
		entities.add(ent);
	}
	
	public void setEntities(Entity[] arr) {
		entities = new ArrayList<Entity>();
		for (Entity e : arr) {
			entities.add(e);
		}
	}
	
	@Override
	public void synchronousSeek(int x, int z) {
		Chunk ch = getChunk(x, z);
		if (!ch.isGenerated()) {
			synchronousGenerate(ch);
			ch.load();
		}
	}
	
	public void synchronousGenerate(Chunk ch) {
		ch.generateFrom(generator);
		wio.saveChunk(ch);
	}
	
	long total = 0;
	long t = System.currentTimeMillis();
	@Override
	public Chunk getChunk(int x, int z) {	// World -> Chunk coordinate system is a bit harder than just x/16. java seems to floor when bigger and to ceil when lower than 0.
		int cx = x;
		cx = cx >> 4;
		int cz = z;
		cz = cz >> 4;
		return _getChunk(cx, cz);
	}
	
	@Override
	public Chunk _getChunk(int x, int z) {
		// First test if the chunk can be found in the list of visible chunks:
		if(x < lastX && x >= lastX-doubleRD && z < lastZ && z >= lastZ-doubleRD) {
			// Sometimes errors happen when resizing the renderDistance. If they happen just go on to iterating through the whole long list.
			// Any seemingly useless checks in here are important!
			int index = (x-(lastX-doubleRD))*doubleRD + (z-(lastZ-doubleRD));
			if(index < visibleChunks.length && index >= 0) {
				Chunk ret = visibleChunks[index];
				if(x == ret.getX() && z == ret.getZ() && ret.isLoaded())
					return ret;
			}
		}
		try {
			if(lastChunk >= 0 && lastChunk < chunks.size() && chunks.get(lastChunk).getX() == x && chunks.get(lastChunk).getZ() == z) {
				return chunks.get(lastChunk);
			}
			for (int i = 0; i < chunks.size(); i++) {
				if (chunks.get(i).getX() == x && chunks.get(i).getZ() == z) {
					lastChunk = i;
					return chunks.get(i);
				}
			}
		} catch(Exception e) {
			System.out.println("Catched NullPointerException:");
			e.printStackTrace();
			chunks.remove(null); // Remove the corruption.
			return _getChunk(x, z); // Just try it again…
		} // Wherever the NullPointerException comes from, it doesn't seem to be a big deal. If another error occurs elsewhere, this might be the source.
		Chunk c = new Chunk(x, z, this, transformData(getChunkData(x, z)));
		// not generated
		chunks.add(c);
		lastChunk = chunks.size()-1;
		return c;
	}
	
	public MetaChunk getMetaChunk(int wx, int wy) {
		for(MetaChunk ch : maps) {
			if(ch.x == wx && ch.y == wy) {
				return ch;
			}
		}
		synchronized(maps) {
			// Now that the thread got access to this part the list might already contain the searched MetaChunk:
			for(MetaChunk ch : maps) {
				if(ch.x == wx && ch.y == wy) {
					return ch;
				}
			}
			// Every time a new MetaChunk is created, go through the list and if the length is at the limit(determined by the renderdistance) remove those that are farthest from the player:
			while(maps.size() > (doubleRD/16 + 4)*(doubleRD/16 + 4)) {
				int max = 0;
				int index = 0;
				for(int i = 0; i < maps.size(); i++) {
					int dist = (maps.get(i).x-player.getPosition().x)*(maps.get(i).x-player.getPosition().x) + (maps.get(i).y-player.getPosition().z)*(maps.get(i).y-player.getPosition().z);
					if(dist > max) {
						max = dist;
						index = i;
					}
				}
				maps.remove(index);
			}
			MetaChunk ch = new MetaChunk(wx, wy, seed, this);
			maps.add(ch);
			return ch;
		}
	}
	public MetaChunk getNoGenerateMetaChunk(int wx, int wy) {
		for(MetaChunk ch : maps) {
			if(ch.x == wx && ch.y == wy) {
				return ch;
			}
		}
		return null;
	}
	
	public float[][] getHeightMapData(int x, int y, int width, int height) {
		int x0 = x&(~255);
		int y0 = y&(~255);
		float[][] map = new float[width][height];
		for(int px = x0; px < x+width; px += 256) {
			for(int py = y0; py < y+height; py += 256) {
				MetaChunk ch = getMetaChunk(px ,py);
				int xS = Math.max(px, x);
				int yS = Math.max(py, y);
				int xE = Math.min(px+256, x+width);
				int yE = Math.min(py+256, y+height);
				for(int cx = xS; cx < xE; cx++) {
					for(int cy = yS; cy < yE; cy++) {
						map[cx-x][cy-y] = ch.heightMap[cx&255][cy&255];
					}
				}
			}
		}
		return map;
	}
	
	public float[][] getHeatMapData(int x, int y, int width, int height) {
		int x0 = x&(~255);
		int y0 = y&(~255);
		float[][] map = new float[width][height];
		for(int px = x0; px < x+width; px += 256) {
			for(int py = y0; py < y+height; py += 256) {
				MetaChunk ch = getMetaChunk(px ,py);
				int xS = Math.max(px, x);
				int yS = Math.max(py, y);
				int xE = Math.min(px+256, x+width);
				int yE = Math.min(py+256, y+height);
				for(int cx = xS; cx < xE; cx++) {
					for(int cy = yS; cy < yE; cy++) {
						map[cx-x][cy-y] = ch.heatMap[cx&255][cy&255];
					}
				}
			}
		}
		return map;
	}
	
	public Biome[][] getBiomeMapData(int x, int y, int width, int height) {
		int x0 = x&(~255);
		int y0 = y&(~255);
		Biome[][] map = new Biome[width][height];
		for(int px = x0; px < x+width; px += 256) {
			for(int py = y0; py < y+height; py += 256) {
				MetaChunk ch = getMetaChunk(px ,py);
				int xS = Math.max(px, x);
				int yS = Math.max(py, y);
				int xE = Math.min(px+256, x+width);
				int yE = Math.min(py+256, y+height);
				for(int cx = xS; cx < xE; cx++) {
					for(int cy = yS; cy < yE; cy++) {
						map[cx-x][cy-y] = ch.biomeMap[cx&255][cy&255];
					}
				}
			}
		}
		return map;
	}
	
	public byte[] getChunkData(int x, int z) { // Gets the data of a Chunk.
		int index = -1;
		for(int i = 0; i < chunkData.size(); i++) {
			int [] arr = chunkData.get(i);
			if(arr[0] == x && arr[1] == z) {
				index = i;
				break;
			}
		}
		if(index == -1) {
			byte[] dummy = new byte[12];
			Bits.putInt(dummy, 0, x);
			Bits.putInt(dummy, 4, z);
			Bits.putInt(dummy, 8, 0);
			return dummy;
		}
		return blockData.get(index);
	}
	
	public ArrayList<BlockChange> transformData(byte[] data) {
		int size = Bits.getInt(data, 8);
		ArrayList<BlockChange> list = new ArrayList<BlockChange>(size);
		for (int i = 0; i < size; i++) {
			list.add(new BlockChange(data, 12 + (i << 4)));
		}
		return list;
	}
	
	public Block getBlock(int x, int y, int z) {
		BlockInstance bi = getBlockInstance(x, y, z);
		if (bi == null)
			return null;
		return bi.getBlock();
	}
	
	@Override
	public BlockInstance getBlockInstance(int x, int y, int z) {
		Chunk ch = getChunk(x, z);
		if (y > World.WORLD_HEIGHT || y < 0)
			return null;
		
		if (ch != null) {
			int cx = x & 15;
			int cz = z & 15;
			BlockInstance bi = ch.getBlockInstanceAt(cx, y, cz);
			return bi;
		} else {
			return null;
		}
	}
	
	@Override
	public void removeBlock(int x, int y, int z) {
		Chunk ch = getChunk(x, z);
		if (ch != null) {
			Block b = ch.getBlockInstanceAt(x&15, y, z&15).getBlock();
			ch.removeBlockAt(x & 15, y, z & 15, true);
			wio.saveChunk(ch);
			wio.saveWorldData();
			for (RemoveBlockHandler hand : removeBlockHandlers) {
				hand.onBlockRemoved(b, x, y, z);
			}
		}
	}
	
	@Override
	public void placeBlock(int x, int y, int z, Block b) {
		Chunk ch = getChunk(x, z);
		if (ch != null) {
			ch.addBlockAt(x & 15, y, z & 15, b, true);
			wio.saveChunk(ch);
			wio.saveWorldData();
			for (PlaceBlockHandler hand : placeBlockHandlers) {
				hand.onBlockPlaced(b, x, y, z);
			}
		}
	}
	
	boolean lqdUpdate;
	boolean loggedUpdSkip;
	final static boolean DO_LATE_UPDATES = false;
	
	BlockEntity[] blockEntities = new BlockEntity[0];
	BlockInstance[] liquids = new BlockInstance[0];
	
	public void update() {
		// Time
		if(milliTime + 100 < System.currentTimeMillis()) {
			milliTime += 100;
			lqdUpdate = true;
			gameTime++; // gameTime is measured in 100ms.
			if ((milliTime + 100) < System.currentTimeMillis()) { // we skipped updates
				if (!loggedUpdSkip) {
					if (DO_LATE_UPDATES) {
						CubyzLogger.i.warning(((System.currentTimeMillis() - milliTime) / 100) + " updates late! Doing them.");
					} else {
						CubyzLogger.i.warning(((System.currentTimeMillis() - milliTime) / 100) + " updates skipped!");
					}
					loggedUpdSkip = true;
				}
				if (DO_LATE_UPDATES) {
					update();
				} else {
					milliTime = System.currentTimeMillis();
				}
			} else {
				loggedUpdSkip = false;
			}
		}
		// Ambient light
		{
			int dayTime = Math.abs((int)(gameTime % DAYCYCLE) - (DAYCYCLE >> 1));
			if(dayTime < (DAYCYCLE >> 2)-(DAYCYCLE >> 4)) {
				ambientLight = 0.1f;
				clearColor.x = clearColor.y = clearColor.z = 0;
			} else if(dayTime > (DAYCYCLE >> 2)+(DAYCYCLE >> 4)) {
				ambientLight = 0.7f;
				clearColor.x = clearColor.y = 0.8f;
				clearColor.z = 1.0f;
			} else {
				//b:
				if(dayTime > (DAYCYCLE >> 2)) {
					clearColor.z = 1.0f*(dayTime-(DAYCYCLE >> 2))/(DAYCYCLE >> 4);
				} else {
					clearColor.z = 0.0f;
				}
				//g:
				if(dayTime > (DAYCYCLE >> 2)+(DAYCYCLE >> 5)) {
					clearColor.y = 0.8f;
				} else if(dayTime > (DAYCYCLE >> 2)-(DAYCYCLE >> 5)) {
					clearColor.y = 0.8f+0.8f*(dayTime-(DAYCYCLE >> 2)-(DAYCYCLE >> 5))/(DAYCYCLE >> 4);
				} else {
					clearColor.y = 0.0f;
				}
				//r:
				if(dayTime > (DAYCYCLE >> 2)) {
					clearColor.x = 0.8f;
				}
				else {
					clearColor.x = 0.8f+0.8f*(dayTime-(DAYCYCLE >> 2))/(DAYCYCLE >> 4);
				}
				dayTime -= (DAYCYCLE >> 2);
				dayTime <<= 3;
				ambientLight = 0.4f + 0.3f*dayTime/(DAYCYCLE >> 1);
			}
		}
		season = (int) ((gameTime/SEASONCYCLE) % 4);
		// Entities
		for (Entity en : entities) {
			en.update();
		}
		// Block Entities
		for (Chunk ch : visibleChunks) {
			if (ch.isLoaded() && ch.blockEntities().size() > 0) {
				blockEntities = ch.blockEntities().values().toArray(blockEntities);
				for (BlockEntity be : blockEntities) {
					if (be == null) continue;
					if (be instanceof IUpdateable) {
						IUpdateable tk = (IUpdateable) be;
						tk.update(false);
						if (tk.randomUpdates()) {
							if (rnd.nextInt(5) <= 1) { // 1/5 chance
								tk.update(true);
							}
						}
					}
				}
			}
		}
		
		// Liquids
		if (gameTime % 3 == 0 && lqdUpdate) {
			lqdUpdate = false;
			//Profiler.startProfiling();
			for (Chunk ch : visibleChunks) {
				if (ch.isLoaded() && ch.liquids().size() > 0) {
					liquids = ch.updatingLiquids().toArray(liquids);
					ch.updatingLiquids().clear();
					for (BlockInstance bi : liquids) {
						if (bi == null) break;
						BlockInstance[] neighbors = bi.getNeighbors(ch);
						for (int i = 0; i < 5; i++) {
							BlockInstance b = neighbors[i];
							if (b == null) {
								int dx = 0, dy = 0, dz = 0;
								switch (i) {
									case 0:
										dx = -1;
									break;
									case 1:
										dx = 1;
										break;
									case 2:
										dz = 1;
										break;
									case 3:
										dz = -1;
										break;
									case 4:
										dy = -1;
										break;
									default:
										System.err.println("(LocalWorld/Liquids) More than 6 nullable neighbors!");
										break;
								}
								if(dy == -1 || (neighbors[4] != null && neighbors[4].getBlock().getBlockClass() != Block.BlockClass.FLUID)) {
									ch.addBlock(bi.getBlock(), bi.getX()+dx, bi.getY()+dy, bi.getZ()+dz);
								}
							}
						}
					}
				}
			}
			//Profiler.printProfileTime("liquid-update");
		}
	}

	private ArrayList<CustomOre> customOres = new ArrayList<>();
	
	public ArrayList<CustomOre> getCustomOres() {
		return customOres;
	}
	
	// Returns the blocks, so their meshes can be created and stored.
	public Block[] generate() {
		if (!generated) seed = rnd.nextInt();
		Random rand = new Random(seed);
		int randomAmount = 9 + (int)(Math.random()*3); // Generate 9-12 random ores.
		blocks = new Block[CubyzRegistries.BLOCK_REGISTRY.registered().length+randomAmount];
		// Set the IDs again every time a new world is loaded. This is necessary, because the random block creation would otherwise mess with it.
		int ID = 0;
		ArrayList<Ore> ores = new ArrayList<Ore>();
		for (IRegistryElement ire : CubyzRegistries.BLOCK_REGISTRY.registered()) {
			Block b = (Block) ire;
			if(!b.isTransparent()) {
				b.ID = ID;
				blocks[ID] = b;
				ID++;
			}
		}
		// Generate random ores:
		for(int i = 0; i < randomAmount; i++) {
			blocks[ID] = CustomOre.random(i, rand);
			customOres.add((CustomOre) blocks[ID]);
			ores.add((Ore)blocks[ID]);
			blocks[ID].ID = ID;
			ID++;
		}
		for (IRegistryElement ire : CubyzRegistries.BLOCK_REGISTRY.registered()) {
			Block b = (Block) ire;
			if(b.isTransparent()) {
				b.ID = ID;
				blocks[ID] = b;
				ID++;
			}
			try {
				ores.add((Ore)b);
			}
			catch(Exception e) {}
		}
		LifelandGenerator.initOres(ores.toArray(new Ore[ores.size()]));
		if(generated) {
			wio.loadWorldData();
		}
		generated = true;
		return blocks;
	}

	@Override
	public void queueChunk(Chunk ch) {
		queue(ch);
	}
	
	@Override
	public void seek(int x, int z) {
		int local = x & 15;
		x >>= 4;
		x += renderDistance;
		if(local > 7)
			x++;
		local = z & 15;
		z >>= 4;
		z += renderDistance;
		if(local > 7)
			z++;
		if(x == lastX && z == lastZ)
			return;
		int doubleRD = renderDistance << 1;
		Chunk [] newVisibles = new Chunk[doubleRD*doubleRD];
		int index = 0;
		int minK = 0;
		for(int i = x-doubleRD; i < x; i++) {
			for(int j = z-doubleRD; j < z; j++) {
				boolean notIn = true;
				for(int k = minK; k < visibleChunks.length; k++) {
					if(visibleChunks[k].getX() == i && visibleChunks[k].getZ() == j) {
						newVisibles[index] = visibleChunks[k];
						// Removes this chunk out of the list of chunks that will be considered in this function.
						visibleChunks[k] = visibleChunks[minK];
						visibleChunks[minK] = newVisibles[index];
						minK++;
						notIn = false;
						break;
					}
				}
				if(notIn) {
					Chunk ch = getChunk(i << 4, j << 4);
					if (!ch.isGenerated()) {
						queueChunk(ch);
					} else {
						ch.setLoaded(true);
					}
					newVisibles[index] = ch;
				}
				index++;
			}
		}
		for(int k = minK; k < visibleChunks.length; k++) {
			visibleChunks[k].setLoaded(false);
			chunks.remove(visibleChunks[k]);
			wio.saveChunk(visibleChunks[k]);
		}
		visibleChunks = newVisibles;
		lastX = x;
		lastZ = z;
		this.doubleRD = doubleRD;
		if (minK != visibleChunks.length) { // if at least one chunk got unloaded
			wio.saveWorldData();
		}
		
		// Check if one of the never loaded chunks is outside of players range.
		// Those chunks were never loaded and therefore don't need to get saved.
		x -= renderDistance;
		z -= renderDistance;
		for(int i = 0; i < chunks.size(); i++) {
			Chunk ch = chunks.get(i);
			int delta = Math.abs(ch.getX()-x);
			if(delta >= renderDistance+2) {
				chunks.remove(ch);
				continue;
			}
			delta = Math.abs(ch.getZ()-z);
			if(delta >= renderDistance+2) {
				chunks.remove(ch);
			}
		}
		
	}
	
	public float getGlobalLighting() {
		return ambientLight;
	}

	@Override
	public long getGameTime() {
		return gameTime;
	}

	@Override
	public void setGameTime(long time) {
		gameTime = time;
	}

	@Override
	public void setRenderDistance(int RD) {
		renderDistance = RD;
		MAX_QUEUE_SIZE = renderDistance << 2;
	}
	
	public int getChunkQueueSize() {
		return loadList.size();
	}

	@Override
	public int getRenderDistance() {
		return renderDistance;
	}

	@Override
	public Vector4f getClearColor() {
		return clearColor;
	}

	@Override
	public void cleanup() {
		// Be sure to dereference and finalize the maximum of things
		try {
			forceSave();
			
			for (Thread thread : threads) {
				thread.interrupt();
				thread.join();
			}
			threads = new ArrayList<>();
			
			chunks = null;
			visibleChunks = null;
			chunkData = null;
			blockData = null;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public BlockEntity getBlockEntity(int x, int y, int z) {
		BlockInstance bi = getBlockInstance(x, y, z);
		Chunk ck = getChunk(bi.getX(), bi.getZ());
		return ck.blockEntities().get(bi);
	}
	
}
