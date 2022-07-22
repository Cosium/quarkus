package io.quarkus.deployment.pkg.steps;

import static io.quarkus.deployment.pkg.steps.LinuxIDUtil.getLinuxID;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang3.SystemUtils;
import org.jboss.logging.Logger;

import io.quarkus.deployment.pkg.NativeConfig;
import io.quarkus.deployment.pkg.builditem.CompiledJavaVersionBuildItem;
import io.quarkus.deployment.util.FileUtil;
import io.quarkus.runtime.util.ContainerRuntimeUtil;

public class NativeImageBuildLocalContainerRunner extends NativeImageBuildContainerRunner {

    private static final Logger LOGGER = Logger.getLogger(NativeImageBuildLocalContainerRunner.class.getName());

    public NativeImageBuildLocalContainerRunner(NativeConfig nativeConfig, Path outputDir,
            CompiledJavaVersionBuildItem.JavaVersion javaVersion) {
        super(nativeConfig, outputDir, javaVersion);
        if (SystemUtils.IS_OS_LINUX) {
            ArrayList<String> containerRuntimeArgs = new ArrayList<>(Arrays.asList(baseContainerRuntimeArgs));
            if (isDockerRootless(containerRuntime)) {
                Collections.addAll(containerRuntimeArgs, "--user", String.valueOf(0));
            } else {
                String uid = getLinuxID("-ur");
                String gid = getLinuxID("-gr");
                if (uid != null && gid != null && !uid.isEmpty() && !gid.isEmpty()) {
                    Collections.addAll(containerRuntimeArgs, "--user", uid + ":" + gid);
                    if (containerRuntime == ContainerRuntimeUtil.ContainerRuntime.PODMAN) {
                        // Needed to avoid AccessDeniedExceptions
                        containerRuntimeArgs.add("--userns=keep-id");
                    }
                }
            }
            baseContainerRuntimeArgs = containerRuntimeArgs.toArray(baseContainerRuntimeArgs);
        }
    }

    private static boolean isDockerRootless(ContainerRuntimeUtil.ContainerRuntime containerRuntime) {
        if (containerRuntime != ContainerRuntimeUtil.ContainerRuntime.DOCKER) {
            return false;
        }
        String dockerHost = System.getenv("DOCKER_HOST");
        // docker socket?
        String socketUriPrefix = "unix://";
        if (dockerHost == null || !dockerHost.startsWith(socketUriPrefix)) {
            return false;
        }
        String dockerSocket = dockerHost.substring(socketUriPrefix.length());
        String currentUid = getLinuxID("-ur");
        if (currentUid == null || currentUid.isEmpty() || currentUid.equals(String.valueOf(0))) {
            return false;
        }

        int socketOwnerUid;
        try {
            socketOwnerUid = (int) Files.getAttribute(Path.of(dockerSocket), "unix:uid", LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            LOGGER.infof("Owner UID lookup on '%s' failed with '%s'", dockerSocket, e.getMessage());
            return false;
        }
        return currentUid.equals(String.valueOf(socketOwnerUid));
    }

    @Override
    protected List<String> getContainerRuntimeBuildArgs() {
        List<String> containerRuntimeArgs = super.getContainerRuntimeBuildArgs();
        String volumeOutputPath = outputPath;
        if (SystemUtils.IS_OS_WINDOWS) {
            volumeOutputPath = FileUtil.translateToVolumePath(volumeOutputPath,
                    containerRuntime == ContainerRuntimeUtil.ContainerRuntime.PODMAN);
        }

        Collections.addAll(containerRuntimeArgs, "-v",
                volumeOutputPath + ":" + NativeImageBuildStep.CONTAINER_BUILD_VOLUME_PATH + ":z");
        return containerRuntimeArgs;
    }
}
