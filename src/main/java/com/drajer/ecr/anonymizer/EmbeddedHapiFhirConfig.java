package com.drajer.ecr.anonymizer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hl7.fhir.r5.utils.validation.constants.BestPracticeWarningLevel;
import org.hl7.fhir.utilities.VersionUtilities;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.validation.IgLoader;
import org.hl7.fhir.validation.ValidationEngine;
import org.hl7.fhir.validation.cli.utils.ValidationLevel;

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
			

			System.setProperty("user.home", terminologycachePath.toString());
			Path cachePath = Paths.get(terminologycachePath+"/.fhir/packages");
			Files.createDirectories(cachePath);
			
			FilesystemPackageCacheManager cacheManager = new FilesystemPackageCacheManager(
					FilesystemPackageCacheManager.FilesystemPackageCacheMode.USER);
			
			 System.out.println("Before loading filestoragePath");

		        Path filestoragePath = Paths.get("/var/task/packages").toAbsolutePath().normalize();
		        System.out.println("Loading filestoragePath: " + filestoragePath);

		        List<String> loaderSrcs = new ArrayList<>();
		        File dir = filestoragePath.toFile();

		        if (dir.exists() && dir.isDirectory()) {
		            String[] files = dir.list();
		            System.out.println("Loading IGs");

		            Arrays.stream(files).parallel().forEach(resource -> {
		                File file = new File(filestoragePath.toString(), resource);
		                System.out.println("Loading IG --> " + file.getPath());

		                if (file.exists()) {
	                        System.out.println("File exists: " + file.getAbsolutePath());

	                        try (InputStream is = new FileInputStream(file)) {

	                            try {
	                                String fullName = file.getName();
	                                String fileName = fullName.substring(0, fullName.lastIndexOf("."));
	                                String[] parts = fullName.split("\\W+");
	                                String version = "";

	                                for(int i = 0; i < parts.length - 1; ++i) {
	                                    if (parts.length - i <= 4) {
	                                        version = version + parts[i] + ".";
	                                    }
	                                }

	                                version = version.substring(0, version.length() - 1);
	                                String packageName = fileName.replace(version, "");
	                                packageName = packageName.substring(0, packageName.length() - 1);
	                                cacheManager.addPackageToCache(packageName, version, is, packageName);
	                                loaderSrcs.add(packageName + "#" + version);
	                                System.out.println("Loading IG done --> " + filestoragePath + "/" + resource);
		                    } catch (IOException e) {
		                        System.out.println("Error loading resource: " + file.getName() + " " + e);
		                    }
		                }catch (Exception e) {
		                	System.out.println("Error loading resource: " + file.getName() + " " + e);
						}
	                        
	                        } else {
		                    System.out.println("File does NOT exist: " + file.getAbsolutePath());
		                }
		            });
		        }

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
