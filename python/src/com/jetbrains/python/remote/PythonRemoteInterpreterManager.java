package com.jetbrains.python.remote;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ParamsGroup;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.remotesdk.RemoteInterpreterException;
import com.intellij.remotesdk.RemoteSdkData;
import com.intellij.remotesdk.RemoteSdkFactory;
import com.intellij.remotesdk.RemoteSshProcess;
import com.intellij.util.NullableConsumer;
import com.intellij.util.PathMappingSettings;
import com.jetbrains.python.PythonHelpersLocator;
import com.jetbrains.python.remote.ui.RemoteProjectSettings;
import com.jetbrains.python.sdk.skeletons.PySkeletonGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * @author traff
 */
public abstract class PythonRemoteInterpreterManager implements RemoteSdkFactory<PyRemoteSdkAdditionalData> {
  public final static ExtensionPointName<PythonRemoteInterpreterManager> EP_NAME =
    ExtensionPointName.create("Pythonid.remoteInterpreterManager");
  public static final String WEB_DEPLOYMENT_PLUGIN_IS_DISABLED =
    "Remote interpreter can't be executed. Please enable the Remote Hosts Access plugin.";

  public abstract ProcessHandler startRemoteProcess(@Nullable Project project,
                                                    @NotNull PyRemoteSdkAdditionalData data,
                                                    @NotNull GeneralCommandLine commandLine,
                                                    @Nullable
                                                    PathMappingSettings mappingSettings)
    throws RemoteInterpreterException;

  public abstract ProcessHandler startRemoteProcessWithPid(@Nullable Project project,
                                                           @NotNull PyRemoteSdkAdditionalData data,
                                                           @NotNull GeneralCommandLine commandLine,
                                                           @Nullable
                                                           PathMappingSettings mappingSettings)
    throws RemoteInterpreterException;

  public abstract void addRemoteSdk(Project project, Component parentComponent, Collection<Sdk> existingSdks,
                                    NullableConsumer<Sdk> sdkCallback);


  public abstract ProcessOutput runRemoteProcess(@Nullable Project project,
                                                 RemoteSdkData data,
                                                 String[] command,
                                                 @Nullable String workingDir,
                                                 boolean askForSudo)
    throws RemoteInterpreterException;

  @NotNull
  public abstract RemoteSshProcess createRemoteProcess(@Nullable Project project,
                                                         @NotNull RemoteSdkData data,
                                                         @NotNull GeneralCommandLine commandLine, boolean allocatePty)
    throws RemoteInterpreterException;

  public abstract boolean editSdk(@NotNull Project project, @NotNull SdkModificator sdkModificator, Collection<Sdk> existingSdks);

  public abstract PySkeletonGenerator createRemoteSkeletonGenerator(@Nullable Project project,
                                                                    @Nullable Component ownerComponent,
                                                                    @NotNull Sdk sdk,
                                                                    String path);

  public abstract boolean ensureCanWrite(@Nullable Object projectOrComponent, RemoteSdkData data, String path);

  @Nullable
  public abstract RemoteProjectSettings showRemoteProjectSettingsDialog(VirtualFile baseDir, RemoteSdkData data);

  public abstract void createDeployment(Project project,
                                        VirtualFile projectDir,
                                        RemoteProjectSettings settings,
                                        RemoteSdkData data);

  public abstract void copyFromRemote(@NotNull Project project,
                                      RemoteSdkData data,
                                      List<PathMappingSettings.PathMapping> mappings);

  @Nullable
  public static PythonRemoteInterpreterManager getInstance() {
    if (EP_NAME.getExtensions().length > 0) {
      return EP_NAME.getExtensions()[0];
    }
    else {
      return null;
    }
  }

  public static void addUnbuffered(ParamsGroup exeGroup) {
    for (String param : exeGroup.getParametersList().getParameters()) {
      if ("-u".equals(param)) {
        return;
      }
    }
    exeGroup.addParameter("-u");
  }

  public static String toSystemDependent(String path, boolean isWin) {
    char separator = isWin ? '\\' : '/';
    return FileUtil.toSystemIndependentName(path).replace('/', separator);
  }

  public static void addHelpersMapping(@NotNull RemoteSdkData data, @Nullable PathMappingSettings newMappingSettings) {
    if (newMappingSettings == null) {
      newMappingSettings = new PathMappingSettings();
    }
    newMappingSettings.addMapping(PythonHelpersLocator.getHelpersRoot().getPath(), data.getHelpersPath());
  }

  public abstract PathMappingSettings setupMappings(@Nullable Project project,
                                                    @NotNull PyRemoteSdkAdditionalData data,
                                                    @Nullable PathMappingSettings mappingSettings);

  public static class PyRemoteInterpreterExecutionException extends ExecutionException {

    public PyRemoteInterpreterExecutionException() {
      super(WEB_DEPLOYMENT_PLUGIN_IS_DISABLED);
    }
  }

  public static class PyRemoteInterpreterRuntimeException extends RuntimeException {

    public PyRemoteInterpreterRuntimeException() {
      super(WEB_DEPLOYMENT_PLUGIN_IS_DISABLED);
    }
  }
}

