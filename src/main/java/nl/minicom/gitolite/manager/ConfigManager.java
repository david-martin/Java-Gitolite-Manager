package nl.minicom.gitolite.manager;

import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.SyncFailedException;
import java.util.Map;
import java.util.Set;

import nl.minicom.gitolite.manager.exceptions.ServiceUnavailable;
import nl.minicom.gitolite.manager.git.GitManager;
import nl.minicom.gitolite.manager.git.JGitManager;
import nl.minicom.gitolite.manager.io.ConfigReader;
import nl.minicom.gitolite.manager.io.ConfigWriter;
import nl.minicom.gitolite.manager.io.KeyReader;
import nl.minicom.gitolite.manager.io.KeyWriter;
import nl.minicom.gitolite.manager.models.Config;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

/**
 * The {@link ConfigManager} class is designed to be used by developers who wish
 * to manage their gitolite configuration.
 * 
 * @author Michael de Jong <michaelj@minicom.nl>
 */
public class ConfigManager {

  private static final String KEY_DIRECTORY_NAME = "keydir";
  private static final String CONF_FILE_NAME = "gitolite.conf";
  private static final String CONF_DIRECTORY_NAME = "conf";

  /**
   * Constructs a {@link ConfigManager} which is based on the provided URI.
   * 
   * @param gitUri The URI of the remote configuration repository.
   * 
   * @return A {@link ConfigManager} which allows a developer to manipulate the
   *         configuration repository.
   */
  public static ConfigManager create(String gitUri) {
    return create(gitUri, null);
  }

  /**
   * Constructs a {@link ConfigManager} which is based on the provided URI and
   * {@link CredentialsProvider}.
   * 
   * @param gitUri The URI of the remote configuration repository.
   * 
   * @param credentialProvider The {@link CredentialsProvider} which handles
   *           the authentication of the git user who accesses the remote
   *           repository containing the configuration.
   * 
   * @return A {@link ConfigManager} which allows a developer to manipulate the
   *         configuration repository.
   */
  public static ConfigManager create(String gitUri, CredentialsProvider credentialProvider) {
    return create(gitUri, Files.createTempDir(), credentialProvider);
  }

  /**
   * Constructs a {@link ConfigManager} which is based on the provided URI, a
   * working directory and {@link CredentialsProvider}.
   * 
   * @param gitUri The URI of the remote configuration repository.
   * 
   * @param workingDirectory The directory where the configuration repository
   *           needs to be cloned to.
   * 
   * @param credentialProvider The {@link CredentialsProvider} which handles
   *           the authentication of the git user who accesses the remote
   *           repository containing the configuration.
   * 
   * @return A {@link ConfigManager} which allows a developer to manipulate the
   *         configuration repository.
   */
  public static ConfigManager create(String gitUri, File workingDirectory, CredentialsProvider credentialProvider) {
    return new ConfigManager(gitUri, new JGitManager(workingDirectory, credentialProvider));
  }

  private final String gitUri;
  private final GitManager git;
  private final File workingDirectory;

  private Config config;

  /**
   * Constructs a new {@link ConfigManager} object.
   * 
   * @param gitUri The URI to clone from and push changes to.
   * 
   * @param gitManager The {@link GitManager} which will handle the git
   *           operations.
   */
  ConfigManager(String gitUri, GitManager gitManager) {
    Preconditions.checkNotNull(gitUri);
    Preconditions.checkNotNull(gitManager);

    this.gitUri = gitUri;
    git = gitManager;
    workingDirectory = git.getWorkingDirectory();
  }

  /**
   * This method reads and interprets the configuration repository, and returns
   * a representation.
   * 
   * @return A {@link Config} object, representing the configuration
   *         repository.
   * 
   * @throws ServiceUnavailable If the service could not be reached.
   * 
   * @throws IOException If one or more files in the repository could not be
   *            read.
   */
  public Config getConfig() throws IOException, ServiceUnavailable {
    try {
      if (!new File(workingDirectory, ".git").exists()) {
        git.clone(gitUri);
      }
    } catch (IOException e) {
      throw new ServiceUnavailable(e);
    } catch (ServiceUnavailable e) {
      throw new ServiceUnavailable(e);
    }

    if (git.pull() || config == null) {
      config = readConfig();
    }
    return config;
  }

  /**
   * This method writes the current state of the internal {@link Config} object
   * to the git repository and commits and pushes the changes.
   * 
   * @throws IOException In case the operation failed, when writing the new
   *            configuration, committing the changes or pushing them to the
   *            remote repository.
   * 
   * @throws ServiceUnavailable If the remote service could not be reached.
   */
  public boolean applyConfig() throws SyncFailedException, IOException, ServiceUnavailable {
    return applyConfig(null);
  }
  
  // overload method
  /**
   * 
   * @param message Commit message
   */
  public boolean applyConfig(String message) throws SyncFailedException, IOException, ServiceUnavailable {
    if (config == null) {
      throw new IllegalStateException("Config has not yet been loaded!");
    }
    
    boolean pushSuccess = false;
    
    ConfigWriter.write(config, new FileWriter(getConfigFile()));
    Set<File> writtenKeys = KeyWriter.writeKeys(config, ensureKeyDirectory());
    Set<File> orphanedKeyFiles = listKeys();
    orphanedKeyFiles.removeAll(writtenKeys);

    for (File orphanedKeyFile : orphanedKeyFiles) {
      git.remove("keydir/" + orphanedKeyFile.getName());
    }

    // commit changes, we're ready to go
    if (message != null) {
      git.commitChanges(message);
    } else {
      git.commitChanges();
    }

    Map<RemoteRefUpdate, Status> updates = null;
    try {
      updates = git.push();
    } catch (IOException e) {
      throw new ServiceUnavailable(e);
    }
    
    // see if all updates (probably only 1) were ok
    Multiset<Status> statuses = HashMultiset.create();
    statuses.addAll(updates.values());
    
    if (statuses.count(Status.OK) != statuses.size()) {
      // throw exception
      String remoteUpdatesMsg = "";
      for (RemoteRefUpdate update : updates.keySet()) {
        remoteUpdatesMsg += "{update.getMessage()=" + update.getMessage() + ",update.getStatus()=" + update.getStatus() + "},";
      }
      // something went wrong and at least 1 update failed
      if (statuses.contains(Status.REJECTED_NONFASTFORWARD) || statuses.contains(Status.REJECTED_OTHER_REASON) || statuses.contains(Status.REJECTED_REMOTE_CHANGED)) {
        pushSuccess = false;
      } else {
        throw new IOException("Git push failed: workingDir=" + git.getWorkingDirectory() + " RemoteRefUpdates=" + remoteUpdatesMsg);
      }
    } else {
      pushSuccess = true;
    }
      
    return pushSuccess;
  }

  private Set<File> listKeys() {
    Set<File> keys = Sets.newHashSet();

    File keyDir = new File(workingDirectory, "keydir");
    if (keyDir.exists()) {
      File[] keyFiles = keyDir.listFiles(new FileFilter() {
        @Override
        public boolean accept(File file) {
          return file.getName().endsWith(".pub");
        }
      });

      for (File keyFile : keyFiles) {
        keys.add(keyFile);
      }
    }

    return keys;
  }

  private Config readConfig() throws IOException {
    Config config = ConfigReader.read(new FileReader(getConfigFile()));
    KeyReader.readKeys(config, ensureKeyDirectory());
    return config;
  }

  private File getConfigFile() {
    File confDirectory = new File(workingDirectory, CONF_DIRECTORY_NAME);
    if (!confDirectory.exists()) {
      throw new IllegalStateException("Could not open " + CONF_DIRECTORY_NAME + "/ directory!");
    }

    File confFile = new File(confDirectory, CONF_FILE_NAME);
    return confFile;
  }

  private File ensureKeyDirectory() {
    File keyDir = new File(workingDirectory, KEY_DIRECTORY_NAME);
    keyDir.mkdir();
    return keyDir;
  }

}