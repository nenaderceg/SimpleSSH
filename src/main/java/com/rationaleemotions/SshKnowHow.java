package com.rationaleemotions;

import com.rationaleemotions.pojo.EnvVariable;
import com.rationaleemotions.pojo.ExecResults;
import com.rationaleemotions.pojo.SSHHost;

import java.io.File;

/**
 * Represents the capabilities of a ssh supporting implementation.
 */
public interface SshKnowHow {
    /**
     * @param cmd - The command to be executed.
     * @return - A {@link ExecResults} object that represents the execution results.
     */
    ExecResults executeCommand(String cmd);

    /**
     * @param cmd - The command to be executed.
     * @param dir - The remote directory from wherein the command is to be executed.
     * @return - A {@link ExecResults} object that represents the execution results.
     */
    ExecResults executeCommand(String cmd, String dir);

    /**
     * @param cmd - The command to be executed.
     * @param env - A set of {@link EnvVariable} that represents the environment settings to be applied before
     *            attempting to execute the command against a remote host.
     * @return - A {@link ExecResults} object that represents the execution results.
     */
    ExecResults executeCommand(String cmd, EnvVariable... env);

    /**
     * @param cmd - The command to be executed.
     * @param dir - The remote directory from wherein the command is to be executed.
     * @param env - A set of {@link EnvVariable} that represents the environment settings to be applied before
     *            attempting to execute the command against a remote host.
     * @return - A {@link ExecResults} object that represents the execution results.
     */
    ExecResults executeCommand(String cmd, String dir, EnvVariable... env);

    /**
     * @param remoteLocation - The remote location to where the files are to be uploaded.
     * @param localFiles     - One or more {@link File}s to be uploaded.
     * @return - A {@link ExecResults} object that represents the upload results.
     */
    ExecResults uploadFile(String remoteLocation, File... localFiles);

    /**
     * @param localLocation - The {@link File} location to where files are to be downloaded.
     * @param remoteFiles   - One or more remote files that need to be downloaded.
     * @return - A {@link ExecResults} object that represents the download results.
     */
    ExecResults downloadFile(File localLocation, String... remoteFiles);

    /**
     * @return - The home directory of the current user.
     */
    String getHomeDirectory();

    /**
     * @param localTo    - The {@link File} location to where contents are to be downloaded.
     * @param remoteFrom - The remote folder that needs to be downloaded.
     * @return - A {@link ExecResults} object that represents the download results.
     */
    ExecResults downloadDirectory(File localTo, String remoteFrom);

    /**
     * @param localFrom - The {@link File} location from where contents are to be uploaded.
     * @param remoteTo  - The remote folder to which contents are to be uploaded.
     * @return - A {@link ExecResults} object that represents the download results.
     */
    ExecResults uploadDirectory(File localFrom, String remoteTo);

    /**
     * Helps close out the ssh connection with the remote host.
     */
    void closeConnections();

    /**
     * @param tunnelHost - The {@link SSHHost} object to which we need to enable port forwarding to.
     */
    void enableTunnellingTo(SSHHost tunnelHost);
}
