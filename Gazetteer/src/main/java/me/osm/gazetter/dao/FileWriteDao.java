package me.osm.gazetter.dao;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import me.osm.gazetter.Options;
import me.osm.gazetter.striper.GeoJsonWriter;
import me.osm.gazetter.utils.FileUtils;

/**
 * Abstraction for writing in multiple "files"
 * */
public class FileWriteDao implements WriteDao {

	private final boolean PARTITION_STRIPE_FILES;
	
	/* 
	 * Cap max amount of writers (of open files).
	 * 
	 * XXX: cause error, unexpected end of zip stream. Turned off by default
	 */
	private final int MAX_OPEN_FILES;
	private final Map<String, PrintWriter> writers;
	
	private static class WritersLRUcacheMap extends LinkedHashMap<String, PrintWriter> {
		private static final long serialVersionUID = 8645291126106604903L;
		private final int capacity;

		public WritersLRUcacheMap(int capacity, float loadFactor) {
			super(capacity, loadFactor, true);
			this.capacity = capacity;
		}

		protected boolean removeEldestEntry(Map.Entry<String, PrintWriter> eldest) {
			if (size() > capacity) {
				String key = eldest.getKey();

				PrintWriter w = eldest.getValue();
				w.flush();
				w.close();
				
				System.out.println("Evict writer for " + key);
				
				return true;
			}
			return false;
		}
	}
	
	private final File dir;
	
	
	/**
	 * @param dir directory for files
	 */
	public FileWriteDao(File dir, boolean partition, int maxFiles) {
		PARTITION_STRIPE_FILES = true;
		MAX_OPEN_FILES = maxFiles;
		
		this.dir = dir;
		
		writers = MAX_OPEN_FILES == 0 ? new HashMap<String, PrintWriter>() : 
			new WritersLRUcacheMap(MAX_OPEN_FILES, 0.75f);
		
		dir.mkdirs();
	}
	
	@Override
	public void write(String line, String key) throws IOException {
		PrintWriter w = getWriter(key);
		synchronized (w) {
			assert GeoJsonWriter.getTimestamp(line) != null;
			w.println(line);
		}
	}

	private PrintWriter getWriter(String key) throws IOException {

		boolean useGZ = Options.get().isCompress();

		PrintWriter pw = writers.get(key);
		if(pw == null) {
			synchronized(writers) {
				pw = writers.get(key);
				if(pw == null) {

					File file = new File(dir.getAbsolutePath() + "/" + key + (useGZ ? ".gz" : ""));
					
					if (PARTITION_STRIPE_FILES) {
						int partition = nextPartitionNumber(key);
						
						file = new File(dir.getAbsolutePath() + "/" + key + "." + partition + (useGZ ? ".gz" : ""));
					}
					pw = FileUtils.getPrintWriterWithGZAppendTrick(file, true);

					if(!file.exists()) {
						file.createNewFile();
					}
					
					writers.put(key, pw);
				}
			}
		}
		return pw;
	}

	private int nextPartitionNumber(String key) throws IOException {
		List<Integer> existing = Files.walk(dir.toPath())
			.filter(Files::isRegularFile)
			.map(p -> p.getFileName().toString())
			.filter(name -> name.startsWith(key))
			.map(name -> name.replace(key, "").replace(".gz", "").replace(".", ""))
			.map(name -> {
				try {
					return Integer.parseInt(name);
				}
				catch (Exception e) {
					return 0;
				}
			})
			.collect(Collectors.toList());
		
		int partition = 0;
		if (!existing.isEmpty()) {
			partition = existing.stream().reduce(Math::max).get() + 1;
		}
		return partition;
	}

	@Override
	public void close() {
		synchronized (writers) {
			for(PrintWriter writer : writers.values()) {
				writer.flush();
				writer.close();
			}
			writers.clear();
		}
	}

}
