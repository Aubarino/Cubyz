package cubyz.utils.datastructures;

/** 
* Implements a simple set associative cache with LRU replacement strategy.
*/

public class Cache<T> {
	public final T[][] cache;
	/**
	 * The cache will be initialized using the given layout.
	 * @param layout first dimension gives the hash size, second dimension gives the associativity.
	 */
	public Cache(T[][] layout) {
		cache = layout;
	}
	
	public int cacheRequests = 0;
	public int cacheMisses = 0;
	
	/**
	 * Tries to find the entry that fits to the supplied hashable.
	 * @param compare
	 * @param index the hash that is fit within cache.length
	 * @return
	 */
	public T find(Object compare, int index) {
		cacheRequests++;
		for(int i = 0; i < cache[index].length; i++) {
			T ret = cache[index][i];
			if(compare.equals(ret)) {
				if(i != 0) { // No need to put it up front when it already is on the front.
					synchronized(cache[index]) {
						System.arraycopy(cache[index], 0, cache[index], 1, i);
						cache[index][i] = ret;
					}
				}
				return ret;
			}
		}
		cacheMisses++;
		return null;
	}
	
	/**
	 * Adds a new object into the cache.
	 * @param t
	 * @param index the hash that is fit within cache.length
	 */
	public void addToCache(T t, int index) {
		System.arraycopy(cache[index], 0, cache[index], 1, cache[index].length - 1);
		cache[index][0] = t;
	}
}
