package com.drajer.ecr.anonymizer;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.validation.IgLoader;
import org.hl7.fhir.validation.ValidationEngine;
import org.hl7.fhir.validation.cli.utils.ValidationLevel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;

public class EmbeddedHapiFhirConfig {

	private String destPath = System.getProperty("java.io.tmpdir");


	public FhirContext getR5FhirContext() {
		return FhirContext.forR4();
	}
	public IParser createParser(FhirContext fhirContext) {
		return fhirContext.newJsonParser();
	}

	public ValidationEngine createValidationEngine() {
		try {

			System.out.println("avaiable processor " + Runtime.getRuntime().availableProcessors());
			Path terminologycachePath = Path.of("/tmp");
			System.out.println("terminologycache Path:::::{}" +terminologycachePath);

			final String fhirSpecVersion = "4.0";
			final String definitions = VersionUtilities.packageForVersion(fhirSpecVersion) + "#"
					+ VersionUtilities.getCurrentVersion(fhirSpecVersion);
			System.out.println("Definitions:::::{}"+ definitions);
			final String txServer = true ? "http://tx.fhir.org" : null;
			final String fhirVersion = "4.0.1";
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
						System.out.println("Error loading resource: " + resource.getFilename()+""+ e);
					}
				}
			});

			System.out.println("Initializing HL7 Validator inside Validator");
			ValidationEngine validationEngine = getValidationEngine(definitions, null, true, fhirSpecVersion,
					cacheManager, terminologycachePath);
			System.out.println("Done initializing");

			IgLoader igLoader = new IgLoader(cacheManager, validationEngine.getContext(),
					validationEngine.getVersion());
			loaderSrcs.parallelStream().forEach(loaderSrc -> {
				try {
					igLoader.loadIg(validationEngine.getIgs(), validationEngine.getBinaries(), loaderSrc, false);
				} catch (Exception e) {
					System.out.println("Error loading IG: " + loaderSrc +" "+ e);
				}
			});

			validationEngine.setAnyExtensionsAllowed(true);
			validationEngine.setHintAboutNonMustSupport(true);
			validationEngine.setNoExtensibleBindingMessages(true);
			validationEngine.setNoInvariantChecks(false);
			validationEngine.setAssumeValidRestReferences(true);

			validationEngine.setDebug(true);
			validationEngine.setLevel(ValidationLevel.ERRORS);
            validationEngine.setBestPracticeLevel(BestPracticeWarningLevel.Warning.Ignore);
			validationEngine.prepare();

			return validationEngine;
		} catch (Exception e) {
			System.out.println(e.getMessage());
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
