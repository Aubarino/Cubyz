package io.cubyz.client.loading;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Set;

import org.reflections.Reflections;

import io.cubyz.ClientOnly;
import io.cubyz.Configuration;
import io.cubyz.Constants;
import io.cubyz.CubyzLogger;
import io.cubyz.api.CubyzRegistries;
import io.cubyz.api.Mod;
import io.cubyz.api.Resource;
import io.cubyz.api.Side;
import io.cubyz.base.BaseMod;
import io.cubyz.blocks.Block;
import io.cubyz.client.Cubyz;
import io.cubyz.entity.EntityType;
import io.cubyz.modding.ModLoader;
import io.cubyz.ui.LoadingGUI;
import io.cubyz.utils.ResourceContext;
import io.cubyz.utils.ResourceManager;
import io.jungle.util.StaticMeshesLoader;

public class LoadThread extends Thread {

	static int i = -1;
	static Runnable run;
	static Runnable run2;
	static ArrayList<Runnable> runnables = new ArrayList<>();
	
	public static void addOnLoadFinished(Runnable run) {
		runnables.add(run);
	}
	
	public void run() {
		setName("Load-Thread");
		Configuration.load();
		LoadingGUI l = LoadingGUI.getInstance();
		CubyzLogger log = CubyzLogger.instance;
		l.setStep(1, 0, 0);
		
		l.setStep(2, 0, 0); // load mods
		
		// Load Mods (via reflection)
		ArrayList<Object> mods = new ArrayList<>();
		ArrayList<File> modSearchPath = new ArrayList<>();
		modSearchPath.add(new File("mods"));
		modSearchPath.add(new File("mods/" + Constants.GAME_VERSION));
		ArrayList<URL> modUrl = new ArrayList<>();
		
		for (File sp : modSearchPath) {
			if (!sp.exists()) {
				sp.mkdirs();
			}
			for (File mod : sp.listFiles()) {
				if (mod.isFile()) {
					try {
						modUrl.add(mod.toURI().toURL());
						System.out.println("- Add " + mod.toURI().toURL());
					} catch (MalformedURLException e) {
						e.printStackTrace();
					}
				}
			}
		}
		
		URLClassLoader loader = new URLClassLoader(modUrl.toArray(new URL[modUrl.size()]), LoadThread.class.getClassLoader());
		log.info("Seeking mods..");
		//try {
			//Enumeration<URL> urls = loader.findResources("CubeComputers");
			//System.out.println("URLs");
			//while (urls.hasMoreElements()) {
			//	URL url = urls.nextElement();
			//	System.out.println("URL - " + url);
			//}
		//} catch (IOException e1) {
		//	e1.printStackTrace();
		//}
		long start = System.currentTimeMillis();
		Reflections reflections = new Reflections("", loader); // load all mods
		Set<Class<?>> allClasses = reflections.getTypesAnnotatedWith(Mod.class);
		long end = System.currentTimeMillis();
		log.info("Took " + (end - start) + "ms for reflection");
		if (!allClasses.contains(BaseMod.class)) {
			allClasses.add(BaseMod.class);
			log.info("Manually adding BaseMod (probably on distributed JAR)");
		}
		for (Class<?> cl : allClasses) {
			log.info("Mod class present: " + cl.getName());
			try {
				mods.add(cl.getConstructor().newInstance());
			} catch (Exception e) {
				log.warning("Error while loading mod:");
				e.printStackTrace();
			}
		}
		log.info("Mod list complete");
		
		// TODO re-add pre-init
		l.setStep(2, 0, mods.size());
		for (int i = 0; i < mods.size(); i++) {
			l.setStep(2, i+1, mods.size());
			Object mod = mods.get(i);
			log.info("Pre-initiating " + mod);
			ModLoader.preInit(mod, Side.CLIENT);
		}
		
		// Between pre-init and init code
		l.setStep(3, 0, mods.size());
		
		for (int i = 0; i < mods.size(); i++) {
			l.setStep(3, i+1, mods.size());
			Object mod = mods.get(i);
			log.info("Initiating " + mod);
			ModLoader.init(mod);
		}
		
		Object lock = new Object();
		run = new Runnable() {
			public void run() {
				i++;
				boolean finishedMeshes = false;
				if (i < CubyzRegistries.BLOCK_REGISTRY.registered().length || i < CubyzRegistries.ENTITY_REGISTRY.registered().length) {
					if(i < CubyzRegistries.BLOCK_REGISTRY.registered().length) {
						Block b = (Block) CubyzRegistries.BLOCK_REGISTRY.registered()[i];
						ClientOnly.createBlockMesh.accept(b);
					}
					if(i < CubyzRegistries.ENTITY_REGISTRY.registered().length) {
						EntityType e = (EntityType) CubyzRegistries.ENTITY_REGISTRY.registered()[i];
						ClientOnly.createEntityMesh.accept(e);
					}
					if(i < CubyzRegistries.BLOCK_REGISTRY.registered().length-1 || i < CubyzRegistries.ENTITY_REGISTRY.registered().length-1) {
						Cubyz.renderDeque.add(run);
						l.setStep(4, i+1, CubyzRegistries.BLOCK_REGISTRY.registered().length);
					} else {
						finishedMeshes = true;
						synchronized (lock) {
							lock.notifyAll();
						}
					}
				} else {
					finishedMeshes = true;
					synchronized (lock) {
						lock.notifyAll();
					}
				}
				if (finishedMeshes) {
					try {
						Cubyz.skyBodyMesh = StaticMeshesLoader.load(
								ResourceManager.lookupPath(ResourceManager.contextToLocal(ResourceContext.MODEL3D, new Resource("cubyz:block.obj"))),
								ResourceManager.lookupPath("cubyz/models/3d/"))[0];
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		};
		Cubyz.renderDeque.add(run);
		try {
			synchronized (lock) {
				lock.wait();
			}
		} catch (InterruptedException e) {
			return;
		}
		
		l.setStep(5, 0, mods.size());
		for (int i = 0; i < mods.size(); i++) {
			l.setStep(5, i+1, mods.size());
			Object mod = mods.get(i);
			log.info("Post-initiating " + mod);
			ModLoader.postInit(mod);
		}
		l.finishLoading();
		
		for (Runnable r : runnables) {
			r.run();
		}
		
		System.gc();
	}
	
}
