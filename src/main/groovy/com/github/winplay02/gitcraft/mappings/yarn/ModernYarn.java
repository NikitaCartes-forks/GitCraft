package com.github.winplay02.gitcraft.mappings.yarn;

import com.github.winplay02.gitcraft.GitCraft;
import com.github.winplay02.gitcraft.meta.GameVersionBuildMeta;
import com.github.winplay02.gitcraft.types.OrderedVersion;
import com.github.winplay02.gitcraft.util.LazyValue;
import com.github.winplay02.gitcraft.util.RemoteHelper;

import java.util.List;

/**
 * Modern Yarn and its intermediary, published by RelativityMC.
 * These mappings continue Fabric's yarn and intermediary for versions without obfuscation, which Fabric does not map.
 * Both are published to the same maven layout as their Fabric counterparts, so only the coordinates differ.
 */
public class ModernYarn {

	private static final String INTERMEDIARY_ARTIFACT = "org.relativitymc:intermediary";
	private static final String YARN_ARTIFACT = "org.relativitymc:modern-yarn";

	private static final LazyValue<List<String>> INTERMEDIARY_VERSIONS = LazyValue.of(() -> readVersions(INTERMEDIARY_ARTIFACT));
	private static final LazyValue<List<String>> YARN_VERSIONS = LazyValue.of(() -> readVersions(YARN_ARTIFACT));

	public static boolean hasIntermediary(OrderedVersion mcVersion) {
		return INTERMEDIARY_VERSIONS.get().contains(mcVersion.launcherFriendlyVersionName());
	}

	public static String makeIntermediaryV2JarUrl(OrderedVersion mcVersion) {
		String version = mcVersion.launcherFriendlyVersionName();
		return String.format("%sorg/relativitymc/intermediary/%s/intermediary-%s-v2.jar", GitCraft.RELATIVITYMC_MAVEN, version, version);
	}

	/**
	 * @return meta of the latest modern yarn build for the given version, or null if no build exists
	 */
	public static GameVersionBuildMeta getLatestBuild(OrderedVersion mcVersion) {
		String gameVersion = mcVersion.launcherFriendlyVersionName();
		String prefix = gameVersion + "+build.";
		int build = YARN_VERSIONS.get().stream()
			.filter(version -> version.startsWith(prefix))
			.map(version -> version.substring(prefix.length()))
			.filter(buildNumber -> buildNumber.chars().allMatch(Character::isDigit))
			.mapToInt(Integer::parseInt)
			.max()
			.orElse(-1);
		if (build < 0) {
			return null;
		}
		return new GameVersionBuildMeta(gameVersion, "+build.", build, YARN_ARTIFACT + ":" + prefix + build, prefix + build, !mcVersion.isSnapshotOrPending());
	}

	private static List<String> readVersions(String artifact) {
		String group = artifact.substring(0, artifact.indexOf(':'));
		String name = artifact.substring(artifact.indexOf(':') + 1);
		try {
			return RemoteHelper.readMavenVersions(String.format("%s%s/%s/maven-metadata.xml", GitCraft.RELATIVITYMC_MAVEN, group.replace('.', '/'), name));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
