package com.drajer.ecranonymizer.config;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.validation.IgLoader;
import org.hl7.fhir.validation.ValidationEngine;
import org.hl7.fhir.validation.cli.utils.ValidationLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

/**
 * Class EmbeddedHapiFhirConfig TODO
 *
 * @author Drajer LLC
 * @since 14-04-2023
 */
@Component
public class EmbeddedHapiFhirConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedHapiFhirConfig.class);
	public static final String VERSION_5_0_0 = "5.0.0";

	@Value("${ecr.anonymizer.cache.file}")
	private String ecrAnonymizerCacheFile;

	private static final String COMMA_SEPARATOR = ",";

	@Bean
	public FhirContext getR5FhirContext() {
		return FhirContext.forR4();
	}

	@Bean
	public IParser createParser(FhirContext fhirContext) {
		return fhirContext.newJsonParser();
	}

	@Autowired
	Environment environment;

	@Bean
	public ValidationEngine createValidationEngine() {
		try {

			System.out.println("avaiable processor " + Runtime.getRuntime().availableProcessors());
			Path terminologycachePath = Path.of(ecrAnonymizerCacheFile);
			LOGGER.info("terminologycache Path:::::{}", terminologycachePath);

			final String fhirSpecVersion = "4.0";
			final String definitions = VersionUtilities.packageForVersion(fhirSpecVersion) + "#"
					+ VersionUtilities.getCurrentVersion(fhirSpecVersion);
			LOGGER.info("Definitions:::::{}", definitions);
			final String txServer = true ? "http://tx.fhir.org" : null;
			final String fhirVersion = "4.0.1";

			String cachefolderpath = environment.getProperty("ecr.anonymizer.cache.file");
			if (cachefolderpath == null || cachefolderpath.isEmpty()) {
				throw new IllegalArgumentException("Cache folder path cannot be null or empty");
			}

//
			System.setProperty("user.home", ecrAnonymizerCacheFile);
			Path cachePath = Paths.get(ecrAnonymizerCacheFile+"/.fhir/packages");
			Files.createDirectories(cachePath);

			FilesystemPackageCacheManager cacheManager = new FilesystemPackageCacheManager(
					FilesystemPackageCacheManager.FilesystemPackageCacheMode.USER);
			String path1 = this.getClass().getClassLoader().getResource("packages").getPath().toString();
			String path = new ClassPathResource("packages").getURI().toString();
			Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:/packages/*");
			File packagePath = new File(path);
			List<String> loaderSrcs = new ArrayList<>();

			Arrays.stream(resources).parallel().forEach(resource -> {
				if (resource.exists() && resource.isReadable()) {
					try (InputStream is = resource.getInputStream()) {
						String fullName = resource.getFilename();
						String fileName = fullName.substring(0, fullName.lastIndexOf("."));
						String[] parts = fullName.split("\\W+");
						String version = "";
						for (int i = 0; i < parts.length - 1; i++) {
							if (parts.length - i <= 4) {
								version += parts[i] + ".";
							}
						}
						version = version.substring(0, version.length() - 1);
						String packageName = fileName.replace(version, "");
						packageName = packageName.substring(0, packageName.length() - 1);
						cacheManager.addPackageToCache(packageName, version, is, packageName);
						loaderSrcs.add(packageName + "#" + version);

					} catch (Exception e) {
						LOGGER.error("Error loading resource: " + resource.getFilename(), e);
					}
				}
			});

			LOGGER.info("Initializing HL7 Validator inside Validator");
			ValidationEngine validationEngine = getValidationEngine(definitions, null, true, fhirSpecVersion,
					cacheManager, terminologycachePath);
			LOGGER.info("Done initializing");

			IgLoader igLoader = new IgLoader(cacheManager, validationEngine.getContext(),
					validationEngine.getVersion());
			loaderSrcs.parallelStream().forEach(loaderSrc -> {
				try {
					igLoader.loadIg(validationEngine.getIgs(), validationEngine.getBinaries(), loaderSrc, false);
				} catch (Exception e) {
					LOGGER.error("Error loading IG: " + loaderSrc, e);
				}
			});

			validationEngine.setAnyExtensionsAllowed(true);
			validationEngine.setHintAboutNonMustSupport(true);
			validationEngine.setNoExtensibleBindingMessages(true);
			validationEngine.setNoInvariantChecks(false);
			validationEngine.setAssumeValidRestReferences(true);

			validationEngine.setDebug(true);
			validationEngine.setLevel(ValidationLevel.ERRORS);
			validationEngine.prepare();

			return validationEngine;
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
		return null;
	}

	public static ValidationEngine getValidationEngine(String src, String path, boolean canRunWithoutTerminologyServer,
			String vString, FilesystemPackageCacheManager pcm, Path terminologycachePath) throws Exception {

		final ValidationEngine validationEngine = new ValidationEngine.ValidationEngineBuilder()
				.withCanRunWithoutTerminologyServer(canRunWithoutTerminologyServer).withVersion(vString)
				.withTerminologyCachePath(terminologycachePath.toString()).fromSource(src).setPcm(pcm);
		return validationEngine;
	}

}
