package com.rationaleemotions;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.rationaleemotions.pojo.*;
import com.rationaleemotions.utils.Preconditions;
import com.rationaleemotions.utils.StreamGuzzler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;

/**
 * A JSch backed implementation of {@link SshKnowHow}
 */
class JSchBackedSshKnowHowImpl implements SshKnowHow {
    interface Marker {}

    private static final Logger LOGGER = LoggerFactory.getLogger(Marker.class.getEnclosingClass());

    private static final int MAX_SIZE = 10;
    private SSHUser userInfo;
    private SSHHost hostInfo;
    private Shells shell;
    private String userHomeOnRemoteHost;
    private Session session;

    private JSchBackedSshKnowHowImpl(SSHHost host, SSHUser user, Shells shell) {
        this.hostInfo = host;
        this.userInfo = user;
        this.shell = shell;
    }

    static SshKnowHow newInstance(SSHHost host, SSHUser user, Shells shell) {
        JSchBackedSshKnowHowImpl instance = new JSchBackedSshKnowHowImpl(host, user, shell);
        instance.computeUserHome();
        Runtime.getRuntime().addShutdownHook(new Thread(new SessionCleaner(instance.session)));
        return instance;
    }



    @Override
    public ExecResults executeCommand(String cmd) {
        return runCommand(cmd, null);
    }

    @Override
    public ExecResults executeCommand(String cmd, String dir) {
        return runCommand(cmd, dir);
    }

    @Override
    public ExecResults executeCommand(String cmd, EnvVariable... env) {
        return runCommand(cmd, null, env);
    }

    @Override
    public ExecResults executeCommand(String cmd, String dir, EnvVariable... env) {
        return runCommand(cmd, dir, env);
    }

    @Override
    public ExecResults uploadFile(String remoteLocation, File... localFiles) {
        for (File file : localFiles) {
            Preconditions.checkArgument(file.exists(), "Cannot find [" + file.getAbsolutePath() + "]");
            Preconditions.checkArgument(file.isFile(), "[" + file.getAbsolutePath() + "] is not a file.");
        }
        Preconditions.checkArgument(identify(remoteLocation) == FileStatOutput.DIRECTORY, "[" +
            remoteLocation + "] is not a directory on " + hostInfo.getHostname());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Working with the parameters [" + Arrays.toString(new Object[] {remoteLocation, localFiles})
                + "]");
        }
        List<String> errors = new ArrayList<>();
        try {
            List<Callable<ExecResults>> workers = new ArrayList<>();
            for (File file : localFiles) {
                workers.add(new ScpUploadFileWorker(getSession(), file, fixRemoteLocation(remoteLocation)));
            }
            int poolSize = Math.max(MAX_SIZE, localFiles.length);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Total number of worker threads to be created :" + poolSize);
            }
            ExecutorService executor = Executors.newFixedThreadPool(poolSize);
            List<Future<ExecResults>> execResults = executor.invokeAll(workers);
            executor.shutdown();
            while (! executor.isTerminated()) {
                //Wait for all the tasks to complete.
                TimeUnit.SECONDS.sleep(1);
            }
            for (Future<ExecResults> execResult : execResults) {
                ExecResults res = execResult.get();
                if (res.getReturnCode() != 0) {
                    errors.addAll(res.getError());
                }
            }
        } catch (JSchException | InterruptedException | ExecutionException e) {
            throw new ExecutionFailedException(e);
        }
        int rc = 0;
        if (! errors.isEmpty()) {
            rc = - 1;
        }
        return new ExecResults(new LinkedList<>(), errors, rc);
    }

    @Override
    public ExecResults downloadFile(File localLocation, String... remoteFiles) {
        Preconditions.checkArgument(localLocation.exists(), "Cannot find [" + localLocation.getAbsolutePath() + "]");
        Preconditions.checkArgument(localLocation.isDirectory(), "[" + localLocation.getAbsolutePath() + "] is NOT a"
            + " directory.");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Working with the parameters [" + Arrays
                .toString(new Object[] {localLocation.getAbsolutePath(), remoteFiles})
                + "]");
        }

        List<String> errors = new ArrayList<>();
        try {
            List<Callable<ExecResults>> workers = new ArrayList<>();
            for (String remoteFile : remoteFiles) {
                workers.add(new ScpDownloadFileWorker(getSession(), localLocation, fixRemoteLocation(remoteFile)));
            }
            int poolSize = Math.max(MAX_SIZE, remoteFiles.length);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Total number of worker threads to be created :" + poolSize);
            }
            ExecutorService executor = Executors.newFixedThreadPool(poolSize);
            List<Future<ExecResults>> execResults = executor.invokeAll(workers);
            executor.shutdown();
            while (! executor.isTerminated()) {
                //Wait for all the tasks to complete.
                TimeUnit.SECONDS.sleep(1);
            }
            for (Future<ExecResults> execResult : execResults) {
                ExecResults res = execResult.get();
                if (res.getReturnCode() != 0) {
                    errors.addAll(res.getError());
                }
            }
        } catch (JSchException | InterruptedException | ExecutionException e) {
            throw new ExecutionFailedException(e);
        }

        int rc = 0;
        if (! errors.isEmpty()) {
            rc = - 1;
        }
        return new ExecResults(new LinkedList<>(), errors, rc);
    }

    @Override
    public String getHomeDirectory() {
        if (userHomeOnRemoteHost == null || userHomeOnRemoteHost.trim().isEmpty()) {
            throw new IllegalStateException("Unable to compute Home directory for " + userInfo.getUserName() +
                " on the remote host " + hostInfo.getHostname());
        }
        return userHomeOnRemoteHost;
    }

    @Override
    public ExecResults uploadDirectory(String remoteLocation, File... localDirs) {
        return null;
    }

    @Override
    public ExecResults downloadDirectory(File localLocation, String... remoteDirs) {
        return null;
    }

    private Session getSession() throws JSchException {
        if (session != null) {
            if (! session.isConnected()) {
                session.connect();
            }
            return session;
        }
        JSch jSch = new JSch();
        try {
            if (hostInfo.isDoHostKeyChecks()) {
                jSch.setKnownHosts(userInfo.sshFolderLocation() + File.separator + "known_hosts");
            } else {
                jSch.setHostKeyRepository(new FakeHostKeyRepository());
            }
            jSch.addIdentity(userInfo.privateKeyLocation().getAbsolutePath());
            session = jSch.getSession(userInfo.getUserName(), hostInfo.getHostname(), hostInfo.getPort());
            Long timeout = TimeUnit.SECONDS.toMillis(hostInfo.getTimeoutSeconds());
            session.setTimeout(timeout.intValue());
            session.setUserInfo(new PasswordlessEnabledUser());
            session.connect();
            return session;
        } catch (JSchException e) {
            String msg = ExecutionFailedException.userFriendlyCause(e.getMessage(), hostInfo.getHostname(), userInfo);
            throw new ExecutionFailedException(msg, e);
        }
    }

    private String constructCommand(String cmd, String dir, EnvVariable... envs) {
        StringBuilder builder = new StringBuilder();
        if (dir != null) {
            builder.append("cd ").append(dir).append("; ");
        }
        if (envs != null) {
            for (EnvVariable env : envs) {
                builder.append(env.prettyPrintedForShell(shell)).append("; ");
            }
        }
        String command = builder.append(cmd).append(";").toString();
        return String.format(shell.cmdFormat(), command);
    }

    private ExecResults runCommand(String cmd, String dir, EnvVariable... envs) {
        ExecResults results;
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) getSession().openChannel("exec");
            String cmdToUse = constructCommand(cmd, dir, envs);
            LOGGER.info("Executing the command [" + cmdToUse + "]");
            channel.setCommand(cmdToUse);
            channel.connect();
            StreamGuzzler output = new StreamGuzzler(channel.getInputStream());
            StreamGuzzler error = new StreamGuzzler(channel.getErrStream());
            ExecutorService executors = Executors.newFixedThreadPool(2);
            executors.submit(error);
            executors.submit(output);
            executors.shutdown();
            while (! executors.isTerminated()) {
                //Wait for all the tasks to complete.
                TimeUnit.SECONDS.sleep(1);
            }
            results = new ExecResults(output.getContent(), error.getContent(), channel.getExitStatus());
        } catch (JSchException | IOException | InterruptedException e) {
            throw new ExecutionFailedException(e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
        return results;
    }

    private void computeUserHome() {
        ExecResults results = executeCommand("echo $HOME");
        if (results != null && ! results.getOutput().isEmpty()) {
            userHomeOnRemoteHost = results.getOutput().get(0);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("User Home Directory : " + userHomeOnRemoteHost);
            }
        }
    }

    private String fixRemoteLocation(String remoteLocation) {
        String home = getHomeDirectory();
        String newLocation = remoteLocation.replaceFirst("~/", home + "/");
        return newLocation.replaceFirst("$HOME", home + "/");
    }

    private FileStatOutput identify(String remoteLocation) {
        ExecResults results = executeCommand("stat --format=%F " + remoteLocation);
        String text;
        if (results.hasErrors()) {
            text = results.getError().get(0);
        } else {
            text = results.getOutput().get(0);
        }
        return FileStatOutput.parse(text);
    }
}
