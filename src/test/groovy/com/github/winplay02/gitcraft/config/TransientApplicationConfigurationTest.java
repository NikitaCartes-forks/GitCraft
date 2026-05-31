package com.github.winplay02.gitcraft.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TransientApplicationConfigurationTest {
	@Test
	public void findsArtifactStorePathOverrideFromEqualsSyntax() {
		Path expected = Path.of("custom-artifact-store").toAbsolutePath().normalize();

		assertEquals(expected, TransientApplicationConfiguration.findArtifactStorePathOverride(new String[] {
			"--artifact-store-path=custom-artifact-store"
		}));
	}

	@Test
	public void findsArtifactStorePathOverrideFromSeparateArgument() {
		Path expected = Path.of("custom-artifact-store").toAbsolutePath().normalize();

		assertEquals(expected, TransientApplicationConfiguration.findArtifactStorePathOverride(new String[] {
			"--artifact-store-path",
			"custom-artifact-store"
		}));
	}

	@Test
	public void ignoresMissingArtifactStorePathOverrideValue() {
		assertNull(TransientApplicationConfiguration.findArtifactStorePathOverride(new String[] {
			"--artifact-store-path",
			"--help"
		}));
	}
}
