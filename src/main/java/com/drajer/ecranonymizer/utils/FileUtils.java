package com.drajer.ecranonymizer.utils;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class FileUtils {

	private static final Logger logger = LoggerFactory.getLogger(FileUtils.class);

	private static final ObjectMapper mapper = new ObjectMapper();

	public static <T> T readFileContents(String filename, TypeReference<T> typeReference) {

		T object = null;
		try {
			InputStream inputStream = new ClassPathResource(filename).getInputStream();
			object = mapper.readValue(inputStream, typeReference);
		} catch (Exception e) {

			logger.info("Error in parsing : " + filename + " to Object");
		}

		return object;
	}

	/**
	 * The method saves the provided data to a file.
	 *
	 * @param data     -- The data to be saved.
	 * @param filename -- The filename to be used for saving the data.
	 * @throws IOException
	 */
	public static void saveDataToFile(String data, String filename) throws IOException {
		try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename), "UTF-8"))) {
			logger.info("Writing data to file: {}", filename);
			writer.write(data);
		} catch (IOException e) {
			logger.debug("Unable to write data to file: {}", filename, e);
			throw e;
		}
	}

	/**
	 * method will used to validate file extension
	 *
	 * @param file
	 * @param fileExtension
	 * @return boolean
	 */

	public static boolean validateFileExtension(MultipartFile file, String fileExtension) {
		return fileExtension.equalsIgnoreCase(FilenameUtils.getExtension(file.getOriginalFilename()));
	}

	public static void writeErrorsToFile(List<String> validationMessages, Path filePath) {
		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath.toString()))) {
			for (String logMessage : validationMessages) {

				writer.write(logMessage);
				writer.newLine();

			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
