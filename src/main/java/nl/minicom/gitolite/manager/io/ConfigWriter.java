package nl.minicom.gitolite.manager.io;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;

import nl.minicom.gitolite.manager.models.Config;
import nl.minicom.gitolite.manager.models.Group;
import nl.minicom.gitolite.manager.models.Identifiable;
import nl.minicom.gitolite.manager.models.Permission;
import nl.minicom.gitolite.manager.models.Repository;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.Multimap;

/**
 * This class contains a method to write a configuration file 
 * based on a specified {@link Config} object.
 *
 * @author Michael de Jong <michaelj@minicom.nl>
 */
public final class ConfigWriter {
	
	private static final String PERMISSION_INDENT = "    ";
	private static final int PADDING = 20;

	/**
	 * This method writes a configuration file based on the specified {@link Config} object,
	 * to the specified {@link Writer}.
	 * 
	 * @param config
	 * 	The {@link Config} object to write. This cannot be NULL.
	 * 
	 * @param writer
	 * 	The {@link Writer} to write the configuration to. This cannot be NULL.
	 * 
	 * @throws IOException
	 * 	If the configuration file could not be written.
	 */
	public static void write(Config config, Writer writer) throws IOException {
		Preconditions.checkNotNull(config);
		Preconditions.checkNotNull(writer);
		
		writeGroups(config, writer);
		writeRepositories(config, writer);
		
		writer.flush();
		writer.close();
	}

	private static void writeGroups(Config config, Writer writer) throws IOException {
		Collection<Group> groups = config.getGroups();
		if (!groups.isEmpty()) {
			for (Group group : groups) {
				if (group.getEntityNamesInGroup().isEmpty() || group.getName().equals("@all")) {
					continue;
				}
				writer.write(pad(group.getName(), PADDING) + " = " 
					+ Joiner.on(" ").join(group.getEntityNamesInGroup()) + "\n");
			}
			writer.write("\n");
		}
	}

	private static void writeRepositories(Config config, Writer writer) throws IOException {
		Collection<Repository> repos = config.getRepositories();
		for (Repository repo : repos) {
			writer.write("repo " + repo.getName() + "\n");
			Multimap<Permission, Identifiable> permissions = repo.getPermissions();
			for (Permission right : permissions.keySet()) {
				Collection<Identifiable> entities = permissions.get(right);
				Collection<String> names = Collections2.transform(entities, new Function<Identifiable, String>() {
					@Override
					public String apply(Identifiable entity) {
						return entity.getName();
					}
				});
				
				writer.write(PERMISSION_INDENT + pad(right.getLevel(), PADDING - PERMISSION_INDENT.length()) + " = ");
				writer.write(Joiner.on(" ").join(names) + "\n");
			}
			writer.write("\n");
		}
	}
	
	private static String pad(String input, int limit) {
		String result = input;
		while (result.length() < limit) {
			result += " ";
		}
		return result;
	}
	
	private ConfigWriter() {
		//prevent instantiation.
	}
	
}