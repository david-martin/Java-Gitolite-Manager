package nl.minicom.gitolite.manager.git;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import nl.minicom.gitolite.manager.exceptions.ServiceUnavailable;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.InitCommand;
import org.eclipse.jgit.api.PullCommand;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;

import com.google.common.base.Preconditions;

/**
 * The {@link JGitManager} class is responsible for communicating with the
 * remote git repository containing the gitolite configuration.
 * 
 * @author Michael de Jong <michaelj@minicom.nl>
 */
public class JGitManager implements GitManager {

	private final File workingDirectory;
	private final CredentialsProvider credentialProvider;

	private Git git;

	/**
	 * Constructs a new {@link JGitManager} object.
	 * 
	 * @param workingDirectory The working directory where we will clone to, and
	 *           manipulate the configuration files in. It's recommended to use a
	 *           temporary directory, unless you wish to keep the git repository.
	 * 
	 * @param credentialProvider The {@link CredentialsProvider} to use to
	 *           authenticate when cloning, pulling or pushing, from or to.
	 */
	public JGitManager(File workingDirectory, CredentialsProvider credentialProvider) {
		Preconditions.checkNotNull(workingDirectory);
		this.workingDirectory = workingDirectory;
		this.credentialProvider = credentialProvider;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#open()
	 */
	@Override
	public void open() throws IOException {
		git = Git.open(workingDirectory);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#remove(java.lang.String)
	 */
	@Override
	public void remove(String filePattern) throws IOException {
		RmCommand rm = git.rm();
		rm.addFilepattern(filePattern);
		try {
			rm.call();
		} catch (GitAPIException e) {
			throw new IOException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#clone(java.lang.String)
	 */
	@Override
	public void clone(String uri) throws IOException, ServiceUnavailable {
		clone(uri, null, -1);
	}
	
	/*
     * (non-Javadoc)
     * 
     * @see nl.minicom.gitolite.manager.git.GitManager#clone(java.lang.String, java.lang.String)
     */
    @Override
	public void clone(String uri, String branch, int timeout) throws IOException, ServiceUnavailable {
	  Preconditions.checkNotNull(uri);

      CloneCommand clone = Git.cloneRepository();
      clone.setDirectory(workingDirectory);
      clone.setURI(uri);
      clone.setCredentialsProvider(credentialProvider);
      if( -1 != timeout){
        clone.setTimeout(timeout);
      }
      if(null != branch && !"".equals(branch)){
        clone.setBranch(branch);
      }
      try {
          git = clone.call();
      } catch (NullPointerException e) {
          throw new ServiceUnavailable(e);
      } catch (GitAPIException e) {
          throw new IOException(e);
      }
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#init()
	 */
	@Override
	public void init() throws IOException {
		InitCommand initCommand = Git.init();
		initCommand.setDirectory(workingDirectory);
		try {
			git = initCommand.call();
		} catch (GitAPIException e) {
			throw new IOException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#pull()
	 */
	@Override
	public boolean pull() throws IOException, ServiceUnavailable {
		return pull(-1);
	}
	
	/*
     * (non-Javadoc)
     * 
     * @see nl.minicom.gitolite.manager.git.GitManager#pull()
     */
    @Override
    public boolean pull(int timeout) throws IOException, ServiceUnavailable {
        try {
            PullCommand pull = git.pull();
            if( -1 != timeout){
              pull.setTimeout(timeout);
            }
            return !pull.call().getFetchResult().getTrackingRefUpdates().isEmpty();
        } catch (NullPointerException e) {
            throw new ServiceUnavailable(e);
        } catch (GitAPIException e) {
            throw new IOException(e);
        }
    }

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#commitChanges()
	 */
	@Override
	public void commitChanges() throws IOException {
		add(git, ".");
		commit(git, "Changed config...");
	}
	
	public void commitChanges(String message) throws IOException{
	  add(git, ".");
	  commit(git, message);
	}

	private void commit(Git git, String message) throws IOException {
		CommitCommand commit = git.commit();
		try {
			commit.setMessage(message).call();
		} catch (GitAPIException e) {
			throw new IOException(e);
		} catch (JGitInternalException e) {
			throw new IOException(e);
		}
	}
	
	@Override
	public void uncommitChanges() throws IOException {
	  // undo the commit
	  ResetCommand reset = git.reset();
	  reset.setMode(ResetType.HARD);
	  reset.setRef("HEAD^");
	  try {
      reset.call();
    } catch (GitAPIException e) {
      throw new IOException(e);
    } catch (JGitInternalException e) {
      throw new IOException(e);
    }
	}

	private void add(Git git, String pathToAdd) throws IOException {
		AddCommand add = git.add();
		try {
			add.addFilepattern(pathToAdd).call();
		} catch (GitAPIException e) {
			throw new IOException(e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#push()
	 */
	@Override
	public Map<RemoteRefUpdate, Status> push() throws IOException, ServiceUnavailable {
	  Map<RemoteRefUpdate, Status> updates = new HashMap<RemoteRefUpdate, Status>();
		try {
			PushCommand push = git.push();
			push.setCredentialsProvider(credentialProvider);
			Iterable<PushResult> results = push.call();
			// copy all ref update statuses into collection for returning
			for (PushResult pushResult : results) {
			  for (RemoteRefUpdate update : pushResult.getRemoteUpdates()) {
			    updates.put(update, update.getStatus());
        }
      }
		} catch (NullPointerException e) {
			throw new ServiceUnavailable(e);
		} catch (GitAPIException e) {
			throw new IOException(e);
		} catch (JGitInternalException e) {
      throw new IOException(e);
    }
		return updates;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.minicom.gitolite.manager.git.GitManager#getWorkingDirectory()
	 */
	@Override
	public File getWorkingDirectory() {
		return workingDirectory;
	}

}