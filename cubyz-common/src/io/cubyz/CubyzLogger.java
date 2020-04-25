package io.cubyz;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class CubyzLogger extends Logger {

	public static boolean useDefaultHandler = false;
	
	/**
	 * Shortcut for {@link CubyzLogger#instance}
	 */
	public static CubyzLogger i;
	
	public static CubyzLogger instance;
	
	static {
		instance = i = new CubyzLogger();
	}
	
	public void throwable(Throwable t) {
		StringWriter w = new StringWriter();
		PrintWriter pw = new PrintWriter(w);
		t.printStackTrace(pw);
		pw.close();
		log(Level.SEVERE, w.toString());
	}
	
	protected CubyzLogger() {
		super(Logger.GLOBAL_LOGGER_NAME, null);
		setUseParentHandlers(true);
		this.setParent(Logger.getGlobal());
		this.setLevel(Level.ALL);
		this.setFilter(null);
		File logs = new File("logs");
		if (!logs.exists()) {
			logs.mkdir();
		}
		if (!useDefaultHandler) {
			setUseParentHandlers(false);
			this.addHandler(new Handler() {
				
				DateFormat format = new SimpleDateFormat("EEE, dd/MM/yy HH:mm:ss");
				DateFormat logFormat = new SimpleDateFormat("YYYY-MM-dd-HH-mm-ss");
				
				FileOutputStream latestLogOutput;
				
				{
					try {
						latestLogOutput = new FileOutputStream("logs/latest.log");
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				
				@Override
				public void close() throws SecurityException {
					try {
						flush();
						if (latestLogOutput != null)
							latestLogOutput.close();
						latestLogOutput = null;
					} catch (Exception e) {
						System.err.println(e);
						throw new SecurityException(e);
					}
				}
	
				@Override
				public void flush() {
					System.out.flush();
					try {
						if (latestLogOutput != null) {
							latestLogOutput.flush();
							Files.copy(Paths.get("logs/latest.log"), Paths.get("logs/" + logFormat.format(Calendar.getInstance().getTime()) + ".log"));
						}
					} catch (Exception e) {
						throwing("CubyzLogger", "flush", e);
					}
				}
	
				@Override
				public void publish(LogRecord log) {
					Date date = new Date(log.getMillis());
					
					StringBuilder sb = new StringBuilder();
					
					sb.append("[" + format.format(date) + " | " + log.getLevel() + " | " + Thread.currentThread().getName() + "] ");
					sb.append(log.getMessage() + "\n");
					
					if (log.getLevel().intValue() >= Level.WARNING.intValue()) {
						System.err.print(sb.toString());
					} else {
						System.out.print(sb.toString());
					}
					
					if (latestLogOutput != null) {
						try {
							latestLogOutput.write(sb.toString().getBytes("UTF-8"));
						} catch (Exception e) {
							throw new Error(e);
						}
					}
				}
				
			});
		}
	}

}
