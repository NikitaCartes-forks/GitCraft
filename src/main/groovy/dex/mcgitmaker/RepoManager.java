package dex.mcgitmaker;

import com.github.winplay02.MappingHelper;
import com.github.winplay02.MinecraftVersionGraph;
import com.github.winplay02.MiscHelper;
import dex.mcgitmaker.data.McMetadata;
import dex.mcgitmaker.data.McVersion;
import dex.mcgitmaker.loom.FileSystemUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.StreamSupport;

class RepoManager implements Closeable {
	Git git;
	MinecraftVersionGraph versionGraph;
	Path root_path;

	public RepoManager(MinecraftVersionGraph versionGraph, Path root_path) throws GitAPIException {
		this.versionGraph = versionGraph;
		this.root_path = Objects.requireNonNullElse(root_path, GitCraft.REPO);
		this.git = setupRepo();
	}

	@Override
	public void close() {
		git.close();
	}

	RevCommit findBaseForNonLinearVersion(McVersion mcVersion) throws IOException, GitAPIException {
		Optional<McVersion> previousVersion = versionGraph.getPreviousNode(mcVersion);
		if (previousVersion.isEmpty()) {
			MiscHelper.panic("Cannot commit non-linear version %s, as the base version was not found", mcVersion.version);
		}
		return findVersionObjectCurrentBranch(previousVersion.get());
	}

	void switchHEAD(Ref ref) throws IOException {
		RefUpdate refUpdate = git.getRepository().getRefDatabase().newUpdate(Constants.HEAD, false);
		RefUpdate.Result result = refUpdate.link(ref.getName());
		if (result != RefUpdate.Result.FORCED) {
			MiscHelper.panic("Unsuccessfully changed HEAD to %s, result was: %s", ref, result);
		}
	}

	void checkoutVersionBranch(String target_branch, McVersion mcVersion) throws IOException, GitAPIException {
		if (!Objects.equals(git.getRepository().getBranch(), target_branch)) {
			Ref target_ref = git.getRepository().getRefDatabase().findRef(target_branch);
			if (target_ref == null) {
				RevCommit branchPoint = findBaseForNonLinearVersion(mcVersion);
				if (branchPoint == null) {
					MiscHelper.panic("Could not find branching point for non-linear version: %s (%s)", mcVersion.version, mcVersion.loaderVersion);
				}
				target_ref = git.branchCreate().setStartPoint(branchPoint).setName(target_branch).call();
			}
			switchHEAD(target_ref);
		}
	}

	boolean findVersionCurrentBranch(McVersion mcVersion) throws GitAPIException, IOException {
		return git.log().all().setRevFilter(new CommitMsgFilter(mcVersion.toCommitMessage())).call().iterator().hasNext();
	}

	RevCommit findVersionObjectCurrentBranch(McVersion mcVersion) throws GitAPIException, IOException {
		Iterator<RevCommit> iterator = git.log().all().setRevFilter(new CommitMsgFilter(mcVersion.toCommitMessage())).call().iterator();
		if (iterator.hasNext()) {
			return iterator.next();
		}
		return null;
	}

	void clearWorkingTree() throws IOException {
		for (File it : Objects.requireNonNull(this.root_path.toFile().listFiles())) {
			if (!it.toPath().toString().endsWith(".git")) {
				if (it.isDirectory()) {
					MiscHelper.deleteDirectory(it.toPath());
				} else {
					it.delete();
				}
			}
		}
	}

	void copyFiles(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) throws IOException {
		// Copy decompiled MC to repo directory
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mcVersion.decompiledMc(mappingFlavour))) {
			MiscHelper.copyLargeDir(fs.get().getPath("."), this.root_path.resolve("minecraft").resolve("src"));
		}

		// Copy assets & data (it makes sense to track them, atleast the data)
		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(mcVersion.mergedJarPath())) {
			if (GitCraft.config.loadAssets) {
				MiscHelper.copyLargeDir(fs.get().getPath("assets"), this.root_path.resolve("minecraft").resolve("resources").resolve("assets"));
			}
			if (GitCraft.config.loadIntegratedDatapack) {
				MiscHelper.copyLargeDir(fs.get().getPath("data"), this.root_path.resolve("minecraft").resolve("resources").resolve("data"));
			}
		}

		if (GitCraft.config.loadAssets && GitCraft.config.loadAssetsExtern) {
			McMetadata.copyExternalAssetsToRepo(mcVersion, this.root_path);
		}

		// Copy additional files
		if (GitCraft.config.additionalFilesPath != null) {
			MiscHelper.copyLargeDir(GitCraft.config.additionalFilesPath, this.root_path);
		}
	}

	void commitDecompiled(McVersion mcVersion, MappingHelper.MappingFlavour mappingFlavour) throws GitAPIException, IOException {
		MiscHelper.println("Version: %s", mcVersion.version);
		String target_branch;
		if (git.getRepository().resolve(Constants.HEAD) != null) { // Don't run on empty repo
			target_branch = MinecraftVersionGraph.isVersionNonLinearSnapshot(mcVersion) ? resolveNonLinearBranch(mcVersion) : GitCraft.config.gitMainlineLinearBranch;
			checkoutVersionBranch(target_branch.replace(" ", "-"), mcVersion);
			if (findVersionCurrentBranch(mcVersion)) {
				MiscHelper.println("Version %s already exists in repo, skipping", mcVersion.version);
				return;
			}
			if (mcVersion.equals(versionGraph.getRootVersion())) {
				MiscHelper.panic("HEAD is not empty, but the current version is the root version, and should be the initial commit");
			}
			Optional<RevCommit> tip_commit = StreamSupport.stream(git.log().setMaxCount(1).call().spliterator(), false).findFirst();
			if (tip_commit.isEmpty()) {
				MiscHelper.panic("HEAD is not empty, but a root commit can not be found");
			}
			Optional<McVersion> prev_version = versionGraph.getPreviousNode(mcVersion);
			if (prev_version.isEmpty()) {
				MiscHelper.panic("HEAD is not empty, but current version does not have a preceding version");
			}
			if (!Objects.equals(tip_commit.get().getFullMessage(), prev_version.get().toCommitMessage())) {
				MiscHelper.panic("This repository is wrongly ordered. Please remove the unordered commits or delete the entire repository");
			}
		} else {
			if (!mcVersion.equals(versionGraph.getRootVersion())) {
				MiscHelper.panic("A non-root version is committed as the root commit to the repository");
			}
			target_branch = GitCraft.config.gitMainlineLinearBranch;
		}

		mcVersion.decompiledMc(mappingFlavour);
		MiscHelper.executeTimedStep("Clearing working directory...", this::clearWorkingTree);
		MiscHelper.executeTimedStep("Moving files to repo...", () -> copyFiles(mcVersion, mappingFlavour));

		// Make commit
		MiscHelper.executeTimedStep("Committing files to repo...", () -> {
			// Remove removed files from index
			git.add().addFilepattern(".").setRenormalize(false).setUpdate(true).call();
			// Stage new files
			git.add().addFilepattern(".").setRenormalize(false).call();
			Date version_date = new Date(OffsetDateTime.parse(mcVersion.time).toInstant().toEpochMilli());
			PersonIdent author = new PersonIdent(GitCraft.config.gitUser, GitCraft.config.gitMail, version_date, TimeZone.getTimeZone("UTC"));
			git.commit().setMessage(mcVersion.toCommitMessage()).setAuthor(author).setCommitter(author).setSign(false).call();
			if (!mcVersion.version.equals("23w13a_or_b_original")) {
				git.tag().setMessage(mcVersion.toCommitMessage()).setName(mcVersion.toCommitMessage().replace(" ", "_")).setTagger(author).setSigned(false).call();
			}
		});
		MiscHelper.println("Committed %s to the repository! (Target Branch is %s)", mcVersion.version, target_branch + (MinecraftVersionGraph.isVersionNonLinearSnapshot(mcVersion) ? " (non-linear)" : ""));
	}

	private String resolveNonLinearBranch(McVersion mcVersion) {
		switch (mcVersion.loaderVersion) {
			// Combat
			case "1.14.3-rc.4.combat.1" -> {
				return "Combat-Snapshot/1.14.3";
			}
			case "1.14.5-combat.2", "1.14.5-combat.3" -> {
				return "Combat-Snapshot/1.14.4";
			}
            case "1.15-rc.3.combat.4" -> {
				return "Combat-Snapshot/1.15";
			}
			case "1.15.2-rc.2.combat.5" -> {
				return "Combat-Snapshot/1.15.2";
			}
			case "1.16.2-beta.3.combat.6" -> {
				return "Combat-Snapshot/1.16.2-pre3";
			}
			case "1.16.3-combat.7" -> {
				return "Combat-Snapshot/1.16.2";
			}
			case "1.16.3-combat.7.b", "1.16.3-combat.7.c", "1.16.3-combat.8", "1.16.3-combat.8.b", "1.16.3-combat.8.c" -> {
				return "Combat-Snapshot/1.16.3";
			}
            // April
			case "1.14-alpha.19.13.shareware" -> {
				return "April-Snapshot/3D_Shareware_v1.34";
			}
			case "1.16-alpha.20.13.inf" -> {
				return "April-Snapshot/20w14infinite";
			}
			case "1.19-alpha.22.13.oneblockatatime" -> {
				return "April-Snapshot/22w13oneblockatatime";
			}
			case "1.20-alpha.23.13.ab.original", "1.20-alpha.23.13.ab.replaced" -> {
				return "April-Snapshot/23w13a_or_b";
			}
            // Experimental
			case "1.18-alpha.0.0.Experimental.1", "1.18-alpha.0.0.Experimental.2", "1.18-alpha.0.0.Experimental.3",
				"1.18-alpha.0.0.Experimental.4", "1.18-alpha.0.0.Experimental.5", "1.18-alpha.0.0.Experimental.6",
				"1.18-alpha.0.0.Experimental.7" -> {
				return "Experimental-Snapshot/1.18";
			}
            case "1.19-alpha.0.0.Experimental.1" -> {
				return "Experimental-Snapshot/1.19";
			}
			default -> {
				return mcVersion.loaderVersion;
			}
		}
	}

	Git setupRepo() throws GitAPIException {
		return Git.init().setInitialBranch(GitCraft.config.gitMainlineLinearBranch).setDirectory(this.root_path.toFile()).call();
	}

	private static final class CommitMsgFilter extends RevFilter {
		String msg;

		CommitMsgFilter(String msg) {
			this.msg = msg;
		}

		@Override
		public boolean include(RevWalk walker, RevCommit c) {
			return Objects.equals(c.getFullMessage(), this.msg);
		}

		@Override
		public RevFilter clone() {
			return this;
		}

		@Override
		public boolean requiresCommitBody() {
			return true;
		}

		@Override
		public String toString() {
			return "MSG FILTER";
		}
	}
}
