package nl.minicom.gitolite.manager.io;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.Map.Entry;
import java.util.Set;

import nl.minicom.gitolite.manager.models.Config;
import nl.minicom.gitolite.manager.models.User;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

/**
 * This class contains a method to write all registered SSH keys in 
 * a specified {@link Config} object, to a specified directory.
 * 
 * @author Michael de Jong <michaelj@minicom.nl>
 */
public final class KeyWriter {

	/**
	 * This method writes all SSH keys currently present in the provided {@link Config} object
	 * to the specified key directory. Existing keys are not removed, but may be overwritten.
	 * 
	 * @param config
	 * 	The {@link Config} object, containing all the SSH keys. This cannot be NULL.
	 * 
	 * @param keyDir
	 * 	The directory where all the keys should be stored. This cannot be NULL.
	 * 
	 * @return
	 * 	A {@link Set} of {@link File} handles of all written SSH key files.
	 * 
	 * @throws IOException
	 * 	If a problem occurred when writing the SSH key files.
	 */
	public static Set<File> writeKeys(Config config, File keyDir) throws SyncFailedException, IOException {
		Preconditions.checkNotNull(config);
		Preconditions.checkNotNull(keyDir);
		Preconditions.checkArgument(keyDir.isDirectory(), "The argument 'keyDir' must be a directory!");

		Set<File> keysWritten = Sets.newHashSet();
		for (User user : config.getUsers()) {
			for (Entry<String, String> keyEntry : user.getKeys().entrySet()) {
				String userName = user.getName();
				String keyName = keyEntry.getKey();
				String keyContent = keyEntry.getValue();
				
				keysWritten.add(createKeyFile(keyDir, userName, keyName, keyContent));
			}
		}
		
		checkForDuplicatesKeys(keyDir);

		return keysWritten;
	}

	private static File createKeyFile(File keyDir, String userName, String name, String content) throws IOException {
		StringBuilder builder = new StringBuilder();
		builder.append(userName);
		if (StringUtils.isNotEmpty(name)) {
			builder.append("@" + name);
		}
		String keyComment = " " + builder.toString();
		builder.append(".pub");
		
		FileWriter writer = null;
		File file = new File(keyDir, builder.toString());
		try {
			writer = new FileWriter(file);
			writer.write(removeComment(content));
			writer.write(keyComment);
		}
		finally {
			if (writer != null) {
				writer.close();
			}
		}
		
		return file;
	}
	
	private static void checkForDuplicatesKeys(File keyDir) throws IOException {
	  Set<String> keys = Sets.newHashSet();

	  File[] keyFiles = keyDir.listFiles();

	  for (File file : keyFiles) {
	    if (file.getName().matches(".*\\.pub\\b")) {
	      String content = FileUtils.readFileToString(file);
	      content = content.substring(0, content.lastIndexOf(" ")); // omit comment
	      if (keys.contains(content)) {
	        throw new SyncFailedException("Duplicate Key:" + content);
	      }
	      keys.add(content);
	    }
    }
	}
	
	private static String removeComment(String key) {
	  String[] keyParts = key.split("\\s");
	  return keyParts[0] + " " + keyParts[1];
	}
	
	private KeyWriter() {
		//Prevent instantiation.
	}
	
}