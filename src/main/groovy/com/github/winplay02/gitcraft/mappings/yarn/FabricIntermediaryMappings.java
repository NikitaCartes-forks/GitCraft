package com.github.winplay02.gitcraft.mappings.yarn;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.GitCraftApplication;
import com.github.winplay02.gitcraft.GitCraftQuirks;
import com.github.winplay02.gitcraft.mappings.Mapping;
import com.github.winplay02.gitcraft.pipeline.GitCraftPipelineFilesystemRoot;
import com.github.winplay02.gitcraft.pipeline.GitCraftPipelineFilesystemStorage;
import com.github.winplay02.gitcraft.pipeline.IStepContext;
import com.github.winplay02.gitcraft.pipeline.key.MinecraftJar;
import com.github.winplay02.gitcraft.pipeline.StepStatus;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.FileSystemNetworkManager;
import com.github.winplay02.gitcraft.util.RemoteHelper;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.mappingio.MappingVisitor;
import net.fabricmc.mappingio.MappingWriter;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.tiny.Tiny1FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public class FabricIntermediaryMappings extends Mapping {
	@Override
	public String getName() {
		return "Fabric Intermediary";
	}

	@Override
	public String getDestinationNS() {
		return MappingsNamespace.INTERMEDIARY.toString();
	}

	@Override
	public boolean needsPackageFixingForLaunch() {
		return false;
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion) {
		if (GitCraftQuirks.intermediaryMissingVersions.contains(mcVersion.launcherFriendlyVersionName())) { // exclude missing versions
			return false;
		}
		if (mcVersion.isNotObfuscated()) { // fabric does not map non-obfuscated versions, modern intermediary continues them
			return getLocalMappingsFile(mcVersion) != null || ModernYarn.hasIntermediary(mcVersion);
		}
		return mcVersion.compareTo(GitCraft.getApplicationConfiguration().manifestSource().getMetadataProvider().getVersionByVersionID(GitCraftQuirks.FABRIC_INTERMEDIARY_MAPPINGS_START_VERSION_ID)) >= 0;
	}

	@Override
	public boolean doMappingsExist(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		// fabric intermediary is provided for the merged jar
		return minecraftJar == MinecraftJar.MERGED && doMappingsExist(mcVersion);
	}

	@Override
	public boolean canMappingsBeUsedOn(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		// the merged mappings can be used for all jars
		return doMappingsExist(mcVersion);
	}

	protected static String mappingsIntermediaryPathQuirkVersion(String version) {
		return GitCraftQuirks.yarnInconsistentVersionNaming.getOrDefault(version, version);
	}

	/**
	 * The local repos may be checkouts of any intermediary repository, as they all use the same layout.
	 *
	 * @return the mapping file of the given version inside the first local intermediary repo containing it, or null
	 * if no repo is configured or no repo contains the version
	 */
	private static Path getLocalMappingsFile(OrderedVersion mcVersion) {
		Path[] repoPaths = GitCraftApplication.getTransientApplicationConfiguration().fabricIntermediaryRepoPaths();
		if (repoPaths == null) {
			return null;
		}
		String quirkVersion = mappingsIntermediaryPathQuirkVersion(mcVersion.launcherFriendlyVersionName());
		for (Path repoPath : repoPaths) {
			Path localMappingsFile = repoPath.resolve("mappings").resolve(quirkVersion + ".tiny");
			if (Files.exists(localMappingsFile)) {
				return localMappingsFile;
			}
		}
		return null;
	}

	@Override
	public StepStatus provideMappings(IStepContext<?, OrderedVersion> versionContext, MinecraftJar minecraftJar) throws IOException {
		// fabric intermediary is provided for the merged jar
		if (minecraftJar != MinecraftJar.MERGED) {
			return StepStatus.NOT_RUN;
		}
		Path mappingsFile = getMappingsPathInternal(versionContext.targetVersion(), minecraftJar);
		if (Files.exists(mappingsFile) && validateMappings(mappingsFile)) {
			return StepStatus.UP_TO_DATE;
		}
		Files.deleteIfExists(mappingsFile);
		// a local repo of modern intermediary is used if it contains the version, otherwise maven provides it
		if (versionContext.targetVersion().isNotObfuscated() && getLocalMappingsFile(versionContext.targetVersion()) == null) {
			return StepStatus.merge(provideModernMappings(versionContext, mappingsFile), StepStatus.SUCCESS);
		}
		Path mappingsV1 = getMappingsPathInternalV1(versionContext.targetVersion());
		String quirkVersion = mappingsIntermediaryPathQuirkVersion(versionContext.targetVersion().launcherFriendlyVersionName());
		Path[] fabricIntermediaryRepoPaths = GitCraftApplication.getTransientApplicationConfiguration().fabricIntermediaryRepoPaths();
		StepStatus downloadStatus;
		if (fabricIntermediaryRepoPaths != null) {
			Path localMappingFile = getLocalMappingsFile(versionContext.targetVersion());
			if (localMappingFile == null) {
				throw new IOException(String.format("Fabric intermediary mapping file not found for version '%s' in any of: %s", quirkVersion, Arrays.toString(fabricIntermediaryRepoPaths)));
			}
			Files.copy(localMappingFile, mappingsV1, StandardCopyOption.REPLACE_EXISTING);
			downloadStatus = StepStatus.SUCCESS;
		} else {
			downloadStatus = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryGitHub(versionContext.executorService(), "FabricMC/intermediary", "master", String.format("mappings/%s.tiny", quirkVersion), new FileSystemNetworkManager.LocalFileInfo(mappingsV1, null, null, "intermediary mapping", versionContext.targetVersion().launcherFriendlyVersionName()));
		}
		MemoryMappingTree mappingTree = new MemoryMappingTree();
		try (BufferedReader br = Files.newBufferedReader(mappingsV1, StandardCharsets.UTF_8)) {
			Tiny1FileReader.read(br, mappingTree);
		}
		try (MappingWriter writer = MappingWriter.create(mappingsFile, MappingFormat.TINY_2_FILE)) {
			mappingTree.accept(writer);
		}
		return StepStatus.merge(downloadStatus, StepStatus.SUCCESS);
	}

	/**
	 * Modern intermediary is published to maven in tiny-v2 format, so it only needs to be extracted.
	 */
	private StepStatus provideModernMappings(IStepContext<?, OrderedVersion> versionContext, Path mappingsFile) throws IOException {
		OrderedVersion mcVersion = versionContext.targetVersion();
		Path mappingsFileJar = GitCraftPipelineFilesystemRoot.getMappings().apply(GitCraftPipelineFilesystemStorage.DEFAULT.get().rootFilesystem()).resolve(mcVersion.launcherFriendlyVersionName() + "-intermediary-v2.jar");
		StepStatus downloadStatus = RemoteHelper.downloadToFileWithChecksumIfNotExistsNoRetryMaven(versionContext.executorService(), ModernYarn.makeIntermediaryV2JarUrl(mcVersion), new FileSystemNetworkManager.LocalFileInfo(mappingsFileJar, null, null, "modern intermediary mapping", mcVersion.launcherFriendlyVersionName()));
		try (FileSystem fs = FileSystems.newFileSystem(mappingsFileJar)) {
			Files.copy(fs.getPath("mappings", "mappings.tiny"), mappingsFile, StandardCopyOption.REPLACE_EXISTING);
		}
		return downloadStatus;
	}

	protected Path getMappingsPathInternalV1(OrderedVersion mcVersion) {
		return GitCraftPipelineFilesystemRoot.getMappings().apply(GitCraftPipelineFilesystemStorage.DEFAULT.get().rootFilesystem()).resolve(mcVersion.launcherFriendlyVersionName() + "-intermediary-v1.tiny");
	}

	@Override
	protected Path getMappingsPathInternal(OrderedVersion mcVersion, MinecraftJar minecraftJar) {
		return GitCraftPipelineFilesystemRoot.getMappings().apply(GitCraftPipelineFilesystemStorage.DEFAULT.get().rootFilesystem()).resolve(mcVersion.launcherFriendlyVersionName() + "-intermediary.tiny");
	}

	@Override
	public void visit(OrderedVersion mcVersion, MinecraftJar minecraftJar, MappingVisitor visitor) throws IOException {
		Path path = getMappingsPathInternal(mcVersion, MinecraftJar.MERGED);
		try (BufferedReader br = Files.newBufferedReader(path)) {
			Tiny2FileReader.read(br, visitor);
		}
	}
}
